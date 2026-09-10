package com.verba.interpretation.protocol

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationSettingsStoreTest {
    @Test
    fun defaultsUseVolcengineAndServerSelectedVoices() {
        val settings = TranslationSettingsStore(FakeSharedPreferences()).load()

        assertEquals(TranslationProvider.VOLCENGINE, settings.provider)
        TranslationVoiceOptions.languages.forEach { language -> assertEquals("", settings.voiceFor(language)) }
    }

    @Test
    fun providerAndVoicePersistAcrossStoreInstances() {
        val preferences = FakeSharedPreferences()
        TranslationSettingsStore(preferences).apply {
            saveProvider(TranslationProvider.AZURE)
            saveVoice("en", "en-US-GuyNeural")
        }

        val settings = TranslationSettingsStore(preferences).load()
        assertEquals(TranslationProvider.AZURE, settings.provider)
        assertEquals("en-US-GuyNeural", settings.voiceFor("en"))
    }

    @Test
    fun invalidStoredValuesFallBackToDefaults() {
        val preferences = FakeSharedPreferences()
        preferences.edit().putString("provider", "unknown").putString("voice_zh", "not-a-voice").apply()

        val settings = TranslationSettingsStore(preferences).load()
        assertEquals(TranslationProvider.VOLCENGINE, settings.provider)
        assertEquals("", settings.voiceFor("zh"))
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): Map<String, Any?> = values.toMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = values[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        private inner class Editor : SharedPreferences.Editor {
            override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { values[key] = value }
            override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor = apply { this@FakeSharedPreferences.values[key] = values }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { values[key] = value }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { values[key] = value }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { values[key] = value }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { values[key] = value }
            override fun remove(key: String): SharedPreferences.Editor = apply { values.remove(key) }
            override fun clear(): SharedPreferences.Editor = apply { values.clear() }
            override fun commit(): Boolean = true
            override fun apply() = Unit
        }
    }
}
