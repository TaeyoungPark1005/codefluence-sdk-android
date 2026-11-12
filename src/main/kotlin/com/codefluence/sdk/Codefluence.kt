package com.codefluence.sdk

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

class Codefluence private constructor(
    hostContext: Context?,
    private val publicKey: String,
    private val apiHost: String,
    injectedPreferences: SharedPreferences? = null,
    transport: CodefluenceTransport? = null,
    workExecutor: ((() -> Unit) -> Unit)? = null,
    mainExecutor: ((() -> Unit) -> Unit)? = null,
    purchaseSource: PurchaseSource? = null,
    integritySource: IntegrityTokenSource? = null,
    clock: (() -> Long)? = null,
    debuggable: Boolean? = null
) {
    private val purchaseSource: PurchaseSource? = purchaseSource ?: PlayBillingPurchaseSource.createIfAvailable(hostContext)
    data class Purchase(val purchaseToken: String, val products: List<String>, val obfuscatedAccountId: String? = null)
    private val context = hostContext?.applicationContext

    /**
     * 이 빌드의 종류(`"debug"` 또는 `"release"`). 모든 서버 요청의 `build` 필드로 실린다.
     * 호스트 앱이 디버그 가능(`ApplicationInfo.FLAG_DEBUGGABLE`, 보통 debug 빌드 타입)이면 `"debug"`다.
     * 에뮬레이터는 판정하지 않는다(오탐으로 실사용자를 개발 빌드로 분류하지 않게). 서버는 `"debug"` 신호를
     * 설치 점검에만 보여 주고 운영 집계·보상에서 뺀다(sandbox). 테스트 구매는 Play 응답으로 따로 판정한다.
     */
    val build: String = if (debuggable ?: ((context?.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0)) "debug" else "release"
    private val activityReference = (hostContext as? Activity)?.let { WeakReference(it) }
    private val preferences: SharedPreferences = injectedPreferences
        ?: requireNotNull(context) { "A Context is required without injected preferences" }
            .getSharedPreferences("codefluence", Context.MODE_PRIVATE)
    private val decisions = ConfirmationDecisions(preferences)
    private val mainHandler: Handler? = if (injectedPreferences == null) Handler(Looper.getMainLooper()) else null

    // HTTP seam (A3/C3). Default is HttpURLConnection; tests inject a fake.
    private val transport: CodefluenceTransport = transport ?: SystemHttpTransport()
    // Background and main-thread dispatch. Defaults use a single-thread executor
    // and the main Looper; tests inject synchronous runners.
    private val realExecutor = if (workExecutor == null) Executors.newSingleThreadExecutor() else null
    private val workExecutor: (() -> Unit) -> Unit =
        workExecutor ?: { work -> realExecutor!!.execute(work) }
    private val mainExecutor: (() -> Unit) -> Unit =
        mainExecutor ?: { work -> mainHandler!!.post(work) }

    // Daily activity signal (SPEC §29). Everything is gated by the server; with the feature off nothing leaves the device.
    private val activitySignals = ActivitySignalClient(
        preferences, this.transport, publicKey, apiHost,
        integritySource ?: if (injectedPreferences == null) PlayIntegrityTokenSource.createIfAvailable(hostContext) else null,
        clock ?: { System.currentTimeMillis() }, this.workExecutor, this.mainExecutor, build
    )

    // Ad revenue reports (SPEC §26.2). Gated by the server the same way; with the feature off nothing leaves the device.
    private val adRevenue = AdRevenueClient(
        preferences, this.transport, publicKey, apiHost,
        integritySource ?: if (injectedPreferences == null) PlayIntegrityTokenSource.createIfAvailable(hostContext) else null,
        clock ?: { System.currentTimeMillis() }, this.workExecutor, this.mainExecutor, build
    )

    /**
     * Forwards one AdMob paid event. Call it from the paid event listener of every ad format:
     * `ad.setOnPaidEventListener { value -> codefluence.reportAdRevenue(value.valueMicros, value.currencyCode, precision) }`
     * where `precision` maps `AdValue.PrecisionType`. [CodefluenceAdValuePrecision.UNKNOWN] (for example test ads) counts the impression
     * and adds no revenue. Values are summed on the device and sent at most once an hour; nothing leaves the device while the server
     * has ad revenue turned off. Never throws.
     */
    fun reportAdRevenue(valueMicros: Long, currencyCode: String, precision: CodefluenceAdValuePrecision) = adRevenue.report(valueMicros, currencyCode, precision)

    /** Sends the accumulated ad revenue batch if the server allows it and the hourly gate is open. Safe to call on every launch. */
    fun flushAdRevenueIfNeeded(onResult: ((CodefluenceAdRevenueOutcome) -> Unit)? = null) = adRevenue.flushIfNeeded(onResult)

    /**
     * Records that the user reached a product milestone (for example `onboarded`). Names use letters, digits, `_`, `-` and `.`
     * (up to 40 characters); anything else is ignored. The name is sent with the next attested daily signal and only when
     * the campaign asks for milestones. Nothing leaves the device if the server has the feature turned off.
     */
    fun trackMilestone(name: String, onResult: ((CodefluenceActivitySignalOutcome) -> Unit)? = null) = activitySignals.trackMilestone(name, onResult)

    /**
     * Sends today's activity signal for the confirmed referral when the server has the feature on, Play Integrity is available and
     * no signal was sent today (a new milestone allows one more). Safe to call on every launch and foreground; failures are
     * returned as [CodefluenceActivitySignalOutcome.Skipped] and never thrown.
     */
    fun sendActivitySignalIfNeeded(onResult: ((CodefluenceActivitySignalOutcome) -> Unit)? = null) = activitySignals.sendIfNeeded(onResult)

    // A3 automatic retry dependencies. Defaults use real time/jitter/monitor;
    // tests inject deterministic values.
    private var backoffPolicy = CodefluenceBackoffPolicy()
    private var jitterProvider: () -> Double = { Math.random() }
    private var scheduler: (Long, () -> Unit) -> Unit = { delay, work -> mainHandler?.postDelayed(work, delay) }
    private var networkMonitor: CodefluenceNetworkMonitor? = context?.let { SystemNetworkMonitor(it) }
    private val monitorStarted = AtomicBoolean(false)
    private val retryScheduled = AtomicBoolean(false)

    /** Test-only injection of the A3 retry dependencies. Not part of the host API. */
    internal fun injectRetryDependencies(
        policy: CodefluenceBackoffPolicy? = null,
        jitter: (() -> Double)? = null,
        scheduler: ((Long, () -> Unit) -> Unit)? = null,
        monitor: CodefluenceNetworkMonitor? = null
    ) {
        policy?.let { backoffPolicy = it }
        jitter?.let { jitterProvider = it }
        scheduler?.let { this.scheduler = it }
        monitor?.let { networkMonitor = it; monitorStarted.set(false) }
    }

    private fun startNetworkMonitorIfNeeded() {
        if (monitorStarted.compareAndSet(false, true)) {
            networkMonitor?.start { resumePendingConfirmationIfNeeded() }
        }
    }

    /**
     * Automatic catch-up/send of an accepted, unfinished confirmation. No-op
     * unless an accepted pending request exists and the attempt cap allows it.
     */
    fun resumePendingConfirmationIfNeeded() {
        val token = preferences.getString("pendingToken", null)
        val appUserId = preferences.getString("pendingAppUserId", null)
        val completedAttempts = preferences.getInt("pendingAttempts", 0)
        if (preferences.getBoolean("accepted", false) &&
            !preferences.contains("confirmedLeadId") &&
            !token.isNullOrBlank() && !appUserId.isNullOrBlank() &&
            backoffPolicy.canAttempt(completedAttempts)
        ) {
            confirm(token, appUserId, automatic = true, onResult = {})
        }
    }

    fun restoreReferralToken(installReferrer: String? = null): String? {
        if (preferences.getBoolean("noTokenAtLaunch", false)) return preferences.getString("laterLinkToken", null)
        preferences.getString("clickToken", null)?.takeIf { it.isNotBlank() }?.let { return it }
        val referrerToken = installReferrer
            ?.let { value ->
                val query = if (value.contains('=')) value else "cf_click_token=$value"
                Uri.parse("https://codefluence.invalid/?$query").getQueryParameter("cf_click_token")
                    ?: Uri.parse("https://codefluence.invalid/?$query").getQueryParameter("codefluence_click_token")
            }
            ?.takeIf(::looksLikeSignedToken)
        if (referrerToken != null) {
            preferences.edit().putString("clickToken", referrerToken).apply()
            return referrerToken
        }
        val clipboard = context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val pasted = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.let(::validReferralUrl) ?: return null
        preferences.edit().putString("clickToken", pasted).apply()
        return pasted
    }

    /** Accepts only an https://<configured-host>/t/<token> URL. */
    fun handleOpenURL(uri: Uri): String? {
        val token = referralTokenFrom(uri.toString()) ?: return null
        decisions.acceptLink(token)
        return token
    }

    // ---- One-call helper -------------------------------------------------------------------

    private val appUserIdProvider = AtomicReference<(() -> String?)?>(null)

    /**
     * Wires the SDK to the host's customer ID and the app lifecycle; used by [start].
     * - The deep link may arrive before or after this, and before the host knows its customer ID:
     *   the token is kept and confirmed as soon as [appUserId] returns a value.
     * - A launch without any link does not record "no attribution" until [launchGraceMs] passed,
     *   so a cold-start deep link is never lost to that race.
     */
    internal fun begin(
        appUserId: () -> String?,
        launchGraceMs: Long = 1_000L,
        schedule: ((Long, () -> Unit) -> Unit)? = null
    ) {
        appUserIdProvider.set(appUserId)
        heartbeat() // 하루 한 번. 대시보드의 「SDK 신호」가 첫 실행에 바로 뜬다.
        val run = schedule ?: { delay, work -> mainHandler?.postDelayed(work, delay) ?: work() }
        run(launchGraceMs) { resume() }
    }

    /**
     * Hand every incoming link (the launch Intent's data and `onNewIntent`) to this. Returns true when it was
     * a referral link for this app; the token is saved and confirmed once the customer ID is known.
     */
    fun handle(uri: Uri?): Boolean = uri != null && handle(uri.toString())
    fun handle(intent: android.content.Intent?): Boolean = handle(intent?.data)

    fun handle(url: String): Boolean {
        val token = referralTokenFrom(url) ?: return false
        decisions.acceptLink(token)
        // 이미 소개로 확인된 설치가 다른 링크를 다시 열었다: 첫 소개자는 그대로 두고, 서버에 "이 링크를 열었다"만 알린다.
        // 소모성(일회성) 재결제를 결제 직전에 연 링크의 크리에이터에게 귀속하는 근거다.
        if (preferences.contains("confirmedLeadId") && token != preferences.getString("clickToken", null)) reportReferralTouch(token)
        resume()
        return true
    }

    /**
     * 확인된 설치가 다른 크리에이터의 링크를 다시 열었음을 `/track/touch`로 알린다. 서버가 받아들였거나 영구 오류로 거절한 링크는 다시 보내지 않고,
     * 일시 오류·연결 실패는 같은 링크를 다시 열 때 다시 시도한다. 응답은 기록 여부만 알려 주며 앱 동작에는 영향이 없다.
     */
    private fun reportReferralTouch(token: String) {
        val appUserId = preferences.getString("confirmedAppUserId", null) ?: return
        val installToken = preferences.getString("purchaseAccountToken", null) ?: return
        val reported = runCatching { org.json.JSONArray(preferences.getString("touchedTokens", "[]")).let { array -> (0 until array.length()).map { array.getString(it) } } }.getOrDefault(emptyList())
        if (token in reported) return
        workExecutor {
            val status = runCatching {
                transport.send("$apiHost/track/touch", mapOf("Authorization" to "Bearer $publicKey", "Content-Type" to "application/json"),
                    JSONObject().put("clickToken", token).put("appUserId", appUserId).put("purchaseAccountToken", installToken).put("build", build).toString()).status
            }.getOrNull()
            if (status != null && (status in 200..299 || status in setOf(400, 401, 403, 404, 409, 422))) {
                preferences.edit().putString("touchedTokens", org.json.JSONArray((reported + token).takeLast(20)).toString()).apply()
            }
        }
    }

    /**
     * Confirms a saved, installed-from or copied referral and reports purchases, once the customer ID is known.
     * Safe to call any time: while the provider returns null it does nothing and never records "no attribution"
     * for that reason. Called automatically whenever an Activity starts after [start].
     */
    fun resume() {
        val id = appUserIdProvider.get()?.invoke()?.takeIf { it.isNotBlank() } ?: return
        autoConfirmIfAvailable(id) { reportIfReferred(); if (preferences.contains("confirmedLeadId")) sendActivitySignalIfNeeded() }
    }

    /**
     * Call after a purchase succeeds, with any purchase SDK. Reports the Play purchase when this install came
     * from a referral; installs without one send nothing. A confirmation still in flight reports by itself when
     * it finishes, so this never decides "no attribution".
     */
    fun purchaseCompleted() = reportIfReferred()

    private fun reportIfReferred() {
        if (preferences.contains("confirmedLeadId")) reportPurchases()
    }

    private fun referralTokenFrom(value: String): String? = runCatching {
        val uri = java.net.URI(value)
        val token = if (uri.scheme.equals("https", true)) {
            require(uri.host.equals(java.net.URI(apiHost).host, true) && uri.rawQuery == null && uri.rawFragment == null)
            val segments = uri.rawPath.split('/').filter { it.isNotEmpty() }
            require(segments.size == 2 && segments[0] == "t")
            segments[1]
        } else {
            require(uri.host == "referral")
            uri.rawQuery?.split('&')?.map { it.split('=', limit = 2) }
                ?.firstOrNull { it[0] == "cf_click_token" || it[0] == "codefluence_click_token" }?.getOrNull(1)
        }
        token?.takeIf(::looksLikeSignedToken)
    }.getOrNull()

    /** Returns this install's stable UUID; pass it as obfuscatedAccountId when launching the purchase flow. */
    fun purchaseAccountToken(): String {
        return preferences.getString("purchaseAccountToken", null) ?: UUID.randomUUID().toString().lowercase(Locale.ROOT).also {
            preferences.edit().putString("purchaseAccountToken", it).apply()
        }
    }

    /** Reports a verified Google Play purchase token; price and state are read server-side. */
    fun reportPurchase(purchase: Purchase, isSubscription: Boolean = false, onResult: (Result<Unit>) -> Unit = {}) {
        if (!publicKey.startsWith("pk_")) { onResult(Result.failure(CodefluenceError.NotConfigured)); return }
        workExecutor { val result = runCatching {
            val connection = (URL("$apiHost/track/purchase").openConnection() as HttpURLConnection).apply { connectTimeout = 5_000; readTimeout = 5_000; requestMethod = "POST"; doOutput = true; setRequestProperty("Authorization", "Bearer $publicKey"); setRequestProperty("Content-Type", "application/json") }
            val body = JSONObject().apply { put("purchaseToken", purchase.purchaseToken); put("productId", purchase.products.firstOrNull() ?: error("products is empty")); purchase.obfuscatedAccountId?.let { put("appUserId", it) }; put("kind", if (isSubscription) "subscription" else "product"); put("build", build) }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            if (connection.responseCode !in 200..299) error("Codefluence purchase report failed: ${connection.responseCode}")
            connection.disconnect(); Unit
        }
        mainExecutor { onResult(result) } }
    }

    /**
     * Reports every Google Play purchase this install hasn't reported yet, with the confirmed
     * lead and install token. Call it from the purchase-success callback (including RevenueCat);
     * it also runs on launch and right after a referral is confirmed. The server verifies each
     * purchase with Google before linking it. Result is the number the server accepted.
     */
    fun reportPurchases(onResult: (Result<Int>) -> Unit = {}) {
        if (!publicKey.startsWith("pk_")) { mainExecutor { onResult(Result.failure(CodefluenceError.NotConfigured)) }; return }
        val source = purchaseSource ?: run { mainExecutor { onResult(Result.success(0)) }; return }
        // One report at a time; overlapping calls (launch, confirm, purchase callback) return 0.
        if (!reportInFlight.compareAndSet(false, true)) { mainExecutor { onResult(Result.success(0)) }; return }
        // Play Billing can fail to call back (service busy or unavailable). Without a deadline the in-flight
        // flag would never clear and every later report would silently return 0.
        val finished = AtomicBoolean(false)
        val finish: (Result<Int>) -> Unit = { result ->
            if (finished.compareAndSet(false, true)) { reportInFlight.set(false); mainExecutor { onResult(result) } }
        }
        scheduler(PURCHASE_LOOKUP_TIMEOUT_MS) { finish(Result.failure(CodefluenceError.RequestFailed("Google Play purchase lookup timed out"))) }
        // A Billing API mismatch must never crash the host app; report nothing instead.
        val started = runCatching { source.ownedPurchases { owned -> workExecutor {
            if (finished.get()) return@workExecutor
            val reported = reportedPurchaseTokens()
            android.util.Log.i("Codefluence", "purchase lookup returned ${owned.size} purchase(s), ${owned.count { it.purchaseToken in reported }} already reported")
            val leadId = preferences.getString("confirmedLeadId", null)
            val token = leadId?.let { preferences.getString("confirmedPurchaseAccountToken", null) ?: purchaseAccountToken() }
            val accepted = owned.filter { it.purchaseToken !in reported }.filter { purchase ->
                val body = JSONObject().apply {
                    put("purchaseToken", purchase.purchaseToken); put("productId", purchase.productId)
                    put("kind", if (purchase.subscription) "subscription" else "product")
                    leadId?.let { put("leadId", it) }; token?.let { put("purchaseAccountToken", it) }
                    put("build", build)
                }
                runCatching { transport.send("$apiHost/track/purchase", mapOf("Authorization" to "Bearer $publicKey", "Content-Type" to "application/json"), body.toString()).status.also { android.util.Log.i("Codefluence", "purchase report answered HTTP $it") } in 200..299 }.onFailure { android.util.Log.w("Codefluence", "purchase report request failed: ${it.javaClass.simpleName}") }.getOrDefault(false)
            }.map { it.purchaseToken }
            if (accepted.isNotEmpty()) preferences.edit().putString("reportedPurchaseTokens", org.json.JSONArray((reported + accepted).takeLast(200)).toString()).apply()
            finish(Result.success(accepted.size))
        } } }
        if (started.isFailure) finish(Result.success(0))
    }

    private val reportInFlight = AtomicBoolean(false)
    private val PURCHASE_LOOKUP_TIMEOUT_MS = 20_000L

    private fun reportedPurchaseTokens(): List<String> = runCatching {
        val array = org.json.JSONArray(preferences.getString("reportedPurchaseTokens", "[]"))
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())

    interface AttestationProvider { fun token(onToken: (String?) -> Unit) }
    /** Sends one aggregate heartbeat per local day; integrity verification is injected. */
    fun heartbeat(appVersion: String = hostAppVersion(), sdkVersion: String = VERSION, attestation: AttestationProvider? = null, onResult: (Result<Unit>) -> Unit = {}) {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date())
        val key = "heartbeat.$day"
        if (preferences.getBoolean(key, false)) { onResult(Result.success(Unit)); return }
        val send: (String?) -> Unit = { assertion -> workExecutor {
            val result = runCatching {
                val status = postHeartbeat(appVersion, sdkVersion, assertion)
                if (status !in 200..299) error("Codefluence heartbeat failed: $status")
                preferences.edit().putBoolean(key, true).apply(); Unit
            }
            mainExecutor { onResult(result) } } }
        if (attestation == null) send(null) else attestation.token(send)
    }

    /** 서버가 받는 계약 그대로 보냅니다: `platform`은 필수이고, 증명이 없으면 `attestation` 키를 뺍니다. */
    private fun postHeartbeat(appVersion: String, sdkVersion: String, attestation: String?): Int {
        val body = JSONObject().put("platform", "android").put("appVersion", appVersion).put("sdkVersion", sdkVersion).put("build", build)
        if (attestation != null) body.put("attestation", attestation)
        val headers = mapOf("Authorization" to "Bearer $publicKey", "Content-Type" to "application/json")
        return transport.send("$apiHost/sdk/heartbeat", headers, body.toString()).status
    }

    private fun hostAppVersion(): String = context?.let { host ->
        runCatching { host.packageManager.getPackageInfo(host.packageName, 0).versionName }.getOrNull()
    } ?: "unknown"

    /** 앱 URL 스킴(`scheme://referral`)을 받는 Activity가 이 앱에 등록돼 있는지. 테스트가 바꿉니다. */
    internal var schemeRegistered: (String) -> Boolean = { scheme ->
        context?.let { host ->
            runCatching {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("$scheme://referral")).addCategory(android.content.Intent.CATEGORY_BROWSABLE)
                host.packageManager.queryIntentActivities(intent, 0).any { it.activityInfo?.packageName == host.packageName }
            }.getOrDefault(false)
        } ?: false
    }

    /**
     * 출시 전 설치 점검. 키 모양, 서버 접속과 키 유효성(heartbeat를 실제로 보내므로 대시보드에 첫 신호가 바로 뜹니다),
     * 선택적으로 앱 URL 스킴 등록을 확인하고 항목별 통과·실패와 고칠 방법을 돌려줍니다. 결과는 Logcat에도 `Codefluence` 태그로 남깁니다.
     * 디버그 빌드에서 부르세요. 토큰·키 값은 어디에도 출력하지 않습니다.
     */
    fun validate(urlScheme: String? = null, onResult: (List<CodefluenceCheck>) -> Unit) {
        val checks = mutableListOf<CodefluenceCheck>()
        val configured = publicKey.startsWith("pk_") && publicKey.length > 3
        checks += when {
            configured -> CodefluenceCheck("설정", true, "pk_ 공개 키와 서버 주소가 설정돼 있어요")
            publicKey.startsWith("sk_") -> CodefluenceCheck("설정", false, "비밀 키(sk_)가 앱에 들어 있어요. 앱에는 pk_ 공개 키만 넣고 sk_ 키는 폐기하세요")
            else -> CodefluenceCheck("설정", false, "pk_ 공개 키와 서버 주소(https://…)를 start 또는 configure에 넣으세요. BuildConfig 값이 비어 있지 않은지 확인하세요")
        }
        val finish: () -> Unit = {
            checks += buildCheck(build)
            if (urlScheme != null) {
                val registered = schemeRegistered(urlScheme)
                checks += CodefluenceCheck("URL 스킴", registered, if (registered) "$urlScheme:// 스킴이 앱에 등록돼 있어요"
                    else "AndroidManifest의 Activity에 scheme $urlScheme, host referral인 intent-filter(VIEW·DEFAULT·BROWSABLE)를 등록하세요. 대시보드 제품 화면의 「앱 열기 스킴」에도 같은 값을 저장해야 소개 링크가 앱을 열어요")
            }
            checks.forEach { android.util.Log.i("Codefluence", "${if (it.passed) "✓" else "✗"} ${it.name}: ${it.hint}") }
            mainExecutor { onResult(checks.toList()) }
        }
        if (!configured) { finish(); return }
        workExecutor {
            val server = runCatching { postHeartbeat(hostAppVersion(), VERSION, null) }.fold(
                onSuccess = { status -> when (status) {
                    in 200..299 -> CodefluenceCheck("서버와 키", true, "서버가 키를 받아들였고 첫 신호가 도착했어요")
                    401, 403 -> CodefluenceCheck("서버와 키", false, "서버가 키를 거절했어요. pk_ 공개 키가 이 제품의 키가 맞는지, 폐기되지 않았는지 대시보드 「키와 결제 알림」에서 확인하세요")
                    422 -> CodefluenceCheck("서버와 키", false, "키의 제품 플랫폼이 Android가 아니에요. Android 제품의 pk_ 키를 쓰세요(iOS 제품 키와 섞이지 않았는지 확인)")
                    else -> CodefluenceCheck("서버와 키", false, "서버가 ${status}을 돌려줬어요. 잠시 뒤 다시 시도하고 계속되면 서버 주소를 확인하세요")
                } },
                onFailure = { CodefluenceCheck("서버와 키", false, "서버에 연결하지 못했어요. 서버 주소(${runCatching { java.net.URI(apiHost).host }.getOrNull() ?: "?"})와 기기의 네트워크, 로컬 서버라면 cleartext 설정을 확인하세요") }
            )
            checks += server
            finish()
        }
    }

    /** 설치 점검의 「빌드」 항목. 개발 빌드의 신호는 서버가 운영 집계·보상에서 빼므로 그 사실을 알려 준다(통과·실패가 아니라 안내). */
    internal fun buildCheck(build: String): CodefluenceCheck = if (build == "debug")
        CodefluenceCheck("빌드", true, "개발 빌드(디버그 가능 빌드)로 표시돼요. 이 빌드의 신호·확인·결제는 대시보드에 「개발 빌드에서 확인됨」으로만 보이고 운영 집계와 보상에는 들어가지 않아요")
    else CodefluenceCheck("빌드", true, "출시 빌드로 표시돼요. 이 빌드의 신호는 운영 집계에 들어가요")

    private fun looksLikeSignedToken(value: String): Boolean = value.matches(Regex("^[^./?#]+\\.[^./?#]+\\.[0-9]+\\.[A-Za-z0-9_-]+$"))

    private fun validReferralUrl(value: String): String? = runCatching {
        val uri = Uri.parse(value)
        require(uri.scheme.equals("https", true) && uri.host.equals(Uri.parse(apiHost).host, true) && uri.query == null && uri.fragment == null)
        val segments = uri.pathSegments
        require(segments.size == 2 && segments[0] == "t")
        val token = segments[1]
        require(looksLikeSignedToken(token))
        token
    }.getOrNull()

    /**
     * Restores a referral token and attributes it without presenting a dialog.
     * A missing token is a normal unattributed start. Use this when following a
     * verified referral link itself is the product's attribution decision.
     */
    fun autoConfirmIfAvailable(appUserId: String, installReferrer: String? = null, onResult: (Result<String?>) -> Unit) {
        mainExecutor { if (appUserId.isBlank()) {
            fail("A stable customer ID is required", onResult)
            return@mainExecutor
        }
        if (preferences.getBoolean("decisionMade", false)) {
            val savedCustomer = preferences.getString("confirmedAppUserId", null)
            val leadId = preferences.getString("confirmedLeadId", null)
            if (savedCustomer == null || savedCustomer == appUserId) {
                mainExecutor { onResult(Result.success(leadId)) }
            } else {
                fail("Referral attribution belongs to a different customer", onResult)
            }
            return@mainExecutor
        }
        if (!gate.beginPrompt()) {
            fail("Referral attribution is already being resolved", onResult)
            return@mainExecutor
        }
        try {
            resolveReferralToken(installReferrer) { token ->
                when (decisions.prepare(token)) {
                    ConfirmationPreparation.SKIP -> {
                        gate.endPrompt()
                        val savedCustomer = preferences.getString("confirmedAppUserId", null)
                        val leadId = preferences.getString("confirmedLeadId", null)
                        if (savedCustomer == null || savedCustomer == appUserId) onResult(Result.success(leadId))
                        else fail("Referral attribution belongs to a different customer", onResult)
                    }
                    ConfirmationPreparation.NO_ATTRIBUTION -> {
                        gate.endPrompt()
                        onResult(Result.success(null))
                    }
                    ConfirmationPreparation.PRESENT -> {
                        val staged = decisions.stage(requireNotNull(token), appUserId)
                        gate.endPrompt()
                        if (staged) confirm(requireNotNull(token), appUserId, onResult = onResult)
                        else fail("Referral attribution already has a different decision or identity", onResult)
                    }
                }
            }
        } catch (_: Exception) {
            gate.endPrompt()
            fail("Referral token restoration failed", onResult)
        } }
    }

    fun showConfirmationIfNeeded(appUserId: String, installReferrer: String? = null, onResult: (Result<String?>) -> Unit) {
        val activity = activityReference?.get()
        if (activity == null) {
            fail("A foreground Activity is required to show referral confirmation", onResult)
            return
        }
        showConfirmationIfNeeded(activity, appUserId, installReferrer, onResult)
    }

    fun showConfirmationIfNeeded(activity: Activity, appUserId: String, installReferrer: String? = null, onResult: (Result<String?>) -> Unit) {
        mainExecutor { if (activity.isFinishing || activity.isDestroyed) {
            fail("A foreground Activity is required to show referral confirmation", onResult)
            return@mainExecutor
        }
        if (preferences.getBoolean("decisionMade", false)) return@mainExecutor
        if (!gate.beginPrompt()) {
            fail("Referral confirmation is already being presented", onResult)
            return@mainExecutor
        }
        if (appUserId.isBlank()) {
            gate.endPrompt()
            fail("A stable customer ID is required", onResult)
            return@mainExecutor
        }
        try { resolveReferralToken(installReferrer) { token ->
            when (decisions.prepare(token)) {
                ConfirmationPreparation.SKIP -> {
                    gate.endPrompt()
                    return@resolveReferralToken
                }
                ConfirmationPreparation.NO_ATTRIBUTION -> {
                    gate.endPrompt()
                    onResult(Result.success(null))
                    return@resolveReferralToken
                }
                ConfirmationPreparation.PRESENT -> Unit
            }
            if (activity.isFinishing || activity.isDestroyed) {
                gate.endPrompt()
                fail("A foreground Activity is required to show referral confirmation", onResult)
                return@resolveReferralToken
            }
            val copy = ConfirmationCopy(Locale.getDefault())
            AlertDialog.Builder(activity)
                .setTitle(copy.title)
                .setMessage(copy.message)
                .setNegativeButton(copy.no) { _, _ ->
                    declineConfirmation(onResult)
                }
                .setPositiveButton(copy.yes) { _, _ ->
                    if (decisions.stage(requireNotNull(token), appUserId)) confirm(token, appUserId, onResult = onResult)
                    else fail("Referral confirmation already has a different decision or identity", onResult)
                }
                .setOnCancelListener { declineConfirmation(onResult) }
                .setOnDismissListener { gate.endPrompt() }
                .show()
        } } catch (_: Exception) {
            gate.endPrompt()
            fail("Referral token restoration or presentation failed", onResult)
        } }
    }

    /** Retry an accepted unfinished confirmation only; never reopens the dialog. */
    fun retryPendingConfirmation(onResult: (Result<String?>) -> Unit) {
        val token = preferences.getString("pendingToken", null)
        val appUserId = preferences.getString("pendingAppUserId", null)
        if (!preferences.getBoolean("accepted", false) || preferences.contains("confirmedLeadId") ||
            token.isNullOrBlank() || appUserId.isNullOrBlank()) {
            fail("No accepted pending confirmation is available", onResult)
            return
        }
        // Manual retry resets backoff attempts and any scheduled auto retry.
        preferences.edit().putInt("pendingAttempts", 0).apply()
        retryScheduled.set(false)
        confirm(token, appUserId, automatic = false, onResult = onResult)
    }

    /**
     * Reads the server's normalized subscription status for the confirmed
     * referral (C3). **Read-only view, not an entitlement decision** — do not
     * gate paid features on it; use your payment provider for access control.
     *
     * Uses the `leadId` and `purchaseAccountToken` saved from `/track/confirm`.
     * Fails with [CodefluenceError.NoSubscriptionReference] when no confirmed
     * referral is stored, and [CodefluenceError.SubscriptionNotFound] on a 404.
     */
    fun subscriptionStatus(onResult: (Result<CodefluenceSubscriptionStatus>) -> Unit) {
        val leadId = preferences.getString("confirmedLeadId", null)
        val purchaseAccountToken = preferences.getString("confirmedPurchaseAccountToken", null)
            ?: preferences.getString("purchaseAccountToken", null)
        if (leadId.isNullOrBlank() || purchaseAccountToken.isNullOrBlank()) {
            mainExecutor { onResult(Result.failure(CodefluenceError.NoSubscriptionReference)) }
            return
        }
        workExecutor {
            val headers = mapOf(
                "Authorization" to "Bearer $publicKey",
                "Content-Type" to "application/json"
            )
            val requestBody = JSONObject(mapOf("leadId" to leadId, "purchaseAccountToken" to purchaseAccountToken)).toString()
            val result: Result<CodefluenceSubscriptionStatus> = try {
                val response = transport.send("$apiHost/track/subscription", headers, requestBody)
                when {
                    response.status == 404 -> Result.failure(CodefluenceError.SubscriptionNotFound)
                    response.status in 200..299 -> {
                        val parsed = CodefluenceSubscriptionStatus.parse(JSONObject(response.body))
                            ?: throw IllegalStateException("Codefluence subscription response is invalid")
                        Result.success(parsed)
                    }
                    else -> throw IllegalStateException("Codefluence subscription read failed: ${response.status}")
                }
            } catch (notFound: CodefluenceError.SubscriptionNotFound) {
                Result.failure(notFound)
            } catch (error: Throwable) {
                Result.failure(CodefluenceError.RequestFailed(error.message ?: "Subscription status request failed"))
            }
            mainExecutor { onResult(result) }
        }
    }

    private fun declineConfirmation(onResult: (Result<String?>) -> Unit) {
        val saved = decisions.decline()
        if (saved) onResult(Result.success(null))
        else fail("Referral confirmation already has a decision", onResult)
    }

    private fun resolveReferralToken(installReferrer: String?, onToken: (String?) -> Unit) {
        if (preferences.getBoolean("noTokenAtLaunch", false)) { onToken(preferences.getString("laterLinkToken", null)); return }
        if (!preferences.getString("clickToken", null).isNullOrBlank() || !installReferrer.isNullOrBlank()) {
            onToken(restoreReferralToken(installReferrer))
            return
        }
        // The official client is bounded and always closed. Unavailable Play
        // services or a non-Play install fall back to the existing clipboard path.
        // This path is device-only; configure() always supplies a Context.
        val hostContext = context ?: run { onToken(restoreReferralToken(installReferrer)); return }
        val handler = mainHandler ?: run { onToken(restoreReferralToken(installReferrer)); return }
        val client = InstallReferrerClient.newBuilder(hostContext).build()
        val finished = AtomicBoolean(false)
        var timeout: Runnable? = null
        val finish: (String?) -> Unit = { value ->
            if (finished.compareAndSet(false, true)) {
                timeout?.let { handler.removeCallbacks(it) }
                try { client.endConnection() } catch (_: Exception) { }
                mainExecutor { onToken(restoreReferralToken(value)) }
            }
        }
        val expiry = Runnable { finish(null) }
        timeout = expiry
        handler.postDelayed(expiry, 2_000)
        try {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    val value = if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                        try { client.installReferrer.installReferrer } catch (_: Exception) { null }
                    } else null
                    finish(value)
                }
                override fun onInstallReferrerServiceDisconnected() { finish(null) }
            })
        } catch (_: Exception) { finish(null) }
    }

    private fun confirm(token: String, appUserId: String, automatic: Boolean = false, onResult: (Result<String?>) -> Unit) {
        if (!preferences.getBoolean("accepted", false) ||
            preferences.getString("pendingToken", null) != token ||
            preferences.getString("pendingAppUserId", null) != appUserId) {
            if (!automatic) fail("Retry must preserve the accepted token and customer ID", onResult)
            return
        }
        if (!gate.beginRequest()) {
            // Automatic resend never races a manual/in-flight request; stay silent.
            if (!automatic) fail("Referral confirmation is already in progress", onResult)
            return
        }
        val idempotencyKey = preferences.getString("pendingIdempotencyKey", null)
        preferences.edit().putInt("pendingAttempts", preferences.getInt("pendingAttempts", 0) + 1).apply()
        retryScheduled.set(false)
        workExecutor {
            var failureClass: ConfirmationFailureClass? = null
            val result: Result<String?> = try {
                val headers = buildMap {
                    put("Authorization", "Bearer $publicKey")
                    put("Content-Type", "application/json")
                    if (!idempotencyKey.isNullOrEmpty()) put("Idempotency-Key", idempotencyKey)
                }
                val body = JSONObject(mapOf("clickToken" to token, "appUserId" to appUserId))
                body.put("launchKind", preferences.getString("pendingLaunchKind", "first_launch"))
                body.put("purchaseAccountToken", purchaseAccountToken())
                body.put("build", build)
                val response = transport.send("$apiHost/track/confirm", headers, body.toString())
                if (response.status !in 200..299) {
                    failureClass = ConfirmationFailureClass.forStatus(response.status)
                    throw IllegalStateException("Codefluence confirm failed: ${response.status}")
                }
                val parsed = JSONObject(response.body)
                if (parsed.isNull("leadId") && parsed.optString("reason") == "existing_users_not_included") {
                    clearPendingQueue()
                    preferences.edit().putBoolean("noTokenAtLaunch", true).remove("decisionMade").remove("accepted")
                        .remove("laterLinkToken").remove("clickToken").apply()
                    Result.success(null)
                } else {
                    val leadId = parsed.optString("leadId").takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Codefluence confirm returned no valid lead ID")
                val editor = preferences.edit().putString("confirmedLeadId", leadId).putString("confirmedAppUserId", appUserId)
                    .remove("pendingToken").remove("pendingAppUserId")
                    .remove("pendingIdempotencyKey").remove("pendingLaunchKind").remove("pendingAttempts")
                parsed.optString("purchaseAccountToken").takeIf { it.isNotBlank() }?.let {
                    editor.putString("confirmedPurchaseAccountToken", it)
                }
                editor.apply()
                reportPurchases()
                Result.success(leadId)
                }
            } catch (error: Throwable) {
                // No HTTP status means a transport failure: retryable.
                if (failureClass == null) failureClass = ConfirmationFailureClass.RETRYABLE
                Result.failure(if (error.message == "Codefluence confirm failed: 409") CodefluenceError.ConfirmationRejected else CodefluenceError.RequestFailed(error.message ?: "Referral confirmation request failed"))
            } finally {
                gate.endRequest()
            }
            if (result.isFailure) {
                if (failureClass == ConfirmationFailureClass.PERMANENT) clearPendingQueue()
                else scheduleAutomaticRetry()
            }
            if (!automatic) mainExecutor { onResult(result) }
        }
    }

    private fun clearPendingQueue() {
        preferences.edit().remove("pendingToken").remove("pendingAppUserId")
            .remove("pendingIdempotencyKey").remove("pendingLaunchKind").remove("pendingAttempts").apply()
        retryScheduled.set(false)
    }

    private fun scheduleAutomaticRetry() {
        val token = preferences.getString("pendingToken", null)
        val appUserId = preferences.getString("pendingAppUserId", null)
        val completedAttempts = preferences.getInt("pendingAttempts", 0)
        if (retryScheduled.get() ||
            !preferences.getBoolean("accepted", false) ||
            preferences.contains("confirmedLeadId") ||
            token.isNullOrBlank() || appUserId.isNullOrBlank() ||
            !backoffPolicy.canAttempt(completedAttempts)
        ) return
        val delay = backoffPolicy.delayMillis(completedAttempts + 1, jitterProvider())
        retryScheduled.set(true)
        scheduler(delay) { confirm(token, appUserId, automatic = true, onResult = {}) }
    }

    private fun fail(message: String, onResult: (Result<String?>) -> Unit) {
        mainExecutor { onResult(Result.failure(errorFor(message))) }
    }

    private fun errorFor(message: String): CodefluenceError = when (message) {
        "A stable customer ID is required",
        "A foreground Activity is required to show referral confirmation" ->
            CodefluenceError.PresenterOrCustomerRequired(message)
        "Referral attribution belongs to a different customer" ->
            CodefluenceError.AttributionForDifferentCustomer
        "Referral attribution is already being resolved",
        "Referral confirmation is already being presented" ->
            CodefluenceError.AlreadyPresenting
        "Referral attribution already has a different decision or identity",
        "Referral confirmation already has a different decision or identity",
        "Retry must preserve the accepted token and customer ID" ->
            CodefluenceError.RetryIdentityMismatch
        "Referral token restoration failed",
        "Referral token restoration or presentation failed" ->
            CodefluenceError.RestorationFailed(message)
        "No accepted pending confirmation is available" ->
            CodefluenceError.NoPendingConfirmation
        "Referral confirmation already has a decision" ->
            CodefluenceError.Declined
        "Referral confirmation is already in progress" ->
            CodefluenceError.RequestInProgress
        "Codefluence confirm failed: 409" ->
            CodefluenceError.ConfirmationRejected
        else -> CodefluenceError.RequestFailed(message)
    }

    companion object {
        /** SDK 버전. heartbeat가 서버에 보내 앱 버전별 신호 표에 나타납니다. */
        const val VERSION = "0.2.1"
        private val gate = ConfirmationGate()
        fun configure(context: Context, publicKey: String, apiHost: String): Codefluence {
            val normalizedKey = publicKey.trim()
            require(normalizedKey.startsWith("pk_") && normalizedKey.length > 3) { "publicKey must be a pk_ public key" }
            val normalizedHost = apiHost.trim().trimEnd('/')
            val parsedHost = runCatching { java.net.URI(normalizedHost) }.getOrNull()
            require(parsedHost != null && parsedHost.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !parsedHost.host.isNullOrBlank() &&
                parsedHost.userInfo == null && parsedHost.query == null && parsedHost.fragment == null &&
                (parsedHost.path.isEmpty() || parsedHost.path == "/")) {
                "apiHost must be an explicit HTTP(S) URL with a host"
            }
            return Codefluence(context, normalizedKey, normalizedHost).also {
                // A3: on app relaunch, watch for connectivity and resume any
                // accepted-but-unfinished confirmation.
                it.startNetworkMonitorIfNeeded()
                it.resumePendingConfirmationIfNeeded()
                if (it.preferences.contains("confirmedLeadId")) { it.reportPurchases(); it.sendActivitySignalIfNeeded() }
                // Ad revenue forwarded earlier (paid events) is sent on launch too; the server gate decides whether anything leaves the device.
                it.flushAdRevenueIfNeeded()
            }
        }

        /**
         * Sets the SDK up in one call: [configure], customer-ID wiring, a launch grace period, and a
         * foreground hook that confirms/reports whenever an Activity starts. [appUserId] returns the host's
         * stable customer ID, or null while it is not known yet (for example before the purchase SDK is
         * configured); call [Codefluence.resume] once it is.
         */
        fun start(application: android.app.Application, publicKey: String, apiHost: String, appUserId: () -> String?): Codefluence {
            val sdk = configure(application, publicKey, apiHost)
            sdk.begin(appUserId)
            application.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) { sdk.resume() }
                override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            })
            return sdk
        }

        /**
         * Test-only constructor that injects storage/transport/executors so the
         * confirm (A3) and subscription (C3) flows can be exercised on the JVM
         * without an Android Context, Looper, network or device. Not a host API.
         */
        internal fun createForTest(
            publicKey: String,
            apiHost: String,
            preferences: SharedPreferences,
            transport: CodefluenceTransport,
            workExecutor: (() -> Unit) -> Unit = { it() },
            mainExecutor: (() -> Unit) -> Unit = { it() },
            purchaseSource: PurchaseSource? = null,
            integritySource: IntegrityTokenSource? = null,
            clock: (() -> Long)? = null,
            debuggable: Boolean? = null
        ): Codefluence = Codefluence(
            hostContext = null,
            publicKey = publicKey,
            apiHost = apiHost.trimEnd('/'),
            injectedPreferences = preferences,
            transport = transport,
            workExecutor = workExecutor,
            mainExecutor = mainExecutor,
            purchaseSource = purchaseSource,
            integritySource = integritySource,
            clock = clock,
            debuggable = debuggable
        )
    }
}
