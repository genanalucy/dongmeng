package com.verba.interpretation.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProtocolTest {
    @Test fun startMatchesCurrentAgentContract() {
        val json = JSONObject(StartMessage("123e4567-e89b-12d3-a456-426614174000", "zh", "en").toJson())
        assertEquals("start", json.getString("type"))
        assertEquals("s2s", json.getString("mode"))
        assertEquals("pcm", json.getString("targetAudioFormat"))
        assertEquals(16_000, json.getInt("targetAudioRate"))
        assertEquals(7, json.length())
    }

    @Test fun cloudStartBindsGrantIdentityFields() {
        val json = JSONObject(StartMessage("session-1", "zh", "en", "user-1", "install-1").toJson())
        assertEquals("session-1", json.getString("sessionId"))
        assertEquals("user-1", json.getString("userId"))
        assertEquals("install-1", json.getString("installId"))
    }

    @Test fun parsesAllTextEventKinds() {
        assertTrue(AgentProtocol.parse("{\"type\":\"ready\"}") is AgentEvent.Ready)
        assertEquals("hello", (AgentProtocol.parse("{\"type\":\"translation_final\",\"message\":\"hello\"}") as AgentEvent.Subtitle).text)
        assertEquals("en", (AgentProtocol.parse("{\"type\":\"detected_language\",\"language\":\"en\"}") as AgentEvent.DetectedLanguage).language)
        assertTrue(AgentProtocol.parse("{\"type\":\"finished\"}") is AgentEvent.Finished)
        assertEquals("BAD", (AgentProtocol.parse("{\"type\":\"error\",\"code\":\"BAD\",\"message\":\"no\"}") as AgentEvent.Error).code)
    }

    @Test fun automaticStartCarriesExactlyTwoCandidates() {
        val json = JSONObject(StartMessage("session-1", "zh", "en", candidateLanguages = listOf("zh", "en")).toJson())
        assertEquals(2, json.getJSONArray("candidateLanguages").length())
        assertEquals("zh", json.getJSONArray("candidateLanguages").getString(0))
        assertEquals("en", json.getJSONArray("candidateLanguages").getString(1))
    }

    @Test fun parsesGovernanceCodesAsTypedTerminalEventsWithoutTrustingMessage() {
        assertEquals(
            AgentEvent.SessionTerminated(TranslationSessionEndReason.REPLACED),
            AgentProtocol.parse(
                "{\"type\":\"error\",\"code\":\"TRANSLATION_SESSION_REPLACED\",\"message\":\"untrusted\"}",
            ),
        )
        assertEquals(
            AgentEvent.SessionTerminated(TranslationSessionEndReason.ENDED),
            AgentProtocol.parse(
                "{\"type\":\"error\",\"code\":\"TRANSLATION_SESSION_ENDED\",\"message\":\"pretend replaced\"}",
            ),
        )
    }

    @Test fun requiresAnExactStringGovernanceCode() {
        val numeric = AgentProtocol.parse(
            "{\"type\":\"error\",\"code\":123,\"message\":\"TRANSLATION_SESSION_REPLACED\"}",
        ) as AgentEvent.Error
        val padded = AgentProtocol.parse(
            "{\"type\":\"error\",\"code\":\" TRANSLATION_SESSION_REPLACED \",\"message\":\"ignored\"}",
        ) as AgentEvent.Error

        assertEquals("UNKNOWN", numeric.code)
        assertEquals(" TRANSLATION_SESSION_REPLACED ", padded.code)
    }

    @Test(expected = ProtocolException::class)
    fun rejectsUnknownEvents() { AgentProtocol.parse("{\"type\":\"vendor_guess\"}") }
}
