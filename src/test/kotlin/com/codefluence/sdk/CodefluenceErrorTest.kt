package com.codefluence.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B6: Structured public error. Numeric codes must stay stable and aligned with
 * the iOS SDK, and the type must remain an [IllegalStateException] subtype so
 * existing message-based `Result.failure` callbacks and `catch` blocks keep
 * working unchanged.
 */
class CodefluenceErrorTest {
    @Test fun eachCaseKeepsItsHistoricalNumericCode() {
        val mapping: List<Pair<CodefluenceError, Int>> = listOf(
            CodefluenceError.RequestFailed("x") to 1,
            CodefluenceError.MissingReferralInput to 2,
            CodefluenceError.Declined to 3,
            CodefluenceError.AttributionForDifferentCustomer to 4,
            CodefluenceError.RetryIdentityMismatch to 5,
            CodefluenceError.NoPendingConfirmation to 6,
            CodefluenceError.RequestInProgress to 7,
            CodefluenceError.AlreadyPresenting to 8,
            CodefluenceError.PresenterOrCustomerRequired("y") to 9,
            CodefluenceError.RestorationFailed("z") to 10,
            CodefluenceError.NotConfigured to 11,
            CodefluenceError.NoSubscriptionReference to 12,
            CodefluenceError.SubscriptionNotFound to 13
        )
        for ((error, expected) in mapping) {
            assertEquals("${error::class.simpleName}", expected, error.code)
        }
    }

    @Test fun codesCoverOneThroughThirteenWithoutGaps() {
        val codes = listOf(
            CodefluenceError.RequestFailed("x"),
            CodefluenceError.MissingReferralInput,
            CodefluenceError.Declined,
            CodefluenceError.AttributionForDifferentCustomer,
            CodefluenceError.RetryIdentityMismatch,
            CodefluenceError.NoPendingConfirmation,
            CodefluenceError.RequestInProgress,
            CodefluenceError.AlreadyPresenting,
            CodefluenceError.PresenterOrCustomerRequired("y"),
            CodefluenceError.RestorationFailed("z"),
            CodefluenceError.NotConfigured,
            CodefluenceError.NoSubscriptionReference,
            CodefluenceError.SubscriptionNotFound
        ).map { it.code }.sorted()
        assertEquals((1..13).toList(), codes)
    }

    @Test fun onlyRequestFailedIsRetryable() {
        assertTrue(CodefluenceError.RequestFailed("timeout").isRetryable)
        val others: List<CodefluenceError> = listOf(
            CodefluenceError.MissingReferralInput,
            CodefluenceError.Declined,
            CodefluenceError.AttributionForDifferentCustomer,
            CodefluenceError.RetryIdentityMismatch,
            CodefluenceError.NoPendingConfirmation,
            CodefluenceError.RequestInProgress,
            CodefluenceError.AlreadyPresenting,
            CodefluenceError.PresenterOrCustomerRequired("y"),
            CodefluenceError.RestorationFailed("z"),
            CodefluenceError.NotConfigured,
            CodefluenceError.NoSubscriptionReference,
            CodefluenceError.SubscriptionNotFound
        )
        for (error in others) {
            assertFalse("${error::class.simpleName} must not be retryable", error.isRetryable)
        }
    }

    @Test fun remainsAnIllegalStateExceptionForLegacyCatchAndMessageCompatibility() {
        // Existing hosts catch IllegalStateException and read getMessage(); the
        // structured errors must keep both behaviors.
        val error: Throwable = CodefluenceError.RequestFailed("Referral confirmation request failed")
        assertTrue(error is IllegalStateException)
        assertTrue(error is RuntimeException)
        assertEquals("Referral confirmation request failed", error.message)
    }

    @Test fun carriesTheHistoricalMessagesExpectedByCallers() {
        assertEquals(
            "A referral token and stable customer ID are required",
            CodefluenceError.MissingReferralInput.message
        )
        assertEquals(
            "No accepted pending confirmation is available",
            CodefluenceError.NoPendingConfirmation.message
        )
        assertEquals(
            "Referral confirmation is already in progress",
            CodefluenceError.RequestInProgress.message
        )
        assertEquals("configured message", CodefluenceError.PresenterOrCustomerRequired("configured message").message)
    }
}
