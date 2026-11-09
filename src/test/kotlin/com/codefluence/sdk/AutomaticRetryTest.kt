package com.codefluence.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A3: automatic retry. Policy-level cases plus the behavioral cases equivalent
 * to the iOS `AutomaticRetryTests` (relaunch resume, network recovery, transient
 * backoff with a reused Idempotency-Key, permanent failure clears the queue,
 * terminal states are not resent, the attempt cap stops resends, and a manual
 * retry resets the backoff attempts). Transport/scheduler/monitor/executors are
 * injected so the flow runs on the JVM with no device, Looper or network.
 */
class AutomaticRetryTest {
    private val policy = CodefluenceBackoffPolicy(baseMillis = 2_000, factor = 2.0, capMillis = 3_600_000, maxAttempts = 5)

    private fun newSdk(
        preferences: TestPreferences,
        transport: ScriptedTransport,
        scheduler: ManualScheduler = ManualScheduler(),
        monitor: TriggerableMonitor = TriggerableMonitor()
    ): Codefluence {
        val sdk = Codefluence.createForTest("pk_fixture", "https://sdk-test.invalid", preferences, transport)
        sdk.injectRetryDependencies(policy = policy, jitter = { 0.5 }, scheduler = scheduler.schedule, monitor = monitor)
        return sdk
    }

    private fun acceptedPreferences(idempotencyKey: String, attempts: Int): TestPreferences = TestPreferences(
        mapOf(
            "decisionMade" to true,
            "accepted" to true,
            "pendingToken" to "signed.token",
            "pendingAppUserId" to "customer",
            "pendingIdempotencyKey" to idempotencyKey,
            "pendingAttempts" to attempts
        )
    )

    // --- Policy-level cases (kept from the initial A3 work) ---

    @Test fun backoffCeilingRespectsBaseFactorAndCap() {
        assertEquals(0L, policy.ceilingMillis(1))
        assertEquals(2_000L, policy.ceilingMillis(2))
        assertEquals(4_000L, policy.ceilingMillis(3))
        assertEquals(8_000L, policy.ceilingMillis(4))
        assertEquals(3_600_000L, policy.ceilingMillis(40))
    }

    @Test fun backoffJitterStaysWithinZeroAndCeiling() {
        var jitter = 0.0
        while (jitter < 1.0) {
            val delay = policy.delayMillis(4, jitter)
            assertTrue(delay in 0..policy.ceilingMillis(4))
            jitter += 0.07
        }
    }

    @Test fun maxAttemptsCapGatesAutomaticResend() {
        val capped = CodefluenceBackoffPolicy(maxAttempts = 5)
        assertTrue(capped.canAttempt(4))
        assertFalse(capped.canAttempt(5))
        assertFalse(capped.canAttempt(6))
    }

    @Test fun failureClassificationMatchesServerContract() {
        for (status in listOf(400, 401, 403, 404, 409, 422)) {
            assertEquals("$status", ConfirmationFailureClass.PERMANENT, ConfirmationFailureClass.forStatus(status))
        }
        for (status in listOf(408, 429, 500, 502, 503, 504)) {
            assertEquals("$status", ConfirmationFailureClass.RETRYABLE, ConfirmationFailureClass.forStatus(status))
        }
    }

    @Test fun stageGeneratesIdempotencyKeyAndZeroAttemptsReusedAcrossRetries() {
        val preferences = TestPreferences()
        val decisions = ConfirmationDecisions(preferences)
        assertTrue(decisions.stage("accepted.token", "customer"))
        val key = preferences.getString("pendingIdempotencyKey", null)
        assertNotNull(key)
        assertEquals(0, preferences.getInt("pendingAttempts", -1))
        assertTrue(decisions.stage("accepted.token", "customer"))
        assertEquals(key, preferences.getString("pendingIdempotencyKey", null))
    }

    @Test fun declinedDoesNotCreateAnIdempotencyKey() {
        val preferences = TestPreferences()
        val decisions = ConfirmationDecisions(preferences)
        assertTrue(decisions.decline())
        assertFalse(preferences.contains("pendingIdempotencyKey"))
    }

    // --- Behavioral cases (iOS-equivalent) ---

