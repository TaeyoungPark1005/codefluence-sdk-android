package com.codefluence.sdk

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/** Result of one activity-signal attempt. Never an exception: a failed signal must not affect the host app. */
sealed class CodefluenceActivitySignalOutcome {
    /** The server accepted the signal. [attested] is whether the Play Integrity proof was verified. */
    data class Sent(val attested: Boolean) : CodefluenceActivitySignalOutcome()
    /** Nothing was sent. [reason] is a short machine-readable code. */
    data class Skipped(val reason: String) : CodefluenceActivitySignalOutcome()
}

/** Where the SDK gets a Play Integrity (Standard API) token. Tests inject a fake; the simulator-like cases return null. */
fun interface IntegrityTokenSource {
    /** Calls [onToken] once with the token, or null when Play Integrity is unavailable. [requestHash] binds the token to this request. */
    fun token(cloudProjectNumber: Long, requestHash: String, onToken: (String?) -> Unit)
}

/**
 * Play Integrity Standard API.
 * Docs: https://developer.android.com/google/play/integrity/standard
 * The token provider is prepared once per process and reused; each request carries its own request hash.
 */
internal class PlayIntegrityTokenSource(context: Context) : IntegrityTokenSource {
    private val manager = com.google.android.play.core.integrity.IntegrityManagerFactory.createStandard(context.applicationContext)
    @Volatile private var provider: com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider? = null
    @Volatile private var providerProject: Long = 0

    override fun token(cloudProjectNumber: Long, requestHash: String, onToken: (String?) -> Unit) {
        val once = OnceResult(onToken)
        fun request(ready: com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenProvider) {
            ready.request(com.google.android.play.core.integrity.StandardIntegrityManager.StandardIntegrityTokenRequest.builder().setRequestHash(requestHash).build())
                .addOnSuccessListener { once.deliver(it.token()) }
                .addOnFailureListener { android.util.Log.w("Codefluence", "Play Integrity request failed: ${it.javaClass.simpleName}"); once.deliver(null) }
        }
        val cached = provider
        if (cached != null && providerProject == cloudProjectNumber) { request(cached); return }
        manager.prepareIntegrityToken(com.google.android.play.core.integrity.StandardIntegrityManager.PrepareIntegrityTokenRequest.builder().setCloudProjectNumber(cloudProjectNumber).build())
            .addOnSuccessListener { provider = it; providerProject = cloudProjectNumber; request(it) }
            .addOnFailureListener { android.util.Log.w("Codefluence", "Play Integrity setup failed: ${it.javaClass.simpleName}"); once.deliver(null) }
    }

    companion object {
        fun createIfAvailable(context: Context?): IntegrityTokenSource? = context?.let {
            runCatching { PlayIntegrityTokenSource(it) }.getOrNull()
        }
    }
}

/**
 * Daily activity signal and milestones (SPEC §29). Gated by the server: until `GET /track/signal-config` answers
 * `enabled: true` the SDK sends no identifiers and no proofs. Runs [perform] on [runOnWorker]; callbacks are
 * delivered with [runOnMain]. All network calls are blocking and must not run on the main thread.
 */
