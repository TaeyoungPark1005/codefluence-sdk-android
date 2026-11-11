package com.codefluence.sdk

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AdMob impression-level precision, mirrored from `AdValue.PrecisionType` so the SDK does not depend on the Google Mobile Ads SDK.
 * Documentation: https://developers.google.com/admob/android/impression-level-ad-revenue
 */
enum class CodefluenceAdValuePrecision { UNKNOWN, ESTIMATED, PUBLISHER_PROVIDED, PRECISE }

/** Result of one ad revenue flush. Never an exception: a failed report must not affect the host app. */
sealed class CodefluenceAdRevenueOutcome {
    /** The server accepted the batch. [attested] is true when it counts towards the referral share (Play Integrity verified). */
    data class Sent(val attested: Boolean) : CodefluenceAdRevenueOutcome()
    /** Nothing was sent. [reason] is a short machine-readable code. */
    data class Skipped(val reason: String) : CodefluenceAdRevenueOutcome()
}

/**
 * Ad revenue reports (SPEC §26.2). The host forwards AdMob paid events; the SDK adds them up on the device and sends one batch
 * at most once an hour. Gated by the server: until `GET /track/signal-config` answers `adRevenue: true` nothing leaves the
 * device. Only amounts, currency and counts are sent, never an advertising ID.
 */
