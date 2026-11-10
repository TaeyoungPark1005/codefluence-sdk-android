package com.codefluence.sdk

import android.content.SharedPreferences
import java.util.Locale

internal enum class ConfirmationPreparation { PRESENT, NO_ATTRIBUTION, SKIP }

/** All instances use the same preference lock, including late referrer callbacks. */
internal class ConfirmationDecisions(private val preferences: SharedPreferences) {
    fun prepare(token: String?): ConfirmationPreparation = synchronized(preferences) {
        if (hasDecision()) return@synchronized ConfirmationPreparation.SKIP
        if (preferences.getBoolean("noTokenAtLaunch", false) && (token == null || token != preferences.getString("laterLinkToken", null))) return@synchronized ConfirmationPreparation.NO_ATTRIBUTION
        if (!token.isNullOrBlank()) return@synchronized ConfirmationPreparation.PRESENT
        preferences.edit().putBoolean("noTokenAtLaunch", true).apply()
        ConfirmationPreparation.NO_ATTRIBUTION
    }

    fun acceptLink(token: String) = synchronized(preferences) {
        if (!hasDecision()) {
            val editor = preferences.edit().putString("clickToken", token)
            if (preferences.getBoolean("noTokenAtLaunch", false)) editor.putString("laterLinkToken", token)
            editor.apply()
        }
    }

    fun stage(token: String, appUserId: String): Boolean = synchronized(preferences) {
        if (preferences.getBoolean("decisionMade", false) && !preferences.getBoolean("accepted", false)) return@synchronized false
        if (preferences.contains("confirmedLeadId")) return@synchronized false
        val pendingToken = preferences.getString("pendingToken", null)
        val pendingCustomer = preferences.getString("pendingAppUserId", null)
        if ((pendingToken != null || pendingCustomer != null) && (pendingToken != token || pendingCustomer != appUserId)) return@synchronized false
        if (preferences.getBoolean("noTokenAtLaunch", false) && pendingToken == null && token != preferences.getString("laterLinkToken", null)) return@synchronized false
        val editor = preferences.edit().putBoolean("decisionMade", true).putBoolean("accepted", true)
            .putString("pendingToken", token).putString("pendingAppUserId", appUserId)
        // A3: one idempotency key per queued confirmation, reused across retries.
        if (preferences.getString("pendingIdempotencyKey", null).isNullOrEmpty()) {
            editor.putString("pendingIdempotencyKey", java.util.UUID.randomUUID().toString())
            editor.putInt("pendingAttempts", 0)
        }
        if (!preferences.contains("pendingLaunchKind")) editor.putString("pendingLaunchKind", if (preferences.getBoolean("noTokenAtLaunch", false)) "later" else "first_launch")
        editor.apply()
        true
    }

    fun decline(): Boolean = synchronized(preferences) {
        if (hasDecision()) return@synchronized false
        saveNoAttribution()
        true
    }

    private fun hasDecision(): Boolean = preferences.getBoolean("decisionMade", false) ||
        preferences.getBoolean("accepted", false) || preferences.contains("pendingToken") ||
        preferences.contains("pendingAppUserId") || preferences.contains("confirmedLeadId")

    private fun saveNoAttribution() {
        preferences.edit().putBoolean("decisionMade", true).putBoolean("accepted", false).apply()
    }
}

internal class ConfirmationCopy(locale: Locale) {
    private val korean = locale.language == "ko"
    val title = if (korean) "이 소개로 시작할까요?" else "Start with this referral?"
    val message = if (korean) "이 소개 링크를 결제 귀속에 사용합니다" else "Use this referral link to attribute your purchases."
    val yes = if (korean) "예" else "Yes"
    val no = if (korean) "아니오" else "No"
}
