package com.codefluence.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Daily activity signal (SPEC §29) on the JVM: transport, Play Integrity and the clock are injected. */
class ActivitySignalTest {
    private class SignalTransport : CodefluenceTransport {
        data class Recorded(val method: String, val path: String, val body: JSONObject?, val authorization: String?)
        val requests = mutableListOf<Recorded>()
        val responses = mutableMapOf<String, CodefluenceHttpResponse>()
        private fun answer(method: String, url: String, headers: Map<String, String>, body: String?): CodefluenceHttpResponse {
            val path = java.net.URI(url).path
            requests.add(Recorded(method, path, body?.let { JSONObject(it) }, headers["Authorization"]))
            return responses[path] ?: CodefluenceHttpResponse(404, "{}")
        }
        override fun send(url: String, headers: Map<String, String>, body: String) = answer("POST", url, headers, body)
        override fun get(url: String, headers: Map<String, String>) = answer("GET", url, headers, null)
        fun paths() = requests.map { it.path }
    }

    private class FakeIntegrity(var token: String? = "play-integrity-token-aaaaaaaa") : IntegrityTokenSource {
        val calls = mutableListOf<Pair<Long, String>>()
        override fun token(cloudProjectNumber: Long, requestHash: String, onToken: (String?) -> Unit) { calls.add(cloudProjectNumber to requestHash); onToken(token) }
    }

    private val now = 1_790_040_000_000L
    private var clockMillis = now
    private lateinit var preferences: TestPreferences
    private lateinit var transport: SignalTransport
    private lateinit var integrity: FakeIntegrity

    private fun sdk(source: IntegrityTokenSource? = integrity) = Codefluence.createForTest("pk_fixture", "https://sdk-test.invalid", preferences, transport, integritySource = source, clock = { clockMillis })

    @Before fun setUp() {
        clockMillis = now
        preferences = TestPreferences(mapOf("confirmedLeadId" to "lead-1", "confirmedPurchaseAccountToken" to "token-1"))
        transport = SignalTransport()
        integrity = FakeIntegrity()
        transport.responses["/track/signal-config"] = CodefluenceHttpResponse(200, """{"enabled":true,"platform":"android","playIntegrityCloudProjectNumber":"123456789012"}""")
        transport.responses["/track/signal-challenge"] = CodefluenceHttpResponse(200, """{"collect":true,"challenge":"challenge-abc"}""")
        transport.responses["/track/signal"] = CodefluenceHttpResponse(200, """{"accepted":true,"attested":true,"day":"2026-09-22"}""")
    }

    private fun send(source: IntegrityTokenSource? = integrity): CodefluenceActivitySignalOutcome? {
        var outcome: CodefluenceActivitySignalOutcome? = null
        sdk(source).sendActivitySignalIfNeeded { outcome = it }
        return outcome
    }

    @Test fun sendsNothingWhenTheServerFlagIsOff() {
        transport.responses["/track/signal-config"] = CodefluenceHttpResponse(200, """{"enabled":false,"platform":"android","reason":"disabled"}""")
        assertEquals(CodefluenceActivitySignalOutcome.Skipped("disabled"), send())
        assertEquals(listOf("/track/signal-config"), transport.paths())
        // Only the public key goes out: GET, no body, no lead or install identifiers.
        val config = transport.requests[0]
        assertEquals("GET", config.method); assertNull(config.body); assertEquals("Bearer pk_fixture", config.authorization)
        assertTrue(integrity.calls.isEmpty())
        // A disabled answer is cached, so the next launch within the TTL does not ask again.
        clockMillis += 60_000
        send()
        assertEquals(listOf("/track/signal-config"), transport.paths())
    }

    @Test fun failsClosedWithoutProjectNumberPlayIntegrityOrAConfirmedReferral() {
        transport.responses["/track/signal-config"] = CodefluenceHttpResponse(200, """{"enabled":true,"platform":"android"}""")
        assertEquals(CodefluenceActivitySignalOutcome.Skipped("disabled"), send())
        assertEquals(CodefluenceActivitySignalOutcome.Skipped("attestation_unsupported"), send(source = null))
        preferences.edit().remove("confirmedLeadId").apply()
        assertEquals(CodefluenceActivitySignalOutcome.Skipped("not_confirmed"), send())
        assertFalse(transport.paths().contains("/track/signal"))
    }

    @Test fun sendsOneProofBoundToTheChallengeAndOncePerKstDay() {
        assertEquals(CodefluenceActivitySignalOutcome.Sent(true), send())
        assertEquals(listOf("/track/signal-config", "/track/signal-challenge", "/track/signal"), transport.paths())
        // The request hash is base64url(SHA-256("challenge\n" + milestones)) and the project number comes from the server.
        assertEquals(123456789012L to ActivitySignalClient.requestHash("challenge-abc\n"), integrity.calls.single())
        val signal = transport.requests[2].body!!
        assertEquals("play_integrity", signal.getJSONObject("proof").getString("type"))
        assertEquals("play-integrity-token-aaaaaaaa", signal.getJSONObject("proof").getString("token"))
        assertEquals("lead-1", signal.getString("leadId")); assertEquals("token-1", signal.getString("purchaseAccountToken")); assertEquals("release", signal.getString("build"))
        assertEquals(0, signal.getJSONArray("milestones").length())
        clockMillis += 3_600_000
        assertEquals(CodefluenceActivitySignalOutcome.Skipped("already_sent_today"), send())
        // The next KST day sends again (capability answer is still cached for 6 hours only, so it is asked again).
        transport.requests.clear(); clockMillis += 86_400_000
        assertEquals(CodefluenceActivitySignalOutcome.Sent(true), send())
        assertEquals(listOf("/track/signal-config", "/track/signal-challenge", "/track/signal"), transport.paths())
    }

