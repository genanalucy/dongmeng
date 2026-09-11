package com.verba.interpretation.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AgentProtocolContinuousTest {
    @Test fun parsesLogicalSegmentBindingForContinuousFinals() {
        val event = AgentProtocol.parse("""{"type":"detected_language","language":"en","segmentId":2,"targetLanguage":"zh"}""") as AgentEvent.DetectedLanguage
        assertEquals("en", event.language)
        assertEquals(2L, event.segmentId)
        assertEquals("zh", event.targetLanguage)
        val tts = AgentProtocol.parse("""{"type":"tts","segmentId":2,"targetLanguage":"zh"}""") as AgentEvent.TtsSegment
        assertEquals(2L, tts.segmentId)
        assertEquals("zh", tts.targetLanguage)
        val prelude = AgentProtocol.parse("""{"type":"tts_start","segmentId":2,"targetLanguage":"zh"}""") as AgentEvent.TtsSegment
        assertEquals(2L, prelude.segmentId)
        assertEquals("zh", prelude.targetLanguage)
        assertEquals(true, prelude.startsPlayback)
    }

    @Test fun rejectsInvalidContinuousSegmentBinding() {
        try {
            AgentProtocol.parse("""{"type":"tts","segmentId":0,"targetLanguage":"zh"}""")
            fail("expected ProtocolException")
        } catch (_: ProtocolException) {
        }
        try {
            AgentProtocol.parse("""{"type":"source_final","message":"hello","segmentId":1}""")
            fail("expected ProtocolException")
        } catch (_: ProtocolException) {
        }
    }
}
