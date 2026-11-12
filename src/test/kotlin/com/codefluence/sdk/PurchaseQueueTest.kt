package com.codefluence.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 0.2.2 미전송 구매 영속 큐: 결제 직후 받은 구매 토큰을 기기에 저장하고 서버가 2xx로 받을 때까지 다시 보낸다. */
class PurchaseQueueTest {
    private val start = 1_800_000_000_000L
    private var now = start
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val install = "11111111-1111-4111-8111-111111111111"

    private class Recorder {
        val bodies = mutableListOf<JSONObject>()
        var status: Int? = 200
        val transport = CodefluenceTransport { url, _, body ->
            if (url.endsWith("/track/purchase")) bodies.add(JSONObject(body))
            if (url.endsWith("/track/confirm")) return@CodefluenceTransport CodefluenceHttpResponse(200, "{\"leadId\":\"lead-confirmed\"}")
            CodefluenceHttpResponse(status ?: throw java.io.IOException("offline"), "{}")
        }
    }

    private fun referredPreferences() = TestPreferences(mapOf("confirmedLeadId" to "lead-1", "confirmedPurchaseAccountToken" to install))

    private fun sdk(preferences: TestPreferences, recorder: Recorder, store: PurchaseQueueStore = InMemoryPurchaseQueueStore(),
                    owned: List<OwnedPurchase>? = null, monitor: CodefluenceNetworkMonitor? = null): Codefluence =
        Codefluence.createForTest("pk_fixture", "https://sdk-test.invalid", preferences, recorder.transport,
            purchaseSource = owned?.let { list -> PurchaseSource { it(list) } }, clock = { now }, purchaseQueueStore = store).also {
            it.injectRetryDependencies(jitter = { 0.5 }, scheduler = { delay, work -> timers.add(delay to work) }, monitor = monitor)
        }

    private fun complete(client: Codefluence, token: String, productId: String = "coins_100", subscription: Boolean = false): Int {
        var count = -1
        client.purchaseCompleted(token, productId, subscription, "GPA.1234") { count = it.getOrThrow() }
        return count
    }

    @Test fun directPurchaseIsSentWithTheSameFieldsAsTheStoreListReport() {
        val recorder = Recorder()
        val client = sdk(referredPreferences(), recorder)
        assertEquals(1, complete(client, "token-direct"))
        val body = recorder.bodies.single()
        assertEquals("token-direct", body.getString("purchaseToken"))
        assertEquals("coins_100", body.getString("productId"))
        assertEquals("product", body.getString("kind"))
        assertEquals("lead-1", body.getString("leadId"))
        assertEquals(install, body.getString("purchaseAccountToken"))
        assertEquals("release", body.getString("build"))
        assertFalse(body.has("orderId"))
        assertTrue(client.queuedPurchases().isEmpty())
    }

