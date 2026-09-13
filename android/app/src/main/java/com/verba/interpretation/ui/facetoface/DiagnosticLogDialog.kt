package com.verba.interpretation.ui.facetoface

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.verba.interpretation.diagnostics.DiagnosticEntry
import com.verba.interpretation.diagnostics.DiagnosticLogger

@Composable
internal fun DiagnosticLogDialog(logger: DiagnosticLogger, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val entries = logger.entries()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("诊断日志") },
        text = { Text(renderDiagnosticLog(entries), fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).horizontalScroll(rememberScrollState()).semantics { contentDescription = "安全诊断日志内容" }) },
        confirmButton = { Button(onClick = { clipboard.setText(AnnotatedString(renderDiagnosticLog(entries))) }, modifier = Modifier.semantics { contentDescription = "复制诊断日志" }) { Text("复制") } },
        dismissButton = {
            OutlinedButton(onClick = { logger.clear() }, modifier = Modifier.padding(end = 8.dp).semantics { contentDescription = "清空诊断日志" }) { Text("清空") }
            OutlinedButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

internal fun renderDiagnosticLog(entries: List<DiagnosticEntry>): String = entries.joinToString(
    separator = "\n",
    prefix = "Verba Debug 诊断日志（安全元数据）\n",
) { "+${it.elapsedMillis}ms ${it.clockTime} [${it.category}] ${it.message}" }
