package com.verba.interpretation.ui.facetoface

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.verba.interpretation.diagnostics.DiagnosticLogger
import com.verba.interpretation.diagnostics.renderDiagnosticLog
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val ExportDiagnosticLogContentDescription = "导出诊断日志"
private val diagnosticLogFilenameFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

@Composable
internal fun DiagnosticLogDialog(logger: DiagnosticLogger, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var pendingExportSnapshot by remember { mutableStateOf<String?>(null) }
    var exportFeedback by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val snapshot = pendingExportSnapshot
        pendingExportSnapshot = null
        exportFeedback = when {
            uri == null || snapshot == null -> "已取消导出"
            writeDiagnosticLogExport({ context.contentResolver.openOutputStream(uri) }, snapshot).isSuccess -> "诊断日志已导出"
            else -> "导出失败，请重试"
        }
    }
    val renderedLog = renderDiagnosticLog(logger.entries())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("诊断日志") },
        text = {
            Column {
                Text(
                    renderedLog,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).horizontalScroll(rememberScrollState())
                        .semantics { contentDescription = "安全诊断日志内容" },
                )
                exportFeedback?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    pendingExportSnapshot = renderedLog
                    exportFeedback = null
                    exportLauncher.launch(diagnosticLogExportFilename(System.currentTimeMillis()))
                },
                modifier = Modifier.semantics { contentDescription = ExportDiagnosticLogContentDescription },
            ) { Text("导出日志") }
        },
        dismissButton = {
            OutlinedButton(onClick = { logger.clear() }, modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = "清空诊断日志" }) { Text("清空") }
            OutlinedButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

internal fun writeDiagnosticLogExport(openOutputStream: () -> java.io.OutputStream?, snapshot: String): Result<Unit> = runCatching {
    openOutputStream()?.bufferedWriter()?.use { it.write(snapshot) }
        ?: throw IOException("No output stream available")
}

internal fun diagnosticLogExportFilename(timestampMillis: Long): String =
    "verba-debug-diagnostics-${diagnosticLogFilenameFormatter.format(Instant.ofEpochMilli(timestampMillis).atZone(ZoneOffset.UTC))}.txt"
