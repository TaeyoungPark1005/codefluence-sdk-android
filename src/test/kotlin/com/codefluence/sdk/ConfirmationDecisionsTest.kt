package com.codefluence.sdk

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ConfirmationDecisionsTest {
    @Test fun noTokenIsSoftButArbitraryTokensCannotRestoreIt() {
        val preferences = MemoryPreferences()
        val decisions = ConfirmationDecisions(preferences)
        assertEquals(ConfirmationPreparation.NO_ATTRIBUTION, decisions.prepare(null))
        assertTrue(preferences.getBoolean("noTokenAtLaunch", false))
        assertFalse(preferences.getBoolean("accepted", false))
        val restarted = ConfirmationDecisions(MemoryPreferences(preferences.all))
        assertEquals(ConfirmationPreparation.NO_ATTRIBUTION, restarted.prepare("later.token"))
        assertEquals(ConfirmationPreparation.NO_ATTRIBUTION, restarted.prepare(null))
        assertFalse(restarted.stage("later.token", "customer"))
    }

    @Test fun blankTokenIsNoAttributionButRestoredTokenRequiresConfirmation() {
        val preferences = MemoryPreferences()
        val decisions = ConfirmationDecisions(preferences)
        assertEquals(ConfirmationPreparation.PRESENT, decisions.prepare("signed.token"))
        assertFalse(preferences.contains("decisionMade"))
        assertEquals(ConfirmationPreparation.NO_ATTRIBUTION, decisions.prepare(" \n"))
    }

    @Test fun lateEmptyRestorationAndDeclinePreserveAcceptedPendingIdentity() {
        val preferences = MemoryPreferences()
        val decisions = ConfirmationDecisions(preferences)
        assertTrue(decisions.stage("accepted.token", "customer"))
        val accepted = preferences.all
        assertEquals(ConfirmationPreparation.SKIP, decisions.prepare(null))
        assertFalse(decisions.decline())
        assertFalse(decisions.stage("other.token", "other-customer"))
        assertEquals(accepted, preferences.all)
        assertTrue(decisions.stage("accepted.token", "customer"))
    }

    @Test fun partialStoredDecisionsCannotBeOverwrittenByNoToken() {
        for ((key, value) in mapOf("accepted" to true, "pendingToken" to "token",
            "pendingAppUserId" to "customer", "confirmedLeadId" to "lead")) {
            val preferences = MemoryPreferences(mapOf(key to value))
            val before = preferences.all
            assertEquals(ConfirmationPreparation.SKIP, ConfirmationDecisions(preferences).prepare(null))
            assertEquals(before, preferences.all)
        }
    }

    @Test fun competingAcceptanceAndEmptyRestorationNeverOverwriteTheFirstDecision() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(64) {
                val preferences = MemoryPreferences()
                val first = ConfirmationDecisions(preferences)
                val second = ConfirmationDecisions(preferences)
                val accepted = pool.submit<Boolean> { first.stage("accepted.token", "customer") }
                val restored = pool.submit<ConfirmationPreparation> { second.prepare(null) }
                val didAccept = accepted.get(3, TimeUnit.SECONDS)
                val restoration = restored.get(3, TimeUnit.SECONDS)
                assertEquals(didAccept, preferences.getBoolean("decisionMade", false))
                assertEquals(didAccept, preferences.getBoolean("accepted", false))
                assertEquals(if (didAccept) ConfirmationPreparation.SKIP else ConfirmationPreparation.NO_ATTRIBUTION, restoration)
                assertEquals(if (didAccept) "accepted.token" else null, preferences.getString("pendingToken", null))
                assertEquals(if (didAccept) "customer" else null, preferences.getString("pendingAppUserId", null))
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun copyUsesKoreanOnlyForKoreanLanguage() {
        for (tag in listOf("ko", "ko-KR", "ko-US")) {
            val copy = ConfirmationCopy(Locale.forLanguageTag(tag))
            assertEquals("이 소개로 시작할까요?", copy.title)
            assertEquals("이 소개 링크를 결제 귀속에 사용합니다", copy.message)
            assertEquals("예", copy.yes)
            assertEquals("아니오", copy.no)
        }
        for (tag in listOf("en", "en-KR", "ja-JP", "fr-FR", "")) {
            val copy = ConfirmationCopy(Locale.forLanguageTag(tag))
            assertEquals("Start with this referral?", copy.title)
            assertEquals("Use this referral link to attribute your purchases.", copy.message)
            assertEquals("Yes", copy.yes)
            assertEquals("No", copy.no)
        }
    }
}

/** In-memory implementation of the Android storage interface; no Android runtime. */
private class MemoryPreferences(initial: Map<String, *> = emptyMap<String, Any>()) : SharedPreferences {
    private val values = initial.toMutableMap()
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun contains(key: String?) = values.containsKey(key)
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as? Boolean ?: defValue
    override fun getString(key: String?, defValue: String?) = values[key] as? String ?: defValue
    override fun getInt(key: String?, defValue: Int) = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as? Float ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = values[key] as? MutableSet<String> ?: defValues
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val writes = mutableMapOf<String, Any?>()
        private var clear = false
        override fun putBoolean(key: String, value: Boolean) = apply { writes[key] = value }
        override fun putString(key: String, value: String?) = apply { writes[key] = value }
        override fun putStringSet(key: String, value: MutableSet<String>?) = apply { writes[key] = value }
        override fun putInt(key: String, value: Int) = apply { writes[key] = value }
        override fun putLong(key: String, value: Long) = apply { writes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { writes[key] = value }
        override fun remove(key: String) = apply { writes[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) values.clear()
            writes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}
