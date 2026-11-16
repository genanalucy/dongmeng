package com.verba.interpretation.export

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentExportControllerTest {
    private fun controller() = DocumentExportController(CoroutineScope(Dispatchers.Unconfined)).apply {
        updateOwner("alice")
    }
    private fun snapshot(source: String = "你好") = ExportSnapshot(0, "solo", listOf(
        ExportEntry("zh", "en", source, "Hello", false),
    ))

    @Test fun emptyContentDoesNotLaunchPicker() {
        val controller = controller()
        assertNull(controller.prepare(ExportSnapshot(0, "solo", emptyList()), ExportFormat.TXT))
        assertFalse(controller.state.value.busy)
        assertTrue(controller.state.value.message!!.contains("暂无"))
    }

    @Test fun unsignedAccountCannotExport() {
        val controller = DocumentExportController(CoroutineScope(Dispatchers.Unconfined))
        assertNull(controller.prepare(snapshot(), ExportFormat.JSON))
        assertTrue(controller.state.value.message!!.contains("登录"))
    }

    @Test fun repeatedClickIsIgnoredAndCancellationIsNotFailure() {
        val controller = controller()
        assertNotNull(controller.prepare(snapshot(), ExportFormat.TXT))
        assertNull(controller.prepare(snapshot("new"), ExportFormat.JSON))
        controller.documentResult(null)
        assertFalse(controller.state.value.busy)
        assertNull(controller.state.value.message)
        assertFalse(controller.state.value.canRetry)
    }

    @Test fun closesBeforeReportingSuccessAndWritesUtf8Snapshot() {
        val controller = controller()
        val frozen = snapshot()
        controller.prepare(frozen, ExportFormat.TXT)
        var closed = false
        val stream = object : ByteArrayOutputStream() {
            override fun close() {
                assertTrue(controller.state.value.busy)
                assertNull(controller.state.value.message)
                closed = true
                super.close()
            }
        }
        controller.documentResult { stream }
        assertTrue(closed)
        assertEquals(frozen.render(ExportFormat.TXT), stream.toString("UTF-8"))
        assertTrue(controller.state.value.message!!.contains("成功"))
        assertFalse(controller.state.value.busy)
    }

    @Test fun nullStreamAndCloseFailureKeepOriginalSnapshotForRetry() {
        listOf<() -> OutputStream?>(
            { null },
            { object : ByteArrayOutputStream() {
                override fun close() { throw IOException("private provider path") }
            } },
        ).forEach { open ->
            val controller = controller()
            val frozen = snapshot()
            controller.prepare(frozen, ExportFormat.JSON)
            controller.documentResult(open)
            assertTrue(controller.state.value.canRetry)
            assertFalse(controller.state.value.message!!.contains("private"))
            val request = requireNotNull(controller.retryPicker())
            assertEquals(frozen, request.snapshot)
            val output = ByteArrayOutputStream()
            controller.documentResult { output }
            assertEquals(frozen.render(ExportFormat.JSON), output.toString("UTF-8"))
        }
    }

    @Test fun writeFailureClosesStreamAndDoesNotReportSuccess() {
        val controller = controller()
        controller.prepare(snapshot(), ExportFormat.TXT)
        var closed = false
        controller.documentResult {
            object : OutputStream() {
                override fun write(value: Int) { throw IOException("no space") }
                override fun close() { closed = true }
            }
        }
        assertTrue(closed)
        assertTrue(controller.state.value.canRetry)
        assertFalse(controller.state.value.message!!.contains("成功"))
    }

    @Test fun accountSwitchRejectsOldResultAndDoesNotOpenFile() {
        val controller = controller()
        controller.prepare(snapshot(), ExportFormat.TXT)
        controller.updateOwner("bob")
        // Cannot replace the old picker slot with a new account's request.
        assertNull(controller.prepare(snapshot("bob"), ExportFormat.JSON))
        var opened = false
        controller.documentResult { opened = true; ByteArrayOutputStream() }
        assertFalse(opened)
        assertFalse(controller.state.value.busy)
        assertNotNull(controller.prepare(snapshot("bob"), ExportFormat.JSON))
    }

    @Test fun sameUserLogoutLoginInvalidatesGeneration() {
        val controller = controller()
        controller.prepare(snapshot(), ExportFormat.TXT)
        controller.invalidateAccount()
        controller.updateOwner("alice")
        var opened = false
        controller.documentResult { opened = true; ByteArrayOutputStream() }
        assertFalse(opened)
        assertNull(controller.state.value.message)
    }

    @Test fun recreatedControllerIgnoresRestoredPickerResultWithoutReadingNewState() {
        val before = controller()
        before.prepare(snapshot(), ExportFormat.TXT)
        val recreated = controller()
        var opened = false
        recreated.documentResult { opened = true; ByteArrayOutputStream() }
        assertFalse(opened)
        assertNull(recreated.state.value.message)
    }

    @Test fun accountRevokedWhileOpeningTargetDoesNotWriteAnySnapshotBytes() {
        val controller = controller()
        controller.prepare(snapshot(), ExportFormat.TXT)
        val output = ByteArrayOutputStream()
        controller.documentResult {
            controller.invalidateAccount()
            output
        }
        assertEquals(0, output.size())
        assertFalse(controller.state.value.busy)
        assertNull(controller.state.value.message)
    }

    @Test fun pickerLaunchFailureCanRetryUntilOwnerChanges() {
        val controller = controller()
        controller.prepare(snapshot(), ExportFormat.TXT)
        controller.pickerLaunchFailed()
        assertTrue(controller.state.value.canRetry)
        controller.updateOwner("bob")
        assertNull(controller.retryPicker())
        assertFalse(controller.state.value.canRetry)
    }
}
