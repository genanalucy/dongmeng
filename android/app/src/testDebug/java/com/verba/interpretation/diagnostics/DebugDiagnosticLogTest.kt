package com.verba.interpretation.diagnostics

import com.verba.interpretation.protocol.AgentEvent
import com.verba.interpretation.ui.facetoface.ExportDiagnosticLogContentDescription
import com.verba.interpretation.ui.facetoface.diagnosticLogExportFilename
import com.verba.interpretation.ui.facetoface.writeDiagnosticLogExport
import java.io.ByteArrayOutputStream
import java.io.IOException
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

    @Test fun exportWritesTheSafeRenderedSnapshot() {
        val secret = "Bearer token=very-secret transcript"
        val logger = DebugDiagnosticLog()
        logger.agentEvent(AgentEvent.Subtitle(AgentEvent.Subtitle.Kind.SOURCE_FINAL, secret))
        val snapshot = logger.render()
        val output = ByteArrayOutputStream()

        val result = writeDiagnosticLogExport({ output }, snapshot)

        assertTrue(result.isSuccess)
        assertEquals(snapshot, output.toString(Charsets.UTF_8.name()))
        assertFalse(output.toString(Charsets.UTF_8.name()).contains(secret))
    }

    @Test fun exportReportsOutputStreamFailure() {
        val result = writeDiagnosticLogExport({ throw IOException("unavailable") }, "safe snapshot")

        assertTrue(result.isFailure)
    }

    @Test fun exportUsesSafeFilenameAndAccessibleLabel() {
        assertEquals("verba-debug-diagnostics-19700101-000000.txt", diagnosticLogExportFilename(0L))
        assertEquals("导出诊断日志", ExportDiagnosticLogContentDescription)
    }
}
