package com.codefluence.sdk

import android.content.SharedPreferences

/** Shared in-memory SharedPreferences for JVM unit tests; no Android runtime. */
class TestPreferences(initial: Map<String, *> = emptyMap<String, Any>()) : SharedPreferences {
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