    @Test fun transientFailureSchedulesBackoffAndReusesSameIdempotencyKey() {
        val preferences = acceptedPreferences("persisted-key", attempts = 0)
        val transport = ScriptedTransport()
        transport.reply = { n -> if (n == 1) CodefluenceHttpResponse(503, "{}") else CodefluenceHttpResponse(200, "{\"leadId\":\"lead-fixture\"}") }
        val scheduler = ManualScheduler()
        val sdk = newSdk(preferences, transport, scheduler)

        var firstResult: Result<String?>? = null
        sdk.retryPendingConfirmation { firstResult = it }
        // First attempt failed with 503; a backoff retry was scheduled.
        assertTrue(firstResult!!.isFailure)
        // Manual retry reset attempts to 0, so the scheduled attempt is the 1st (ceiling 0)
        // after the completed one; the completed count is 1 → next ceiling is attempt 2 = 2000 * 0.5.
        assertEquals(1, scheduler.scheduledCount)
        assertEquals(1_000L, scheduler.recordedDelays.last())
        scheduler.runNext()
        assertEquals(2, transport.requestCount)
        assertNotNull(transport.captured[0].idempotencyKey)
        assertEquals(transport.captured[0].idempotencyKey, transport.captured[1].idempotencyKey)
        assertEquals(transport.captured[0].body, transport.captured[1].body)
        assertEquals("lead-fixture", preferences.getString("confirmedLeadId", null))
    }

    @Test fun permanentFailureClearsQueueAndDoesNotRetry() {
        val preferences = acceptedPreferences("permanent-key", attempts = 0)
        val transport = ScriptedTransport()
        transport.reply = { CodefluenceHttpResponse(422, "{\"error\":\"invalid\"}") }
        val scheduler = ManualScheduler()
        val sdk = newSdk(preferences, transport, scheduler)

        sdk.retryPendingConfirmation { }
        assertEquals(0, scheduler.scheduledCount)
        assertNull(preferences.getString("pendingToken", null))
        assertNull(preferences.getString("pendingIdempotencyKey", null))
        // A later resume finds no pending queue and sends nothing more.
        sdk.resumePendingConfirmationIfNeeded()
        assertEquals(1, transport.requestCount)
    }

    @Test fun relaunchResumesAcceptedPendingConfirmation() {
        val preferences = acceptedPreferences("persisted-key", attempts = 1)
        val transport = ScriptedTransport()
        val sdk = newSdk(preferences, transport)
        sdk.resumePendingConfirmationIfNeeded()
        assertEquals(1, transport.requestCount)
        assertEquals("persisted-key", transport.captured.single().idempotencyKey)
        assertEquals("lead-fixture", preferences.getString("confirmedLeadId", null))
    }

    @Test fun networkRecoveryTriggersResend() {
        val preferences = acceptedPreferences("recovery-key", attempts = 2)
        val transport = ScriptedTransport()
        val monitor = TriggerableMonitor()
        val sdk = newSdk(preferences, transport, monitor = monitor)
        // The monitor was injected and must be started; mirror configure()'s start.
        monitor.start { sdk.resumePendingConfirmationIfNeeded() }
        monitor.fireAvailable()
        assertEquals(1, transport.requestCount)
        assertEquals("recovery-key", transport.captured.single().idempotencyKey)
    }

    @Test fun declinedCompletedAndNoAttributionAreNotResent() {
        val transport = ScriptedTransport()

        // Declined.
        newSdk(TestPreferences(mapOf("decisionMade" to true, "accepted" to false)), transport)
            .resumePendingConfirmationIfNeeded()
        // Completed.
        newSdk(
            TestPreferences(
                mapOf(
                    "accepted" to true,
                    "confirmedLeadId" to "lead-done",
                    "pendingToken" to "signed.token",
                    "pendingAppUserId" to "customer"
                )
            ),
            transport
        ).resumePendingConfirmationIfNeeded()
        // No-attribution.
        newSdk(TestPreferences(mapOf("decisionMade" to true, "accepted" to false)), transport)
            .resumePendingConfirmationIfNeeded()

        assertEquals(0, transport.requestCount)
    }

    @Test fun maxAttemptsCapStopsAutomaticResend() {
        // attempts == maxAttempts (5) → no further automatic resend.
        val preferences = acceptedPreferences("capped-key", attempts = 5)
        val transport = ScriptedTransport()
        val sdk = newSdk(preferences, transport)
        sdk.resumePendingConfirmationIfNeeded()
        assertEquals(0, transport.requestCount)
    }

    @Test fun manualRetryResetsBackoffAttempts() {
        val preferences = acceptedPreferences("manual-key", attempts = 4)
        val transport = ScriptedTransport()
        transport.reply = { CodefluenceHttpResponse(200, "{\"leadId\":\"lead-fixture\"}") }
        val sdk = newSdk(preferences, transport)
        var result: Result<String?>? = null
        sdk.retryPendingConfirmation { result = it }
        assertEquals("lead-fixture", result!!.getOrNull())
        assertEquals("manual-key", transport.captured.single().idempotencyKey)
    }
}