    @Test fun queuedPurchaseSurvivesARestartAndIsSentOnTheNextLaunch() {
        val directory = Files.createTempDirectory("cf-queue").toFile()
        try {
            val file = File(directory, "codefluence-purchase-queue.json")
            val preferences = referredPreferences()
            val failing = Recorder().apply { status = 503 }
            assertEquals(0, complete(sdk(preferences, failing, FilePurchaseQueueStore(file)), "token-restart"))
            assertTrue(file.exists())
            assertFalse(file.readText().contains("price"))
            // 다음 실행: 새 인스턴스·새 저장소 객체가 같은 파일을 읽는다. 소비된 소모성 구매라 스토어 목록은 비어 있다.
            now += 3_600_000
            val recorder = Recorder()
            val relaunched = sdk(preferences, recorder, FilePurchaseQueueStore(file), owned = emptyList())
            assertEquals(listOf("token-restart"), relaunched.queuedPurchases().map { it.purchaseToken })
            var count = -1
            relaunched.reportPurchases { count = it.getOrThrow() }
            assertEquals(1, count)
            assertEquals("token-restart", recorder.bodies.single().getString("purchaseToken"))
            assertEquals("lead-1", recorder.bodies.single().getString("leadId"))
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun outcomeClassificationForPurchaseReports() {
        assertEquals(PurchaseReportOutcome.ACCEPTED, PurchaseReportOutcome.forStatus(201))
        assertEquals(PurchaseReportOutcome.RETRY, PurchaseReportOutcome.forStatus(null))
        listOf(408, 409, 429, 500, 503).forEach { assertEquals("$it", PurchaseReportOutcome.RETRY, PurchaseReportOutcome.forStatus(it)) }
        listOf(401, 403).forEach { assertEquals("$it", PurchaseReportOutcome.RETRY_LATER, PurchaseReportOutcome.forStatus(it)) }
        listOf(400, 404, 410, 422).forEach { assertEquals("$it", PurchaseReportOutcome.DROP, PurchaseReportOutcome.forStatus(it)) }
    }

    @Test fun credentialMissingKeepsThePurchaseAndTheScheduledRetrySendsIt() {
        val recorder = Recorder().apply { status = 409 }
        val client = sdk(referredPreferences(), recorder)
        assertEquals(0, complete(client, "token-409"))
        val queued = client.queuedPurchases().single()
        assertEquals(1, queued.attempts)
        assertEquals(start + 15_000, queued.nextAttemptAt) // 30초 backoff × jitter 0.5
        assertEquals(15_000L, timers.single().first)
        recorder.status = 200
        now += 15_000
        timers.removeAt(0).second()
        assertEquals(2, recorder.bodies.size)
        assertTrue(client.queuedPurchases().isEmpty())
    }

    @Test fun networkFailureAndServerErrorsAreRetriedAfterBackoff() {
        val recorder = Recorder().apply { status = null }
        val client = sdk(referredPreferences(), recorder)
        assertEquals(0, complete(client, "token-offline"))
        recorder.status = 500
        client.flushPurchaseQueue()
        assertEquals(1, recorder.bodies.size) // backoff 전에는 보내지 않는다
        now += 15_000
        client.flushPurchaseQueue()
        assertEquals(2, recorder.bodies.size)
        assertEquals(2, client.queuedPurchases().single().attempts)
        assertEquals(now + 30_000, client.queuedPurchases().single().nextAttemptAt)
    }

    @Test fun permanentRejectionRemovesThePurchase() {
        val recorder = Recorder().apply { status = 422 }
        val client = sdk(referredPreferences(), recorder)
        assertEquals(0, complete(client, "token-bad"))
        assertTrue(client.queuedPurchases().isEmpty())
        now += 86_400_000
        client.flushPurchaseQueue()
        assertEquals(1, recorder.bodies.size)
    }

    @Test fun revokedKeyKeepsThePurchaseWithALongInterval() {
        val recorder = Recorder().apply { status = 401 }
        val client = sdk(referredPreferences(), recorder)
        complete(client, "token-401")
        assertEquals(start + PurchaseQueueRules.REVOKED_KEY_INTERVAL_MS, client.queuedPurchases().single().nextAttemptAt)
        now += 3_600_000
        client.flushPurchaseQueue()
        assertEquals(1, recorder.bodies.size)
        recorder.status = 200
        now += PurchaseQueueRules.REVOKED_KEY_INTERVAL_MS
        var count = -1
        client.flushPurchaseQueue { count = it.getOrThrow() }
        assertEquals(1, count)
    }

    @Test fun theSameTokenIsQueuedOnceAndAReportedOneIsNotQueuedAgain() {
        val preferences = referredPreferences()
        val recorder = Recorder().apply { status = 503 }
        val client = sdk(preferences, recorder)
        complete(client, "token-dup"); complete(client, "token-dup")
        assertEquals(1, client.queuedPurchases().size)
        preferences.edit().putString("reportedPurchaseTokens", "[\"token-old\"]").apply()
        complete(client, "token-old")
        assertEquals(listOf("token-dup"), client.queuedPurchases().map { it.purchaseToken })
        // 스토어 목록에서 읽은 같은 구매도 한 번만 들어간다.
        val scanning = sdk(preferences, recorder, owned = listOf(OwnedPurchase("token-dup", "coins_100", false)))
        scanning.reportPurchases {}
        assertEquals(1, scanning.queuedPurchases().size)
    }

    @Test fun queueKeepsAtMostOneHundredRecordsForNinetyDays() {
        var records = emptyList<QueuedPurchase>()
        for (index in 0 until 105) records = PurchaseQueueRules.enqueue(records, QueuedPurchase("t$index", "p", false, null, 1_000L + index), 1_000L + index, emptySet())
        assertEquals(100, records.size)
        assertEquals("t5", records.first().purchaseToken)
        assertEquals("t51", PurchaseQueueRules.pruned(records, 1_000L + PurchaseQueueRules.MAX_AGE_MS + 50).first().purchaseToken)
    }

    @Test fun installWithoutAReferralQueuesAndSendsNothing() {
        val recorder = Recorder()
        val client = sdk(TestPreferences(), recorder)
        assertEquals(0, complete(client, "token-organic"))
        assertTrue(client.queuedPurchases().isEmpty())
        assertTrue(recorder.bodies.isEmpty())
    }

    @Test fun pendingConfirmationHoldsThePurchaseUntilTheReferralIsConfirmed() {
        val preferences = TestPreferences(mapOf("decisionMade" to true, "accepted" to true, "pendingToken" to "signed.token", "pendingAppUserId" to "customer"))
        val recorder = Recorder()
        val client = sdk(preferences, recorder, owned = emptyList())
        assertEquals(0, complete(client, "token-early"))
        assertTrue(recorder.bodies.isEmpty())
        assertEquals(1, client.queuedPurchases().size)
        client.retryPendingConfirmation {}
        assertEquals("lead-confirmed", recorder.bodies.single().getString("leadId"))
        assertTrue(client.queuedPurchases().isEmpty())
    }

    @Test fun abandonedReferralClearsTheQueue() {
        val store = InMemoryPurchaseQueueStore().apply { save(listOf(QueuedPurchase("t", "p", false, null, start))) }
        val recorder = Recorder()
        val client = sdk(TestPreferences(), recorder, store)
        client.flushPurchaseQueue()
        assertTrue(client.queuedPurchases().isEmpty())
        assertTrue(recorder.bodies.isEmpty())
    }

    @Test fun networkRecoveryFlushesTheQueue() {
        val recorder = Recorder().apply { status = null }
        val monitor = TriggerableMonitor()
        val client = sdk(referredPreferences(), recorder, monitor = monitor)
        client.startNetworkMonitorIfNeeded()
        complete(client, "token-net")
        recorder.status = 200
        now += 60_000
        monitor.fireAvailable()
        assertEquals(2, recorder.bodies.size)
        assertTrue(client.queuedPurchases().isEmpty())
    }

    @Test fun singleReportPurchaseNowCarriesTheLeadAndInstallToken() {
        val recorder = Recorder()
        val client = sdk(referredPreferences(), recorder)
        var ok = false
        client.reportPurchase(Codefluence.Purchase("token-single", listOf("pro"), install), isSubscription = true) { ok = it.isSuccess }
        assertTrue(ok)
        val body = recorder.bodies.single()
        assertEquals("lead-1", body.getString("leadId"))
        assertEquals(install, body.getString("purchaseAccountToken"))
        assertEquals("subscription", body.getString("kind"))
    }

    @Test fun storeListPurchasesGoThroughTheQueueForAReferredInstall() {
        val recorder = Recorder().apply { status = 503 }
        val client = sdk(referredPreferences(), recorder, owned = listOf(OwnedPurchase("token-list", "monthly", true)))
        var count = -1
        client.reportPurchases { count = it.getOrThrow() }
        assertEquals(0, count)
        val queued = client.queuedPurchases().single()
        assertEquals("token-list", queued.purchaseToken)
        assertTrue(queued.subscription)
        assertNull(queued.orderId)
    }

    @Test fun fileStoreRoundTripsAndKeepsTheOrderId() {
        val directory = Files.createTempDirectory("cf-file").toFile()
        try {
            val file = File(directory, "q.json")
            FilePurchaseQueueStore(file).save(listOf(QueuedPurchase("t", "p", true, "GPA.1", 5, 2, 9)))
            assertEquals(listOf(QueuedPurchase("t", "p", true, "GPA.1", 5, 2, 9)), FilePurchaseQueueStore(file).load())
            FilePurchaseQueueStore(file).save(emptyList())
            assertFalse(file.exists())
        } finally { directory.deleteRecursively() }
    }
}
