package com.verba.interpretation.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationSettingsSerializationTest {
    @Test
    fun defaultSettingsKeepLegacyStartMessageExactlyUnchanged() {
        val legacy = StartMessage("session-1", "zh", "en").toJson()
        val configured = StartMessage("session-1", "zh", "en", settings = TranslationSettings()).toJson()

        assertEquals(legacy, configured)
    }

    @Test
    fun azureProviderAndConfiguredTargetVoiceAreIncludedInStartMessage() {
        val json = JSONObject(
            StartMessage(
                sessionId = "session-1",
                sourceLanguage = "zh",
                targetLanguage = "en",
                settings = TranslationSettings(TranslationProvider.AZURE, mapOf("en" to "en-US-JennyNeural")),
            ).toJson(),
        )

        assertEquals("azure", json.getString("provider"))
        assertEquals("en-US-JennyNeural", json.getString("voice"))
    }

    @Test
    fun volcengineWithConfiguredVoiceOnlyIncludesVoice() {
        val json = JSONObject(
            StartMessage(
                sessionId = "session-1",
                sourceLanguage = "zh",
                targetLanguage = "en",
                settings = TranslationSettings(voices = mapOf("en" to "en-US-GuyNeural")),
            ).toJson(),
        )

        assertFalse(json.has("provider"))
        assertTrue(json.has("voice"))
    }
}