internal class ActivitySignalClient(
    private val preferences: SharedPreferences,
    private val transport: CodefluenceTransport,
    private val publicKey: String,
    private val apiHost: String,
    private val integrity: IntegrityTokenSource?,
    private val clock: () -> Long,
    private val runOnWorker: (() -> Unit) -> Unit,
    private val runOnMain: (() -> Unit) -> Unit
) {
    private val inFlight = AtomicBoolean(false)

    private data class Identity(val leadId: String, val token: String)

    fun trackMilestone(name: String, onResult: ((CodefluenceActivitySignalOutcome) -> Unit)?) {
        if (!validMilestone(name)) { deliver(onResult, CodefluenceActivitySignalOutcome.Skipped("invalid_milestone")); return }
        synchronized(this) {
            val pending = readList(PENDING); val sent = readList(SENT)
            if (name !in pending && name !in sent && pending.size < MAX_PENDING) preferences.edit().putString(PENDING, JSONArray(pending + name).toString()).apply()
        }
        sendIfNeeded(onResult)
    }

    fun sendIfNeeded(onResult: ((CodefluenceActivitySignalOutcome) -> Unit)?) {
        if (!inFlight.compareAndSet(false, true)) { deliver(onResult, CodefluenceActivitySignalOutcome.Skipped("in_flight")); return }
        runOnWorker {
            val finish: (CodefluenceActivitySignalOutcome) -> Unit = { outcome -> inFlight.set(false); deliver(onResult, outcome) }
            try { perform(clock(), finish) } catch (error: Throwable) {
                android.util.Log.w("Codefluence", "activity signal failed: ${error.javaClass.simpleName}")
                finish(CodefluenceActivitySignalOutcome.Skipped("error"))
            }
        }
    }

    private fun deliver(onResult: ((CodefluenceActivitySignalOutcome) -> Unit)?, outcome: CodefluenceActivitySignalOutcome) {
        if (onResult != null) runOnMain { onResult(outcome) }
    }

    private fun identity(): Identity? {
        val leadId = preferences.getString("confirmedLeadId", null)?.takeIf { it.isNotBlank() } ?: return null
        val token = (preferences.getString("confirmedPurchaseAccountToken", null) ?: preferences.getString("purchaseAccountToken", null))?.takeIf { it.isNotBlank() } ?: return null
        return Identity(leadId, token)
    }

    private fun headers() = mapOf("Authorization" to "Bearer $publicKey", "Content-Type" to "application/json")

    private fun post(path: String, body: JSONObject): Pair<Int, JSONObject> {
        val response = transport.send("$apiHost/$path", headers(), body.toString())
        return response.status to (runCatching { JSONObject(response.body) }.getOrNull() ?: JSONObject())
    }

    private fun stop(leadId: String) { preferences.edit().putString(STOPPED_LEAD, leadId).apply() }

    private fun serverState(now: Long): Pair<Boolean, Long?> {
        val checkedAt = preferences.getLong(CHECKED_AT, 0L)
        val cached = if (preferences.contains(ENABLED)) preferences.getBoolean(ENABLED, false) else null
        if (cached != null && now - checkedAt in 0 until CAPABILITY_TTL_MS) {
            return cached to preferences.getString(CLOUD_PROJECT, null)?.toLongOrNull()
        }
        // Only the public key is sent here: no user, install or device identifiers.
        val response = runCatching { transport.get("$apiHost/track/signal-config", headers()) }.getOrNull() ?: return false to null
        if (response.status != 200) return false to null
        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: return false to null
        val enabled = json.optBoolean("enabled", false)
        val project = json.optString("playIntegrityCloudProjectNumber", "").takeIf { it.matches(Regex("^\\d{4,20}$")) }
        preferences.edit().putBoolean(ENABLED, enabled).putLong(CHECKED_AT, now).putString(CLOUD_PROJECT, project).apply()
        return enabled to project?.toLongOrNull()
    }

    /** null = the server does not want signals for this install. */
    private fun challenge(identity: Identity, now: Long): String? {
        val (status, json) = post("track/signal-challenge", JSONObject().put("leadId", identity.leadId).put("purchaseAccountToken", identity.token).put("purpose", "signal"))
        // 409: the feature was switched off meanwhile, so ask again later instead of stopping for good.
        if (status == 409) { preferences.edit().putBoolean(ENABLED, false).putLong(CHECKED_AT, now).apply(); return null }
        if (status == 404) { stop(identity.leadId); return null }
        if (status !in 200..299) throw IllegalStateException("challenge failed")
        if (json.has("collect") && !json.optBoolean("collect", true)) { stop(identity.leadId); return null }
        return json.optString("challenge", "").takeIf { it.isNotEmpty() } ?: throw IllegalStateException("challenge missing")
    }

    private fun perform(now: Long, finish: (CodefluenceActivitySignalOutcome) -> Unit) {
        val skip = { reason: String -> finish(CodefluenceActivitySignalOutcome.Skipped(reason)) }
        val identity = identity() ?: return skip("not_confirmed")
        if (preferences.getString(STOPPED_LEAD, null) == identity.leadId) return skip("stopped")
        val today = kstDay(now)
        val pending = readList(PENDING); val sent = readList(SENT).toSet()
        val milestones = (pending.toSet() - sent).sorted().take(5)
        if (preferences.getString(LAST_DAY, null) == today && milestones.isEmpty()) return skip("already_sent_today")
        val attempts = if (preferences.getString(ATTEMPTS_DAY, null) == today) preferences.getInt(ATTEMPTS, 0) else 0
        if (attempts >= MAX_ATTEMPTS_PER_DAY) return skip("attempt_limit")
        val source = integrity ?: return skip("attestation_unsupported")
        val (enabled, project) = serverState(now)
        if (!enabled || project == null) return skip("disabled")

        preferences.edit().putString(ATTEMPTS_DAY, today).putInt(ATTEMPTS, attempts + 1).apply()
        val challenge = challenge(identity, now) ?: return skip("not_collecting")
        val clientData = challenge + "\n" + milestones.joinToString(",")
        source.token(project, requestHash(clientData)) { token ->
            if (token == null) { skip("integrity_unavailable"); return@token }
            runOnWorker {
                try { finish(submit(identity, challenge, token, milestones, pending, sent, today)) } catch (error: Throwable) {
                    android.util.Log.w("Codefluence", "activity signal failed: ${error.javaClass.simpleName}")
                    skip("error")
                }
            }
        }
    }

    private fun submit(identity: Identity, challenge: String, token: String, milestones: List<String>, pending: List<String>, sent: Set<String>, today: String): CodefluenceActivitySignalOutcome {
        val body = JSONObject().put("leadId", identity.leadId).put("purchaseAccountToken", identity.token).put("challenge", challenge)
            .put("milestones", JSONArray(milestones)).put("proof", JSONObject().put("type", "play_integrity").put("token", token))
        val (status, json) = post("track/signal", body)
        if (status == 429) { preferences.edit().putInt(ATTEMPTS, MAX_ATTEMPTS_PER_DAY).apply(); return CodefluenceActivitySignalOutcome.Skipped("rate_limited") }
        if (status !in 200..299 || !json.optBoolean("accepted", false)) return CodefluenceActivitySignalOutcome.Skipped("rejected")
        val attested = json.optBoolean("attested", false)
        if (attested) {
            preferences.edit().putString(LAST_DAY, today)
                .putString(SENT, JSONArray((sent + milestones).sorted()).toString())
                .putString(PENDING, JSONArray(pending.filter { it !in milestones }).toString()).apply()
        }
        return CodefluenceActivitySignalOutcome.Sent(attested)
    }

    private fun readList(key: String): List<String> = runCatching {
        val array = JSONArray(preferences.getString(key, "[]")); List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())

    companion object {
        const val ENABLED = "signal.enabled"
        const val CHECKED_AT = "signal.checkedAt"
        const val CLOUD_PROJECT = "signal.cloudProject"
        const val STOPPED_LEAD = "signal.stoppedLeadId"
        const val LAST_DAY = "signal.lastDay"
        const val SENT = "signal.sentMilestones"
        const val PENDING = "signal.pendingMilestones"
        const val ATTEMPTS_DAY = "signal.attemptsDay"
        const val ATTEMPTS = "signal.attempts"
        /** How long a capability answer is reused. A switched-off feature is re-checked rarely. */
        const val CAPABILITY_TTL_MS = 6 * 3_600_000L
        const val MAX_ATTEMPTS_PER_DAY = 3
        const val MAX_PENDING = 10

        fun validMilestone(name: String): Boolean = name.toByteArray(Charsets.UTF_8).size in 1..40 && name.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it == '_' || it == '.' || it == '-' }

        fun kstDay(millis: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }.format(java.util.Date(millis))

        /** base64url(SHA-256(UTF-8(clientData))) without padding. The server builds the same value. */
        fun requestHash(clientData: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(clientData.toByteArray(Charsets.UTF_8))
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val out = StringBuilder()
            var index = 0
            while (index < digest.size) {
                val remaining = digest.size - index
                val b0 = digest[index].toInt() and 0xff
                val b1 = if (remaining > 1) digest[index + 1].toInt() and 0xff else 0
                val b2 = if (remaining > 2) digest[index + 2].toInt() and 0xff else 0
                out.append(alphabet[b0 shr 2]).append(alphabet[((b0 and 3) shl 4) or (b1 shr 4)])
                if (remaining > 1) out.append(alphabet[((b1 and 15) shl 2) or (b2 shr 6)])
                if (remaining > 2) out.append(alphabet[b2 and 63])
                index += 3
            }
            return out.toString()
        }
    }
}
