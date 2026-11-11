package com.codefluence.sdk

import kotlin.math.min
import kotlin.math.pow

/**
 * Deterministic exponential backoff with full jitter and a hard cap (A3).
 *
 * [ceilingMillis] returns the un-jittered ceiling (ms) to wait before the given
 * 1-based attempt. Attempt 1 is immediate (0). [delayMillis] applies jitter in
 * `[0, ceiling]` from an injected value so tests can assert bounds.
 */
data class CodefluenceBackoffPolicy(
    val baseMillis: Long = 2_000,
    val factor: Double = 2.0,
    val capMillis: Long = 3_600_000,
    val maxAttempts: Int = 10
) {
    fun ceilingMillis(attempt: Int): Long {
        if (attempt <= 1) return 0
        val raw = baseMillis * factor.pow((attempt - 2).toDouble())
        return min(raw, capMillis.toDouble()).toLong()
    }

    fun delayMillis(attempt: Int, jitter: Double): Long {
        val bounded = jitter.coerceIn(0.0, 0.999999)
        return (ceilingMillis(attempt) * bounded).toLong()
    }

    fun canAttempt(completedAttempts: Int): Boolean = completedAttempts < maxAttempts
}

/** HTTP/transport classification for retry decisions (A3). */
enum class ConfirmationFailureClass {
    PERMANENT, // 400/401/403/404/409/422 — never retry.
    RETRYABLE; // 408/429/5xx/transport error — retry with backoff.

    companion object {
        fun forStatus(status: Int): ConfirmationFailureClass = when (status) {
            400, 401, 403, 404, 409, 422 -> PERMANENT
            408, 429 -> RETRYABLE
            in 500..599 -> RETRYABLE
            in 400..499 -> PERMANENT
            else -> RETRYABLE
        }
    }
}

/** Connectivity signal, injectable for tests (A3). Default uses ConnectivityManager. */
interface CodefluenceNetworkMonitor {
    /** Starts monitoring; [onAvailable] fires whenever a usable network appears. */
    fun start(onAvailable: () -> Unit)
    fun cancel()
}

/** One HTTP response from the SDK transport. */
data class CodefluenceHttpResponse(val status: Int, val body: String)

/**
 * HTTP transport seam (A3/C3). The default implementation uses
 * `HttpURLConnection`; JVM unit tests inject a fake so confirm/subscription
 * behavior (idempotency key, status classification, parsing) can be verified
 * without a device, network or Android runtime. Implementations throw on a
 * transport failure (no HTTP status), which the caller classifies as retryable.
 */
fun interface CodefluenceTransport {
    fun send(
        url: String,
        headers: Map<String, String>,
        body: String
    ): CodefluenceHttpResponse

    /** GET without a body. Transports that cannot do it throw, so the caller fails closed (nothing is sent). */
    fun get(url: String, headers: Map<String, String>): CodefluenceHttpResponse =
        throw java.io.IOException("GET is not supported by this transport")
}
