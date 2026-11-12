package com.codefluence.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Ad revenue report (SPEC §26.2) on the JVM: transport, Play Integrity and the clock are injected. */
class AdRevenueTest {
    private class QueueTransport : CodefluenceTransport {
        data class Recorded(val method: String, val path: String, val body: JSONObject?, val authorization: String?)
        val requests = mutableListOf<Recorded>()
        val responses = mutableMapOf<String, ArrayDeque<CodefluenceHttpResponse>>()
        fun answer(path: String, vararg answers: CodefluenceHttpResponse) { responses[path] = ArrayDeque(answers.toList()) }
        private fun respond(method: String, url: String, headers: Map<String, String>, body: String?): CodefluenceHttpResponse {
            val path = java.net.URI(url).path
            requests.add(Recorded(method, path, body?.let { JSONObject(it) }, headers["Authorization"]))
            val queue = responses[path] ?: return CodefluenceHttpResponse(404, "{}")
            return if (queue.size > 1) queue.removeFirst() else queue.first()
        }
        override fun send(url: String, headers: Map<String, String>, body: String) = respond("POST", url, headers, body)
        override fun get(url: String, headers: Map<String, String>) = respond("GET", url, headers, null)
        fun paths() = requests.map { it.path }
    }

    private class FakeIntegrity(var token: String? = "play-integrity-token-aaaaaaaa") : IntegrityTokenSource {
        val calls = mutableListOf<Pair<Long, String>>()
        override fun token(cloudProjectNumber: Long, requestHash: String, onToken: (String?) -> Unit) { calls.add(cloudProjectNumber to requestHash); onToken(token) }
    }

    private val now = 1_790_040_000_000L
    private var clockMillis = now
    private lateinit var preferences: TestPreferences
    private lateinit var transport: QueueTransport
    private lateinit var integrity: FakeIntegrity

    private fun sdk(source: IntegrityTokenSource? = integrity) = Codefluence.createForTest("pk_fixture", "https://sdk-test.invalid", preferences, transport, integritySource = source, clock = { clockMillis })
    private fun ok(body: String) = CodefluenceHttpResponse(200, body)

    @Before fun setUp() {
        clockMillis = now
        preferences = TestPreferences()
        transport = QueueTransport()
        integrity = FakeIntegrity()
        transport.answer("/track/signal-config", ok("""{"enabled":true,"platform":"android","adRevenue":true,"playIntegrityCloudProjectNumber":"123456789012"}"""))
        transport.answer("/track/signal-challenge", ok("""{"collect":true,"challenge":"challenge-abc"}"""))
        transport.answer("/track/ad-revenue", ok("""{"accepted":true,"attested":true}"""))
    }

    private fun confirm() { preferences.edit().putString("confirmedLeadId", "lead-1").putString("confirmedPurchaseAccountToken", "token-1").apply() }
    private fun buffer(currency: String, micros: Long, impressions: Int, unknown: Int = 0) {
        preferences.edit().putString(AdRevenueClient.PENDING, JSONObject().put(currency, org.json.JSONArray().put(micros).put(impressions).put(unknown)).toString()).apply()
    }
    private fun flush(source: IntegrityTokenSource? = integrity): CodefluenceAdRevenueOutcome? {
        var outcome: CodefluenceAdRevenueOutcome? = null
        sdk(source).flushAdRevenueIfNeeded { outcome = it }
        return outcome
    }

    @Test fun sendsNothingWhenTheServerHasAdRevenueOff() {
        transport.answer("/track/signal-config", ok("""{"enabled":true,"platform":"android"}"""))
        buffer("USD", 1_500_000, 2)
        assertEquals(CodefluenceAdRevenueOutcome.Skipped("disabled"), flush())
        assertEquals(listOf("/track/signal-config"), transport.paths())
        val config = transport.requests[0]
        assertEquals("GET", config.method); assertNull(config.body); assertEquals("Bearer pk_fixture", config.authorization)
        assertTrue(integrity.calls.isEmpty())
        // The batch stays on the device and a disabled answer is cached.
        assertTrue(preferences.getString(AdRevenueClient.PENDING, null)!!.contains("USD"))
        clockMillis += 60_000
        flush()
        assertEquals(listOf("/track/signal-config"), transport.paths())
    }

