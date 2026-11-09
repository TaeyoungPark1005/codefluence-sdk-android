package com.codefluence.sdk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * C3: read-only subscription status. Equivalent to the iOS
 * `SubscriptionStatusTests`: parses state/sandbox/updatedAt/events, preserves
 * unknown event types, requires a stored confirmed referral, maps a 404 to a
 * structured error, and falls back to the local purchase account token.
 */
class SubscriptionStatusTest {
    private fun sdk(preferences: TestPreferences, transport: CodefluenceTransport): Codefluence =
        Codefluence.createForTest("pk_fixture", "https://sdk-test.invalid", preferences, transport)

    private fun confirmedPreferences(): TestPreferences = TestPreferences(
        mapOf(
            "confirmedLeadId" to "lead-fixture",
            "confirmedPurchaseAccountToken" to "pat-fixture"
        )
    )

    @Test fun parsesStateSandboxUpdatedAtAndEvents() {
        val captured = mutableListOf<Triple<String, Map<String, String>, String>>()
        val transport = CodefluenceTransport { url, headers, body ->
            captured.add(Triple(url, headers, body))
            CodefluenceHttpResponse(
                200,
                """
                {"state":"active","sandbox":true,"updatedAt":"2026-10-28T00:00:00Z",
                 "events":[{"type":"started","occurredAt":"2026-10-01T00:00:00Z","source":"app_store"},
                           {"type":"renewed","occurredAt":"2026-10-15T00:00:00Z","source":"app_store"}]}
                """.trimIndent()
            )
        }
        var status: CodefluenceSubscriptionStatus? = null
        sdk(confirmedPreferences(), transport).subscriptionStatus { result ->
            status = result.getOrThrow()
        }
        val resolved = status ?: run { fail("expected success"); return }
        assertEquals(CodefluenceSubscriptionStatus.State.ACTIVE, resolved.state)
        assertTrue(resolved.sandbox)
        assertEquals("2026-10-28T00:00:00Z", resolved.updatedAt)
        assertEquals(2, resolved.events.size)
        assertEquals(CodefluenceSubscriptionStatus.Event.Kind.STARTED, resolved.events.first().kind)
        assertEquals(CodefluenceSubscriptionStatus.Event.Kind.RENEWED, resolved.events.last().kind)
        assertEquals("https://sdk-test.invalid/track/subscription", captured.single().first)
        assertEquals("Bearer pk_fixture", captured.single().second["Authorization"])
        val sentBody = JSONObject(captured.single().third)
        assertEquals("lead-fixture", sentBody.getString("leadId"))
        assertEquals("pat-fixture", sentBody.getString("purchaseAccountToken"))
    }

    @Test fun unknownEventTypePreservesRawTypeWithNilKind() {
        val parsed = CodefluenceSubscriptionStatus.parse(
            JSONObject(
                """
                {"state":"grace","sandbox":false,"updatedAt":null,
                 "events":[{"type":"paused","occurredAt":"2026-10-20T00:00:00Z","source":"store"}]}
                """.trimIndent()
            )
        )
        assertEquals(CodefluenceSubscriptionStatus.State.GRACE, parsed?.state)
        assertNull(parsed?.updatedAt)
        assertNull(parsed?.events?.first()?.kind)
        assertEquals("paused", parsed?.events?.first()?.rawType)
    }

    @Test fun missingConfirmedReferralFailsWithNoSubscriptionReference() {
        var sent = false
        val transport = CodefluenceTransport { _, _, _ -> sent = true; CodefluenceHttpResponse(200, "{}") }
        var error: Throwable? = null
        sdk(TestPreferences(), transport).subscriptionStatus { result ->
            error = result.exceptionOrNull()
        }
        assertTrue(error is CodefluenceError.NoSubscriptionReference)
        assertEquals(12, (error as CodefluenceError).code)
        assertTrue("no request must be made", !sent)
    }

    @Test fun server404MapsToSubscriptionNotFound() {
        val transport = CodefluenceTransport { _, _, _ ->
            CodefluenceHttpResponse(404, "{\"error\":\"subscription_not_found\"}")
        }
        var error: Throwable? = null
        sdk(confirmedPreferences(), transport).subscriptionStatus { result ->
            error = result.exceptionOrNull()
        }
        assertTrue(error is CodefluenceError.SubscriptionNotFound)
        assertEquals(13, (error as CodefluenceError).code)
    }

    @Test fun fallsBackToLocalPurchaseAccountTokenWhenConfirmedTokenAbsent() {
        val captured = mutableListOf<String>()
        val transport = CodefluenceTransport { _, _, body ->
            captured.add(body)
            CodefluenceHttpResponse(200, "{\"state\":\"none\",\"sandbox\":false,\"updatedAt\":null,\"events\":[]}")
        }
        val preferences = TestPreferences(
            mapOf("confirmedLeadId" to "lead-fixture", "purchaseAccountToken" to "local-pat")
        )
        var status: CodefluenceSubscriptionStatus? = null
        sdk(preferences, transport).subscriptionStatus { result -> status = result.getOrNull() }
        assertEquals(CodefluenceSubscriptionStatus.State.NONE, status?.state)
        assertEquals("local-pat", JSONObject(captured.single()).getString("purchaseAccountToken"))
    }
}
