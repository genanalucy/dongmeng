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

    @Test fun parsesAllTextEventKinds() {
        assertTrue(AgentProtocol.parse("{\"type\":\"ready\"}") is AgentEvent.Ready)
        assertEquals("hello", (AgentProtocol.parse("{\"type\":\"translation_final\",\"message\":\"hello\"}") as AgentEvent.Subtitle).text)
        assertTrue(AgentProtocol.parse("{\"type\":\"finished\"}") is AgentEvent.Finished)
        assertEquals("BAD", (AgentProtocol.parse("{\"type\":\"error\",\"code\":\"BAD\",\"message\":\"no\"}") as AgentEvent.Error).code)
    }

    @Test(expected = ProtocolException::class)
    fun rejectsUnknownEvents() { AgentProtocol.parse("{\"type\":\"vendor_guess\"}") }
}
