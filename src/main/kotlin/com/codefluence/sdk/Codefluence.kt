package com.codefluence.sdk

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
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
    purchaseSource: PurchaseSource? = null
) {
    private val purchaseSource: PurchaseSource? = purchaseSource ?: PlayBillingPurchaseSource.createIfAvailable(hostContext)
    data class Purchase(val purchaseToken: String, val products: List<String>, val obfuscatedAccountId: String? = null)
    private val context = hostContext?.applicationContext
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
    fun handleOpenURL(uri: Uri): String? = (validReferralUrl(uri.toString()) ?: uri.takeIf { it.host == "referral" }?.getQueryParameter("cf_click_token")?.takeIf { it.isNotBlank() })?.also {
        preferences.edit().putString("clickToken", it).apply()
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
        if (!preferences.getBoolean("decisionMade", false)) preferences.edit().putString("clickToken", token).apply()
        resume()
        return true
    }

    /**
     * Confirms a saved, installed-from or copied referral and reports purchases, once the customer ID is known.
     * Safe to call any time: while the provider returns null it does nothing and never records "no attribution"
     * for that reason. Called automatically whenever an Activity starts after [start].
     */
    fun resume() {
        val id = appUserIdProvider.get()?.invoke()?.takeIf { it.isNotBlank() } ?: return
        autoConfirmIfAvailable(id) { reportIfReferred() }
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
            val body = JSONObject().apply { put("purchaseToken", purchase.purchaseToken); put("productId", purchase.products.firstOrNull() ?: error("products is empty")); purchase.obfuscatedAccountId?.let { put("appUserId", it) }; put("kind", if (isSubscription) "subscription" else "product") }
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
    fun heartbeat(appVersion: String = "unknown", sdkVersion: String = "1", attestation: AttestationProvider? = null, onResult: (Result<Unit>) -> Unit = {}) {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(java.util.Date())
        val key = "heartbeat.$day"
        if (preferences.getBoolean(key, false)) { onResult(Result.success(Unit)); return }
        val send: (String?) -> Unit = { assertion -> workExecutor { var connection: HttpURLConnection? = null
        val result = runCatching {
            val request = (URL("$apiHost/sdk/heartbeat").openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000; readTimeout = 5_000; requestMethod = "POST"; doOutput = true
                setRequestProperty("Authorization", "Bearer $publicKey"); setRequestProperty("Content-Type", "application/json")
            }; connection = request
            request.outputStream.use { it.write(JSONObject(mapOf("appVersion" to appVersion, "sdkVersion" to sdkVersion, "attestation" to assertion)).toString().toByteArray()) }
            if (request.responseCode !in 200..299) error("Codefluence heartbeat failed: ${request.responseCode}")
            preferences.edit().putBoolean(key, true).apply(); Unit
        }
        connection?.disconnect(); mainExecutor { onResult(result) } } }
        if (attestation == null) send(null) else attestation.token(send)
    }

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
                body.put("purchaseAccountToken", purchaseAccountToken())
                val response = transport.send("$apiHost/track/confirm", headers, body.toString())
                if (response.status !in 200..299) {
                    failureClass = ConfirmationFailureClass.forStatus(response.status)
                    throw IllegalStateException("Codefluence confirm failed: ${response.status}")
                }
                val parsed = JSONObject(response.body)
                val leadId = parsed.optString("leadId").takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Codefluence confirm returned no valid lead ID")
                val editor = preferences.edit().putString("confirmedLeadId", leadId).putString("confirmedAppUserId", appUserId)
                    .remove("pendingToken").remove("pendingAppUserId")
                    .remove("pendingIdempotencyKey").remove("pendingAttempts")
                parsed.optString("purchaseAccountToken").takeIf { it.isNotBlank() }?.let {
                    editor.putString("confirmedPurchaseAccountToken", it)
                }
                editor.apply()
                reportPurchases()
                Result.success(leadId)
            } catch (error: Throwable) {
                // No HTTP status means a transport failure: retryable.
                if (failureClass == null) failureClass = ConfirmationFailureClass.RETRYABLE
                Result.failure(CodefluenceError.RequestFailed(error.message ?: "Referral confirmation request failed"))
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
            .remove("pendingIdempotencyKey").remove("pendingAttempts").apply()
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
        else -> CodefluenceError.RequestFailed(message)
    }

    companion object {
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
                if (it.preferences.contains("confirmedLeadId")) it.reportPurchases()
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
            purchaseSource: PurchaseSource? = null
        ): Codefluence = Codefluence(
            hostContext = null,
            publicKey = publicKey,
            apiHost = apiHost.trimEnd('/'),
            injectedPreferences = preferences,
            transport = transport,
            workExecutor = workExecutor,
            mainExecutor = mainExecutor,
            purchaseSource = purchaseSource
        )
    }
}