    @Test fun nothingPendingMeansNoRequest() {
        assertEquals(CodefluenceAdRevenueOutcome.Skipped("nothing_pending"), flush())
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun anInstallWithoutAReferralSendsTheUnprovenTotalWithoutAnyIdentifier() {
        buffer("USD", 1_500_000, 2, 1)
        assertEquals(CodefluenceAdRevenueOutcome.Sent(false), flush())
        assertEquals(listOf("/track/signal-config", "/track/ad-revenue"), transport.paths())
        val body = transport.requests[1].body!!
        assertEquals(setOf("report", "build"), body.keys().asSequence().toSet())
        assertEquals("release", body.getString("build"))
        val report = body.getJSONObject("report")
        assertEquals("USD", report.getString("currency")); assertEquals(1_500_000L, report.getLong("valueMicros"))
        assertEquals(2, report.getInt("impressions")); assertEquals(1, report.getInt("unknownImpressions"))
        assertTrue(integrity.calls.isEmpty())
        assertNull(preferences.getString(AdRevenueClient.PENDING, null))
    }

    @Test fun aReferredInstallSendsAProvenBatchBoundToTheExactValues() {
        confirm(); buffer("USD", 1_500_000, 2, 1)
        assertEquals(CodefluenceAdRevenueOutcome.Sent(true), flush())
        // The integrity request hash covers `challenge\ncurrency,micros,impressions,unknown`, the string the server rebuilds.
        assertEquals(123456789012L to ActivitySignalClient.requestHash("challenge-abc\nUSD,1500000,2,1"), integrity.calls.single())
        val sent = transport.requests.last().body!!
        assertEquals("lead-1", sent.getString("leadId")); assertEquals("token-1", sent.getString("purchaseAccountToken")); assertEquals("release", sent.getString("build"))
        assertEquals("play_integrity", sent.getJSONObject("proof").getString("type"))
        assertEquals("ad_revenue", transport.requests.first { it.path == "/track/signal-challenge" }.body!!.getString("purpose"))
    }

    @Test fun aRejectedProofFallsBackToTheUnprovenTotalSoTheRevenueIsNotLost() {
        confirm(); buffer("USD", 2_000_000, 3)
        transport.answer("/track/ad-revenue", ok("""{"accepted":true,"attested":false,"reason":"device_not_trusted"}"""), ok("""{"accepted":true,"attested":false}"""))
        assertEquals(CodefluenceAdRevenueOutcome.Sent(false), flush())
        val sends = transport.requests.filter { it.path == "/track/ad-revenue" }
        assertEquals(2, sends.size)
        assertTrue(sends[0].body!!.has("leadId")); assertFalse(sends[1].body!!.has("leadId"))
    }

    @Test fun withoutPlayIntegrityTheBatchGoesInTheUnprovenTotal() {
        confirm(); buffer("USD", 500_000, 1)
        assertEquals(CodefluenceAdRevenueOutcome.Sent(false), flush(source = null))
        assertFalse(transport.requests.last().body!!.has("leadId"))
        integrity.token = null; buffer("USD", 500_000, 1); clockMillis += 3_700_000
        assertEquals(CodefluenceAdRevenueOutcome.Sent(false), flush())
        assertFalse(transport.requests.last().body!!.has("leadId"))
    }

    @Test fun stoppingTheReferralWindowDoesNotStopTheActivitySignal() {
        confirm(); buffer("USD", 500_000, 1)
        transport.answer("/track/signal-challenge", ok("""{"collect":false,"reason":"window_closed"}"""))
        assertEquals(CodefluenceAdRevenueOutcome.Sent(false), flush())
        assertEquals("lead-1", preferences.getString(AdRevenueClient.STOPPED_LEAD, null))
        assertNull(preferences.getString(ActivitySignalClient.STOPPED_LEAD, null))
    }

    @Test fun atMostOneBatchPerHour() {
        buffer("USD", 100_000, 1)
        assertEquals(CodefluenceAdRevenueOutcome.Sent(false), flush())
        buffer("USD", 100_000, 1); clockMillis += 600_000
        assertEquals(CodefluenceAdRevenueOutcome.Skipped("interval"), flush())
        clockMillis += 3_100_000
        assertEquals(CodefluenceAdRevenueOutcome.Sent(false), flush())
    }

    @Test fun aRateLimitedBatchIsKeptAndRetriedLater() {
        buffer("USD", 700_000, 2)
        transport.answer("/track/ad-revenue", CodefluenceHttpResponse(429, """{"error":"ad_revenue_rate_limited"}"""))
        assertEquals(CodefluenceAdRevenueOutcome.Skipped("rate_limited"), flush())
        val kept = JSONObject(preferences.getString(AdRevenueClient.PENDING, null)!!).getJSONArray("USD")
        assertEquals(700_000L, kept.getLong(0)); assertEquals(2, kept.getInt(1))
        clockMillis += 1_800_000
        assertEquals(CodefluenceAdRevenueOutcome.Skipped("backoff"), flush())
    }

    @Test fun reportsAreSummedValidatedAndUnknownPrecisionAddsNoRevenue() {
        // The feature is off on the server in this test, so the background flush only asks for the config.
        transport.answer("/track/signal-config", ok("""{"enabled":false,"platform":"android"}"""))
        val sdk = sdk()
        sdk.reportAdRevenue(5_000, "usd", CodefluenceAdValuePrecision.PRECISE)
        sdk.reportAdRevenue(1_000, "USD", CodefluenceAdValuePrecision.ESTIMATED)
        sdk.reportAdRevenue(500_000, "USD", CodefluenceAdValuePrecision.UNKNOWN)
        sdk.reportAdRevenue(1_000_000, "GBP", CodefluenceAdValuePrecision.PRECISE)
        sdk.reportAdRevenue(-1, "USD", CodefluenceAdValuePrecision.PRECISE)
        val pending = JSONObject(preferences.getString(AdRevenueClient.PENDING, null)!!).getJSONArray("USD")
        assertEquals(listOf(6_000L, 2L, 1L), listOf(pending.getLong(0), pending.getLong(1), pending.getLong(2)))
    }

    @Test fun aTransportWithoutGetFailsClosed() {
        buffer("USD", 1_000, 1)
        val noGet = CodefluenceTransport { _, _, _ -> CodefluenceHttpResponse(200, "{}") }
        var outcome: CodefluenceAdRevenueOutcome? = null
        Codefluence.createForTest("pk_fixture", "https://sdk-test.invalid", preferences, noGet, integritySource = integrity, clock = { clockMillis }).flushAdRevenueIfNeeded { outcome = it }
        assertEquals(CodefluenceAdRevenueOutcome.Skipped("disabled"), outcome)
    }
}
