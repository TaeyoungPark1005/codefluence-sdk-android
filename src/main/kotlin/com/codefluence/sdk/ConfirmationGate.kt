package com.codefluence.sdk

/** Shared across SDK instances so restoration/dialogs and sends cannot overlap. */
internal class ConfirmationGate {
    private var presenting = false
    private var requesting = false

    @Synchronized fun beginPrompt(): Boolean {
        if (presenting) return false
        presenting = true
        return true
    }
    @Synchronized fun endPrompt() { presenting = false }
    @Synchronized fun beginRequest(): Boolean {
        if (requesting) return false
        requesting = true
        return true
    }
    @Synchronized fun endRequest() { requesting = false }
}
