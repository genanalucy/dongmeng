package com.verba.interpretation.diagnostics

import com.verba.interpretation.protocol.AgentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugDiagnosticLogTest {
    @Test fun keepsOnlyNewestCapacityEntries() {
        val logger = DebugDiagnosticLog(clock = { 1_000L }, capacity = 2)
        logger.faceAction(FaceAction.START_AUTO)
        logger.faceAction(FaceAction.STOP_AUTO)
        logger.faceAction(FaceAction.MODE_AUTO)

        assertEquals(listOf("STOP_AUTO", "MODE_AUTO"), logger.entries().map { it.message })
    }

    @Test fun agentEventRedactsSubtitleAndErrorMessage() {
        val secret = "Bearer token=very-secret transcript"
        val logger = DebugDiagnosticLog()
        logger.agentEvent(AgentEvent.Subtitle(AgentEvent.Subtitle.Kind.SOURCE_FINAL, secret))
        logger.agentEvent(AgentEvent.Error("AZURE_FAILURE", secret))

        val copy = logger.render()
        assertFalse(copy.contains(secret))
        assertFalse(copy.contains("transcript"))
        assertTrue(copy.contains("SUBTITLE_SOURCE_FINAL"))
        assertTrue(copy.contains("ERROR code=AZURE_FAILURE"))
    }
}
