package com.codefluence.sdk

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Scriptable in-memory transport for A3/C3 JVM tests. Records every request and
 * returns a response (or throws to simulate a transport failure) based on the
 * 1-based attempt count, mirroring the iOS `RetryMockProtocol`.
 */
class ScriptedTransport : CodefluenceTransport {
    data class Captured(val url: String, val idempotencyKey: String?, val body: String)

    private val count = AtomicInteger(0)
    val captured = mutableListOf<Captured>()

    /** Given the 1-based attempt number, return a response or throw for transport failure. */
    var reply: (Int) -> CodefluenceHttpResponse = { CodefluenceHttpResponse(200, "{\"leadId\":\"lead-fixture\"}") }

    override fun send(url: String, headers: Map<String, String>, body: String): CodefluenceHttpResponse {
        val n = count.incrementAndGet()
        synchronized(captured) { captured.add(Captured(url, headers["Idempotency-Key"], body)) }
        return reply(n)
    }

    val requestCount: Int get() = synchronized(captured) { captured.size }

    companion object {
        /** A thrown transport error (no HTTP status) — classified as retryable. */
        fun transportFailure(): Nothing = throw IOException("simulated transport failure")
    }
}

/** Deterministic scheduler for A3 tests; runs scheduled work on demand. */
class ManualScheduler {
    private val pending = ArrayDeque<() -> Unit>()
    val recordedDelays = mutableListOf<Long>()

    val schedule: (Long, () -> Unit) -> Unit = { delay, work ->
        recordedDelays.add(delay)
        pending.addLast(work)
    }

    val scheduledCount: Int get() = pending.size

    fun runNext() {
        val work = if (pending.isEmpty()) null else pending.removeFirst()
        work?.invoke()
    }
}

/** Triggerable network monitor for A3 tests. */
class TriggerableMonitor : CodefluenceNetworkMonitor {
    private var handler: (() -> Unit)? = null
    override fun start(onAvailable: () -> Unit) { handler = onAvailable }
    override fun cancel() { handler = null }
    fun fireAvailable() { handler?.invoke() }
}