    @Test fun requestHashMatchesTheServerFormula() {
        // base64url(SHA-256("a")) without padding; the same value lib/play-integrity.ts playRequestHash("a") returns.
        assertEquals("ypeBEsobvcr6wjGzmiPcTaeG7_gUfE5yuYB3ha_uSLs", ActivitySignalClient.requestHash("a"))
        assertEquals(43, ActivitySignalClient.requestHash("challenge\nonboarded,second").length)
    }

    @Test fun milestonesAreValidatedSentOnceAndBoundIntoTheRequestHash() {
        val sdk = sdk()
        sdk.trackMilestone("bad name!"); sdk.trackMilestone("a".repeat(41))
        assertNull(preferences.getString(ActivitySignalClient.PENDING, null))
        assertTrue(transport.requests.isEmpty())
        sdk.trackMilestone("onboarded")
        assertEquals(listOf("onboarded"), transport.requests.last().body!!.getJSONArray("milestones").let { a -> List(a.length()) { a.getString(it) } })
        assertEquals(ActivitySignalClient.requestHash("challenge-abc\nonboarded"), integrity.calls.last().second)
        assertEquals("[\"onboarded\"]", preferences.getString(ActivitySignalClient.SENT, null))
        assertEquals("[]", preferences.getString(ActivitySignalClient.PENDING, null))
        // A new milestone on the same day allows exactly one more signal.
        var outcome: CodefluenceActivitySignalOutcome? = null
        sdk.trackMilestone("second") { outcome = it }
        assertEquals(CodefluenceActivitySignalOutcome.Sent(true), outcome)
        sdk.sendActivitySignalIfNeeded { outcome = it }
        assertEquals(CodefluenceActivitySignalOutcome.Skipped("already_sent_today"), outcome)
    }

    @Test fun anUnattestedAnswerKeepsMilestonesPendingAndDoesNotCountTheDay() {
        transport.responses["/track/signal"] = CodefluenceHttpResponse(200, """{"accepted":true,"attested":false,"day":"2026-09-22","reason":"device_not_trusted"}""")
        preferences.edit().putString(ActivitySignalClient.PENDING, "[\"onboarded\"]").apply()
        assertEquals(CodefluenceActivitySignalOutcome.Sent(false), send())
        assertNull(preferences.getString(ActivitySignalClient.LAST_DAY, null))
        assertEquals("[\"onboarded\"]", preferences.getString(ActivitySignalClient.PENDING, null))
    }

    @Test fun stopsForeverWhenTheServerDoesNotCollectForThisInstall() {
        transport.responses["/track/signal-challenge"] = CodefluenceHttpResponse(200, """{"collect":false,"reason":"window_closed"}""")
        assertEquals(CodefluenceActivitySignalOutcome.Skipped("not_collecting"), send())
        transport.requests.clear(); clockMillis += 86_400_000
        assertEquals(CodefluenceActivitySignalOutcome.Skipped("stopped"), send())
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun aFeatureSwitchedOffMeanwhileIsAskedAgainLaterNotStoppedForGood() {
        transport.responses["/track/signal-challenge"] = CodefluenceHttpResponse(409, """{"error":"signals_disabled"}""")
        assertEquals(CodefluenceActivitySignalOutcome.Skipped("not_collecting"), send())
        assertEquals(false, preferences.getBoolean(ActivitySignalClient.ENABLED, true))
        assertNull(preferences.getString(ActivitySignalClient.STOPPED_LEAD, null))
    }

    @Test fun attemptsPerDayAreCappedAndAnIntegrityFailureNeverThrows() {
        integrity.token = null
        repeat(ActivitySignalClient.MAX_ATTEMPTS_PER_DAY) { assertEquals(CodefluenceActivitySignalOutcome.Skipped("integrity_unavailable"), send()) }
        transport.requests.clear()
        assertEquals(CodefluenceActivitySignalOutcome.Skipped("attempt_limit"), send())
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun rateLimitAndServerErrorsAreSkippedNotThrown() {
        transport.responses["/track/signal"] = CodefluenceHttpResponse(429, """{"error":"signal_rate_limited"}""")
        assertEquals(CodefluenceActivitySignalOutcome.Skipped("rate_limited"), send())
        assertEquals(CodefluenceActivitySignalOutcome.Skipped("attempt_limit"), send())
        preferences.edit().putInt(ActivitySignalClient.ATTEMPTS, 0).apply()
        transport.responses["/track/signal"] = CodefluenceHttpResponse(500, "{}")
        assertEquals(CodefluenceActivitySignalOutcome.Skipped("rejected"), send())
    }

    @Test fun aTransportWithoutGetFailsClosed() {
        val noGet = CodefluenceTransport { _, _, _ -> CodefluenceHttpResponse(200, "{}") }
        var outcome: CodefluenceActivitySignalOutcome? = null
        Codefluence.createForTest("pk_fixture", "https://sdk-test.invalid", preferences, noGet, integritySource = integrity, clock = { clockMillis }).sendActivitySignalIfNeeded { outcome = it }
        assertEquals(CodefluenceActivitySignalOutcome.Skipped("disabled"), outcome)
        assertTrue(integrity.calls.isEmpty())
    }

    @Test fun milestoneNamesAndKstDay() {
        assertTrue(ActivitySignalClient.validMilestone("first_post-1.0"))
        assertFalse(ActivitySignalClient.validMilestone("")); assertFalse(ActivitySignalClient.validMilestone("한글")); assertFalse(ActivitySignalClient.validMilestone("a b"))
        assertEquals("2026-09-22", ActivitySignalClient.kstDay(now))
        assertEquals("0.2.2", Codefluence.VERSION)
    }
}
