package com.verba.interpretation.brand

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Persists-choice contract for [ThemeModeStore] over an in-memory
 * [SharedPreferences] fake (plain JVM, no Android framework): default value,
 * cross-instance persistence, canonical writes and the invalid-value fallback.
 */
class ThemeModeStoreTest {

    @Test
    fun missingKeyLoadsSystemMode() {
        val preferences = FakeSharedPreferences()

        assertEquals(ThemeMode.SYSTEM, ThemeModeStore(preferences).load())
    }

    @Test
    fun savedModePersistsAcrossStoreInstances() {
        val preferences = FakeSharedPreferences()
        ThemeModeStore(preferences).save(ThemeMode.DARK)

        // 新实例模拟进程重启后重新读取同一持久化文件。
        assertEquals(ThemeMode.DARK, ThemeModeStore(preferences).load())
    }

    @Test
    fun saveWritesCanonicalStoredValueUnderModeKey() {
        val preferences = FakeSharedPreferences()

        ThemeModeStore(preferences).save(ThemeMode.LIGHT)

        assertEquals("light", preferences.getString("mode", null))
    }

    @Test
    fun invalidStoredValueFallsBackToSystem() {
        val preferences = FakeSharedPreferences()
        preferences.edit().putString("mode", "midnight").apply()

        assertEquals(ThemeMode.SYSTEM, ThemeModeStore(preferences).load())
    }

    @Test
    fun emptyStoredValueFallsBackToSystem() {
        val preferences = FakeSharedPreferences()
        preferences.edit().putString("mode", "").apply()

        assertEquals(ThemeMode.SYSTEM, ThemeModeStore(preferences).load())
    }

    /** Minimal in-memory SharedPreferences implementation; apply() mutates synchronously. */
    private class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, Any?> = values.toMap()

        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? =
            values[key] as? Set<String> ?: defValues

        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        private inner class FakeEditor : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
                values[key] = value
            }

            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply {
                this@FakeSharedPreferences.values[key] = values
            }

            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
                values[key] = value
            }

            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
                values[key] = value
            }

            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
                values[key] = value
            }

            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
                values[key] = value
            }

            override fun remove(key: String): SharedPreferences.Editor = apply {
                values.remove(key)
            }

            override fun clear(): SharedPreferences.Editor = apply {
                values.clear()
            }

            override fun commit(): Boolean = true

            override fun apply() = Unit
        }
    }
}
