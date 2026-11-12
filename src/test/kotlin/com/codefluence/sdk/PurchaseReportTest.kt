package com.codefluence.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SDK reads the app's Google Play purchases itself (any purchase SDK, e.g. RevenueCat) and
 * reports each new one with the confirmed lead and install token. The server verifies the
 * purchase with Google before linking it. Plan: docs/superpowers/plans/2026-11-05-store-read-attribution-and-device-test.md Task 5
 */
class PurchaseReportTest {
    private val owned = listOf(OwnedPurchase("token-1", "pro", false), OwnedPurchase("token-2", "monthly", true))
    private fun sdk(preferences: TestPreferences, transport: CodefluenceTransport, purchases: List<OwnedPurchase> = owned) =
        Codefluence.createForTest("pk_fixture", "https://sdk-test.invalid", preferences, transport, purchaseSource = PurchaseSource { it(purchases) })

    private fun recording(status: Int = 200): Pair<MutableList<JSONObject>, CodefluenceTransport> {
        val bodies = mutableListOf<JSONObject>()
        return bodies to CodefluenceTransport { url, _, body ->
            if (url.endsWith("/track/purchase")) bodies.add(JSONObject(body))
            CodefluenceHttpResponse(status, "{}")
        }
    }

    @Test fun reportsLeadIdAndTokenWithPurchase() {
        val (bodies, transport) = recording()
        var count = -1
        sdk(TestPreferences(mapOf("confirmedLeadId" to "lead-1", "confirmedPurchaseAccountToken" to "11111111-1111-4111-8111-111111111111")), transport).reportPurchases { count = it.getOrThrow() }
        assertEquals(2, count)
        assertEquals("token-1", bodies[0].getString("purchaseToken"))
        assertEquals("release", bodies[0].getString("build"))
        assertEquals("pro", bodies[0].getString("productId"))
        assertEquals("product", bodies[0].getString("kind"))
        assertEquals("lead-1", bodies[0].getString("leadId"))
        assertEquals("11111111-1111-4111-8111-111111111111", bodies[0].getString("purchaseAccountToken"))
        assertEquals("subscription", bodies[1].getString("kind"))
    }

    @Test fun skipsAlreadyReportedTokens() {
        val (bodies, transport) = recording()
        val preferences = TestPreferences(mapOf("confirmedLeadId" to "lead-1"))
        val client = sdk(preferences, transport)
        client.reportPurchases {}
        client.reportPurchases {}
        assertEquals(2, bodies.size)
    }

    @Test fun rejectedReportIsRetriedLater() {
        val (bodies, transport) = recording(status = 422)
        val client = sdk(TestPreferences(mapOf("confirmedLeadId" to "lead-1")), transport)
        var count = -1
        client.reportPurchases { count = it.getOrThrow() }
        client.reportPurchases {}
        assertEquals(0, count)
        assertEquals(4, bodies.size)
    }

    @Test fun noLeadSendsWithoutLeadId() {
        val (bodies, transport) = recording()
        sdk(TestPreferences(), transport).reportPurchases {}
        assertEquals(2, bodies.size)
        assertFalse(bodies[0].has("leadId"))
        assertFalse(bodies[0].has("purchaseAccountToken"))
    }

    @Test fun billingApiMismatchDoesNotCrashTheHostApp() {
        val (bodies, transport) = recording()
        var count = -1
        Codefluence.createForTest("pk_fixture", "https://sdk-test.invalid", TestPreferences(mapOf("confirmedLeadId" to "lead-1")), transport,
            purchaseSource = PurchaseSource { throw NoSuchMethodError("enablePendingPurchases") })
            .reportPurchases { count = it.getOrThrow() }
        assertEquals(0, count)
        assertTrue(bodies.isEmpty())
    }

    @Test fun purchaseResultIsDeliveredOnce() {
        val delivered = mutableListOf<List<OwnedPurchase>>()
        val once = OnceResult<List<OwnedPurchase>> { delivered.add(it) }
        once.deliver(emptyList()); once.deliver(owned)
        assertEquals(listOf(emptyList<OwnedPurchase>()), delivered)
    }

    @Test fun overlappingReportsSendOnce() {
        val (bodies, transport) = recording()
        var pending: ((List<OwnedPurchase>) -> Unit)? = null
        val client = Codefluence.createForTest("pk_fixture", "https://sdk-test.invalid", TestPreferences(mapOf("confirmedLeadId" to "lead-1")), transport,
            purchaseSource = PurchaseSource { pending = it })
        val results = mutableListOf<Int>()
        client.reportPurchases { results.add(it.getOrThrow()) }
        client.reportPurchases { results.add(it.getOrThrow()) }
        pending!!(owned)
        assertEquals(2, bodies.size)
        assertEquals(listOf(0, 2), results)
    }

    /** Play Billing가 응답하지 않아도 보고가 영영 막히지 않는다(2026-11-06 실기기: 이후 보고가 모두 조용히 0건이 됐다). */
    @Test fun aHungPurchaseLookupTimesOutAndDoesNotBlockLaterReports() {
        val (bodies, transport) = recording()
        val callbacks = mutableListOf<(List<OwnedPurchase>) -> Unit>()
        val client = Codefluence.createForTest("pk_fixture", "https://sdk-test.invalid", TestPreferences(mapOf("confirmedLeadId" to "lead-1")), transport,
            purchaseSource = PurchaseSource { callbacks.add(it) })
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        client.injectRetryDependencies(policy = CodefluenceBackoffPolicy(), jitter = { 0.5 }, scheduler = { delay, work -> timers.add(delay to work) }, monitor = null)
        val results = mutableListOf<Result<Int>>()
        client.reportPurchases { results.add(it) }
        assertEquals(1, callbacks.size)
        val (delay, expire) = timers.last { it.first >= 10_000L }
        assertTrue(delay in 10_000L..60_000L)
        expire()
        assertTrue(results.single().isFailure)
        assertTrue(results.single().exceptionOrNull()!!.message!!.contains("timed out"))
        // the callback that finally arrives is ignored and the next report starts a fresh lookup
        callbacks[0](owned)
        assertTrue(bodies.isEmpty())
        client.reportPurchases { results.add(it) }
        assertEquals(2, callbacks.size)
        callbacks[1](owned)
        assertEquals(2, bodies.size)
        assertEquals(2, results.last().getOrThrow())
    }

    @Test fun notConfiguredFailsWithoutRequests() {
        val (bodies, transport) = recording()
        var failed = false
        Codefluence.createForTest("sk_live_wrong", "https://sdk-test.invalid", TestPreferences(), transport, purchaseSource = PurchaseSource { it(owned) })
            .reportPurchases { failed = it.isFailure }
        assertTrue(failed)
        assertTrue(bodies.isEmpty())
    }
}
