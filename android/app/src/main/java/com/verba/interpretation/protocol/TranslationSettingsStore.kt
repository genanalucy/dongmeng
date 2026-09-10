package com.verba.interpretation.protocol

import android.content.Context
import android.content.SharedPreferences

enum class TranslationProvider(val storedValue: String) {
    VOLCENGINE("volcengine"),
    AZURE("azure"),
    ;

    companion object {
        fun fromStored(value: String?): TranslationProvider = entries.firstOrNull { it.storedValue == value } ?: VOLCENGINE
    }
}

/** Debug-only translation preferences. An empty voice delegates selection to the service. */
data class TranslationSettings(
    val provider: TranslationProvider = TranslationProvider.VOLCENGINE,
    val voices: Map<String, String> = emptyMap(),
) {
    fun voiceFor(language: String): String = voices[language].orEmpty()
}

object TranslationVoiceOptions {
    val voicesByLanguage: Map<String, List<String>> = mapOf(
        "zh" to listOf("zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural"),
        "en" to listOf("en-US-JennyNeural", "en-US-GuyNeural"),
        "fr" to listOf("fr-FR-DeniseNeural", "fr-FR-HenriNeural"),
        "vi" to listOf("vi-VN-HoaiMyNeural", "vi-VN-NamMaleNeural"),
    )
    val languages: Set<String> get() = voicesByLanguage.keys

    fun isSupported(language: String, voice: String): Boolean = voice.isEmpty() || voice in voicesByLanguage[language].orEmpty()
}

class TranslationSettingsStore internal constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    fun load(): TranslationSettings = TranslationSettings(
        provider = TranslationProvider.fromStored(preferences.getString(PROVIDER_KEY, null)),
        voices = TranslationVoiceOptions.languages.associateWith { language ->
            preferences.getString(voiceKey(language), null).orEmpty()
                .takeIf { voice -> TranslationVoiceOptions.isSupported(language, voice) }
                .orEmpty()
        },
    )

    fun saveProvider(provider: TranslationProvider) {
        preferences.edit().putString(PROVIDER_KEY, provider.storedValue).apply()
    }

    fun saveVoice(language: String, voice: String) {
        require(TranslationVoiceOptions.isSupported(language, voice)) { "Unsupported voice for $language" }
        preferences.edit().putString(voiceKey(language), voice).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "translation_settings"
        const val PROVIDER_KEY = "provider"
        fun voiceKey(language: String) = "voice_$language"
    }
}
