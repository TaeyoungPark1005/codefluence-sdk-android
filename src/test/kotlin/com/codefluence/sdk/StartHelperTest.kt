package com.codefluence.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 통합 헬퍼(handle / resume / purchaseCompleted). 개발자가 호출 순서를 맞추지 않아도
 * 딥링크가 사용자 ID보다 먼저 와도, 시작 직후 "귀속 없음"이 확정되어도 소개가 사라지면 안 된다.
 */
class StartHelperTest {
    private val token = "6902082a-b202-4f7f-9b61-6ec419fb3b81.481e73f0-b627-482a-9466-5bc004807ff9.1796533770.UTM7ns4CUi-k-rI8rPXizYq5kjXadqEi5hP_jSA2JYM"
    private val owned = listOf(OwnedPurchase("purchase-1", "pro", true))

    private class Fixture(val preferences: TestPreferences, val transport: ScriptedTransport, val sdk: Codefluence, val timers: MutableList<() -> Unit>)

    private fun fixture(userId: () -> String?): Fixture {
        val preferences = TestPreferences()
        val transport = ScriptedTransport()
        val sdk = Codefluence.createForTest("pk_fixture", "https://api.example.test", preferences, transport,
            purchaseSource = PurchaseSource { it(owned) })
        val timers = mutableListOf<() -> Unit>()
        sdk.injectRetryDependencies(policy = CodefluenceBackoffPolicy(), jitter = { 0.5 }, scheduler = { _, work -> timers.add(work) }, monitor = null)
        sdk.begin(appUserId = userId, launchGraceMs = 800, schedule = { _, work -> timers.add(work) })
        return Fixture(preferences, transport, sdk, timers)
    }

    private fun Fixture.confirms() = transport.captured.filter { it.url.endsWith("/track/confirm") }.map { JSONObject(it.body) }
    private fun Fixture.purchases() = transport.captured.filter { it.url.endsWith("/track/purchase") }

    @Test fun referralLinkIsConfirmedWithTheHostUserIdAndTheFullToken() {
        val f = fixture { "user-1" }
        assertTrue(f.sdk.handle("adledger://referral?cf_click_token=$token"))
        assertEquals(1, f.confirms().size)
        assertEquals(token, f.confirms().single().getString("clickToken"))
        assertEquals("user-1", f.confirms().single().getString("appUserId"))
    }

    @Test fun linksThatAreNotReferralsAreIgnored() {
        val f = fixture { "user-1" }
        assertFalse(f.sdk.handle("adledger://settings"))
        assertFalse(f.sdk.handle("https://example.com/other"))
        assertFalse(f.sdk.handle("adledger://referral?cf_click_token=truncated-token"))
        assertTrue(f.confirms().isEmpty())
    }

    @Test fun referralBeforeTheUserIdIsKnownWaitsForResume() {
        var id: String? = null
        val f = fixture { id }
        assertTrue(f.sdk.handle("adledger://referral?cf_click_token=$token"))
        assertTrue(f.confirms().isEmpty())
        assertFalse("ID가 없다는 이유로 귀속 없음을 확정하면 안 된다", f.preferences.getBoolean("decisionMade", false))
        id = "late-user"
        f.sdk.resume()
        assertEquals("late-user", f.confirms().single().getString("appUserId"))
        assertEquals(token, f.confirms().single().getString("clickToken"))
    }

    @Test fun launchWithoutATokenDoesNotDecideBeforeTheDeepLinkCanArrive() {
        val f = fixture { "user-1" }
        assertFalse(f.preferences.getBoolean("decisionMade", false))
        assertTrue(f.sdk.handle("adledger://referral?cf_click_token=$token"))
        assertEquals(1, f.confirms().size)
        f.timers.toList().forEach { it() }
        assertEquals("유예가 끝나도 같은 소개를 다시 보내지 않는다", 1, f.confirms().size)
    }

    @Test fun graceElapsedWithoutAnyLinkRecordsNoAttributionOnce() {
        val f = fixture { "user-1" }
        f.timers.toList().forEach { it() }
        assertTrue(f.preferences.getBoolean("decisionMade", false))
        assertTrue(f.confirms().isEmpty())
    }

    @Test fun purchaseCompletedReportsOnlyAfterAReferralWasConfirmed() {
        val f = fixture { "user-1" }
        f.sdk.purchaseCompleted()
        assertTrue("소개로 온 적 없는 설치의 결제는 보내지 않는다", f.purchases().isEmpty())
        assertFalse(f.preferences.getBoolean("decisionMade", false))
        assertTrue(f.sdk.handle("adledger://referral?cf_click_token=$token"))
        val afterConfirm = f.purchases().size
        assertTrue("확인 직후 SDK가 결제를 보고한다", afterConfirm >= 1)
        assertEquals("lead-fixture", JSONObject(f.purchases().first().body).getString("leadId"))
    }
}
