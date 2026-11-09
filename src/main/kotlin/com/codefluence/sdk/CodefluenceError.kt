package com.codefluence.sdk

/**
 * Structured public error for the Codefluence SDK (B6).
 *
 * `Result.failure` from the public callbacks now carries a [CodefluenceError].
 * Each case exposes a stable numeric [code] (aligned with the iOS SDK) and an
 * [isRetryable] flag telling a host whether a later `retryPendingConfirmation`
 * (or the automatic queue) can make progress. It subclasses
 * [IllegalStateException] (the pre-B6 failure type) so existing
 * `Result<String?>` callbacks and `catch (IllegalStateException)` blocks keep
 * working unchanged.
 */
sealed class CodefluenceError(val code: Int, message: String, val isRetryable: Boolean = false) : IllegalStateException(message) {
    /** code 1 — a network/HTTP request failed or returned an invalid body. */
    class RequestFailed(message: String) : CodefluenceError(1, message, isRetryable = true)
    /** code 2 — confirm requires a non-blank referral token and customer ID. */
    object MissingReferralInput : CodefluenceError(2, "A referral token and stable customer ID are required")
    /** code 3 — a saved "No"/decline cannot be bypassed. */
    object Declined : CodefluenceError(3, "Referral confirmation already has a decision")
    /** code 4 — a completed/again-resolved attribution belongs to another customer. */
    object AttributionForDifferentCustomer : CodefluenceError(4, "Referral attribution belongs to a different customer")
    /** code 5 — a retry changed the accepted token or customer ID. */
    object RetryIdentityMismatch : CodefluenceError(5, "Retry must preserve the accepted token and customer ID")
    /** code 6 — there is no accepted, unfinished confirmation to retry. */
    object NoPendingConfirmation : CodefluenceError(6, "No accepted pending confirmation is available")
    /** code 7 — a confirmation request is already in progress. */
    object RequestInProgress : CodefluenceError(7, "Referral confirmation is already in progress")
    /** code 8 — a prompt/attribution is already being presented or resolved. */
    object AlreadyPresenting : CodefluenceError(8, "Referral confirmation is already being presented")
    /** code 9 — a foreground Activity and/or stable customer ID is required. */
    class PresenterOrCustomerRequired(message: String) : CodefluenceError(9, message)
    /** code 10 — restoration/presentation failed before a decision was stored. */
    class RestorationFailed(message: String) : CodefluenceError(10, message)
    /** code 11 — the SDK is not configured (missing/invalid pk_ key). */
    object NotConfigured : CodefluenceError(11, "SDK is not configured")
    /** code 12 — no saved leadId/purchaseAccountToken is available to read status. */
    object NoSubscriptionReference : CodefluenceError(12, "No confirmed referral is available to read subscription status")
    /** code 13 — the server has no subscription record for the saved references. */
    object SubscriptionNotFound : CodefluenceError(13, "No subscription record was found for this customer")
}