internal class AdRevenueClient(
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
    private data class Batch(val currency: String, val micros: Long, val impressions: Int, val unknown: Int)
    private data class Config(val adRevenue: Boolean, val signals: Boolean, val project: Long?)

    fun report(valueMicros: Long, currencyCode: String, precision: CodefluenceAdValuePrecision) {
        val currency = currencyCode.uppercase(java.util.Locale.ROOT)
        if (valueMicros < 0 || currency !in CURRENCIES) return
        synchronized(this) {
            val pending = readPending()
            val entry = pending.optJSONArray(currency) ?: JSONArray().put(0).put(0).put(0)
            val counted = if (precision == CodefluenceAdValuePrecision.UNKNOWN) 0L else valueMicros
            entry.put(0, minOf(entry.optLong(0) + counted, MAX_BATCH_MICROS))
            if (precision == CodefluenceAdValuePrecision.UNKNOWN) entry.put(2, minOf(entry.optInt(2) + 1, MAX_BATCH_IMPRESSIONS)) else entry.put(1, minOf(entry.optInt(1) + 1, MAX_BATCH_IMPRESSIONS))
            pending.put(currency, entry)
            preferences.edit().putString(PENDING, pending.toString()).apply()
        }
        flushIfNeeded(null)
    }

    fun flushIfNeeded(onResult: ((CodefluenceAdRevenueOutcome) -> Unit)?) {
        if (!inFlight.compareAndSet(false, true)) { deliver(onResult, CodefluenceAdRevenueOutcome.Skipped("in_flight")); return }
        runOnWorker {
            val finish: (CodefluenceAdRevenueOutcome) -> Unit = { outcome -> inFlight.set(false); deliver(onResult, outcome) }
            try { perform(clock(), finish) } catch (error: Throwable) {
                android.util.Log.w("Codefluence", "ad revenue report failed: ${error.javaClass.simpleName}")
                finish(CodefluenceAdRevenueOutcome.Skipped("error"))
            }
        }
    }

    private fun deliver(onResult: ((CodefluenceAdRevenueOutcome) -> Unit)?, outcome: CodefluenceAdRevenueOutcome) { if (onResult != null) runOnMain { onResult(outcome) } }

    private fun readPending(): JSONObject = runCatching { JSONObject(preferences.getString(PENDING, "{}")) }.getOrDefault(JSONObject())

    private fun restore(batch: Batch) = synchronized(this) {
        val pending = readPending()
        val entry = pending.optJSONArray(batch.currency) ?: JSONArray().put(0).put(0).put(0)
        entry.put(0, minOf(entry.optLong(0) + batch.micros, MAX_BATCH_MICROS)); entry.put(1, minOf(entry.optInt(1) + batch.impressions, MAX_BATCH_IMPRESSIONS)); entry.put(2, minOf(entry.optInt(2) + batch.unknown, MAX_BATCH_IMPRESSIONS))
        pending.put(batch.currency, entry)
        preferences.edit().putString(PENDING, pending.toString()).apply()
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

    /** Only the public key is sent here: no user, install or device identifiers. */
    private fun config(now: Long): Config {
        val checkedAt = preferences.getLong(CHECKED_AT, 0L)
        if (preferences.contains(ADREV_ENABLED) && now - checkedAt in 0 until CAPABILITY_TTL_MS) {
            return Config(preferences.getBoolean(ADREV_ENABLED, false), preferences.getBoolean(SIGNALS_ENABLED, false), preferences.getString(CLOUD_PROJECT, null)?.toLongOrNull())
        }
        val response = runCatching { transport.get("$apiHost/track/signal-config", headers()) }.getOrNull() ?: return Config(false, false, null)
        if (response.status != 200) return Config(false, false, null)
        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: return Config(false, false, null)
        val adRevenue = json.optBoolean("adRevenue", false)
        val signals = json.optBoolean("enabled", false)
        val project = json.optString("playIntegrityCloudProjectNumber", "").takeIf { it.matches(Regex("^\\d{4,20}$")) }
        preferences.edit().putBoolean(ADREV_ENABLED, adRevenue).putBoolean(SIGNALS_ENABLED, signals).putLong(CHECKED_AT, now).putString(CLOUD_PROJECT, project).apply()
        return Config(adRevenue, signals, project?.toLongOrNull())
    }

    /** null = the server does not want a referral batch from this install (or it failed); the batch then goes in the unproven total. */
    private fun challenge(identity: Identity): String? {
        val (status, json) = runCatching { post("track/signal-challenge", JSONObject().put("leadId", identity.leadId).put("purchaseAccountToken", identity.token).put("purpose", "ad_revenue")) }.getOrNull() ?: return null
        if (status == 404) { preferences.edit().putString(STOPPED_LEAD, identity.leadId).apply(); return null }
        if (status !in 200..299) return null
        if (json.has("collect") && !json.optBoolean("collect", true)) { preferences.edit().putString(STOPPED_LEAD, identity.leadId).apply(); return null }
        return json.optString("challenge", "").takeIf { it.isNotEmpty() }
    }

    private fun perform(now: Long, finish: (CodefluenceAdRevenueOutcome) -> Unit) {
        val skip = { reason: String -> finish(CodefluenceAdRevenueOutcome.Skipped(reason)) }
        val pending = readPending()
        if (pending.length() == 0) return skip("nothing_pending")
        if (now < preferences.getLong(BACKOFF_UNTIL, 0L)) return skip("backoff")
        val lastFlush = preferences.getLong(LAST_FLUSH, 0L)
        if (lastFlush > 0 && now - lastFlush in 0 until FLUSH_INTERVAL_MS) return skip("interval")
        val config = config(now)
        if (!config.adRevenue) return skip("disabled")

        val currency = pending.keys().asSequence().sorted().first()
        val entry = pending.getJSONArray(currency)
        val batch = Batch(currency, entry.optLong(0), entry.optInt(1), entry.optInt(2))
        synchronized(this) {
            val latest = readPending(); latest.remove(currency)
            preferences.edit().apply { if (latest.length() == 0) remove(PENDING) else putString(PENDING, latest.toString()) }.putLong(LAST_FLUSH, now).apply()
        }
        val report = JSONObject().put("currency", batch.currency).put("valueMicros", batch.micros).put("impressions", batch.impressions).put("unknownImpressions", batch.unknown)

        // A referred install with Play Integrity sends a proven batch that counts towards the creator share.
        // Everything else (no referral, window closed, no integrity) goes in the unproven total used only as the denominator.
        val identity = identity()
        val source = integrity
        val project = config.project
        if (config.signals && project != null && source != null && identity != null && preferences.getString(STOPPED_LEAD, null) != identity.leadId) {
            val challenge = challenge(identity)
            if (challenge != null) {
                val clientData = challenge + "\n" + "${batch.currency},${batch.micros},${batch.impressions},${batch.unknown}"
                source.token(project, ActivitySignalClient.requestHash(clientData)) { token ->
                    runOnWorker {
                        try {
                            if (token == null) finish(unproven(report, batch, now)) else finish(proven(identity, challenge, token, report, batch, now))
                        } catch (error: Throwable) { restore(batch); skip("error") }
                    }
                }
                return
            }
        }
        finish(try { unproven(report, batch, now) } catch (error: Throwable) { restore(batch); CodefluenceAdRevenueOutcome.Skipped("error") })
    }

    private fun proven(identity: Identity, challenge: String, token: String, report: JSONObject, batch: Batch, now: Long): CodefluenceAdRevenueOutcome {
        val body = JSONObject().put("leadId", identity.leadId).put("purchaseAccountToken", identity.token).put("challenge", challenge).put("report", report)
            .put("proof", JSONObject().put("type", "play_integrity").put("token", token))
        val (status, json) = post("track/ad-revenue", body)
        if (status == 429) { preferences.edit().putLong(BACKOFF_UNTIL, now + FLUSH_INTERVAL_MS).apply(); restore(batch); return CodefluenceAdRevenueOutcome.Skipped("rate_limited") }
        if (status in 200..299 && json.optBoolean("accepted", false) && json.optBoolean("attested", false)) return CodefluenceAdRevenueOutcome.Sent(true)
        // Proof rejected or the server no longer wants a referral batch: the value was not stored, send it as part of the unproven total.
        if (status in 200..299 && (json.optBoolean("accepted", false) || (json.has("collect") && !json.optBoolean("collect", true)))) return unproven(report, batch, now)
        restore(batch)
        return CodefluenceAdRevenueOutcome.Skipped("rejected")
    }

    private fun unproven(report: JSONObject, batch: Batch, now: Long): CodefluenceAdRevenueOutcome {
        val (status, json) = post("track/ad-revenue", JSONObject().put("report", report))
        if (status == 429) { preferences.edit().putLong(BACKOFF_UNTIL, now + FLUSH_INTERVAL_MS).apply(); restore(batch); return CodefluenceAdRevenueOutcome.Skipped("rate_limited") }
        // 409: the server switched the feature off or the product is not an ad product. Drop the batch instead of retrying forever.
        if (status == 409) return CodefluenceAdRevenueOutcome.Skipped("disabled")
        if (status !in 200..299 || !json.optBoolean("accepted", false)) { restore(batch); return CodefluenceAdRevenueOutcome.Skipped("rejected") }
        return CodefluenceAdRevenueOutcome.Sent(false)
    }

    companion object {
        const val PENDING = "adrev.pending"
        const val LAST_FLUSH = "adrev.lastFlushAt"
        const val STOPPED_LEAD = "adrev.stoppedLeadId"
        const val BACKOFF_UNTIL = "adrev.backoffUntil"
        const val CHECKED_AT = "adrev.checkedAt"
        const val ADREV_ENABLED = "adrev.enabled"
        const val SIGNALS_ENABLED = "adrev.signals"
        const val CLOUD_PROJECT = "adrev.cloudProject"
        const val CAPABILITY_TTL_MS = 6 * 3_600_000L
        /** The server accepts 24 batches per referred install per day. */
        const val FLUSH_INTERVAL_MS = 3_600_000L
        const val MAX_BATCH_MICROS = 1_000_000_000L
        const val MAX_BATCH_IMPRESSIONS = 100_000
        val CURRENCIES = setOf("KRW", "USD", "JPY", "EUR")
    }
}
