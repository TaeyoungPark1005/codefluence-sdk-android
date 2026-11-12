package com.codefluence.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 이미 소개로 확인된 설치가 다른 크리에이터의 링크를 다시 열면 서버에 알린다(소모성 재결제 귀속의 근거). 첫 소개자는 바뀌지 않는다. */
class TouchTest {
    private val tokenA = "6902082a-b202-4f7f-9b61-6ec419fb3b81.481e73f0-b627-482a-9466-5bc004807ff9.1796533770.UTM7ns4CUi-k-rI8rPXizYq5kjXadqEi5hP_jSA2JYM"
    private val tokenB = "7a02082a-b202-4f7f-9b61-6ec419fb3b82.481e73f0-b627-482a-9466-5bc004807ff9.1796533999.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    private val install = "11111111-1111-4111-8111-111111111111"

    private fun confirmed(transport: CodefluenceTransport): Pair<Codefluence, TestPreferences> {
        val preferences = TestPreferences(mapOf("decisionMade" to true, "accepted" to true, "confirmedLeadId" to "lead-1", "confirmedAppUserId" to "user-1",
            "clickToken" to tokenA, "purchaseAccountToken" to install))
        val sdk = Codefluence.createForTest("pk_fixture", "https://api.example.test", preferences, transport)
        sdk.injectRetryDependencies(policy = CodefluenceBackoffPolicy(), jitter = { 0.5 }, scheduler = { _, _ -> }, monitor = null)
        sdk.begin(appUserId = { "user-1" }, launchGraceMs = 3_600_000, schedule = { _, _ -> })
        return sdk to preferences
    }
    private fun ScriptedTransport.touches() = captured.filter { it.url.endsWith("/track/touch") }

    @Test fun anotherCreatorsLinkIsReportedWithTheInstallToken() {
        val transport = ScriptedTransport().also { it.reply = { CodefluenceHttpResponse(200, "{\"recorded\":true}") } }
        val (sdk, preferences) = confirmed(transport)
        assertTrue(sdk.handle("adledger://referral?cf_click_token=$tokenB"))
        val body = JSONObject(transport.touches().single().body)
        assertEquals(tokenB, body.getString("clickToken"))
        assertEquals("user-1", body.getString("appUserId"))
        assertEquals(install, body.getString("purchaseAccountToken"))
        assertEquals("release", body.getString("build"))
        assertEquals("첫 소개 토큰은 바뀌지 않는다", tokenA, preferences.getString("clickToken", null))
    }

    @Test fun theSameLinkIsReportedOnlyOnce() {
        val transport = ScriptedTransport().also { it.reply = { CodefluenceHttpResponse(200, "{\"recorded\":true}") } }
        val (sdk, _) = confirmed(transport)
        sdk.handle("adledger://referral?cf_click_token=$tokenB"); sdk.handle("adledger://referral?cf_click_token=$tokenB")
        assertEquals(1, transport.touches().size)
    }

    @Test fun theOriginalLinkIsNotReportedAgain() {
        val transport = ScriptedTransport()
        val (sdk, _) = confirmed(transport)
        sdk.handle("adledger://referral?cf_click_token=$tokenA")
        assertTrue(transport.touches().isEmpty())
    }

    @Test fun aTransientFailureIsRetriedWhenTheLinkIsOpenedAgain() {
        var touchCalls = 0
        val transport = ScriptedTransport()
        transport.reply = { CodefluenceHttpResponse(if (transport.captured.last().url.endsWith("/track/touch") && ++touchCalls == 1) 503 else 200, "{}") }
        val (sdk, _) = confirmed(transport)
        sdk.handle("adledger://referral?cf_click_token=$tokenB"); sdk.handle("adledger://referral?cf_click_token=$tokenB")
        assertEquals(2, transport.touches().size)
    }

    @Test fun anInstallThatWasNeverConfirmedSendsNoTouch() {
        val transport = ScriptedTransport()
        val sdk = Codefluence.createForTest("pk_fixture", "https://api.example.test", TestPreferences(), transport)
        sdk.injectRetryDependencies(policy = CodefluenceBackoffPolicy(), jitter = { 0.5 }, scheduler = { _, _ -> }, monitor = null)
        sdk.begin(appUserId = { "user-1" }, launchGraceMs = 3_600_000, schedule = { _, _ -> })
        sdk.handle("adledger://referral?cf_click_token=$tokenB")
        assertTrue(transport.touches().isEmpty())
    }
}
