package com.verba.interpretation.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.verba.interpretation.ui.AccountUiState
import com.verba.interpretation.ui.FaceToFaceState
import com.verba.interpretation.ui.FaceToFaceView
import com.verba.interpretation.ui.InterpretationUiState
import kotlinx.coroutines.flow.StateFlow

/** Entire transport blocks, never guessed sentence-by-sentence pairing. */
internal fun InterpretationUiState.exportSnapshot(nowMillis: Long = System.currentTimeMillis()) =
    ExportSnapshot(nowMillis, "interpretation", turns.map { turn ->
        ExportEntry(
            turn.sourceLanguage, turn.targetLanguage, turn.sourceText, turn.translatedText,
            completed = completeBlock(turn.finished, turn.sourceFinals, turn.translationFinals,
                turn.sourcePartial, turn.translationPartial),
        )
    })

internal fun FaceToFaceState.exportSnapshot(nowMillis: Long = System.currentTimeMillis()) =
    ExportSnapshot(nowMillis, if (view == FaceToFaceView.CONVERSATION) "conversation" else "face_to_face", turns.map { turn ->
        ExportEntry(
            turn.sourceLanguage, turn.targetLanguage, turn.sourceText, turn.translatedText,
            completed = completeBlock(turn.finished, turn.sourceFinals, turn.translationFinals,
                turn.sourcePartial, turn.translationPartial),
            side = turn.side.name,
        )
    })

private fun completeBlock(
    finished: Boolean,
    sourceFinals: List<String>,
    translationFinals: List<String>,
    sourcePartial: String,
    translationPartial: String,
): Boolean = finished && sourcePartial.isEmpty() && translationPartial.isEmpty() &&
    sourceFinals.isNotEmpty() && sourceFinals.size == translationFinals.size &&
    sourceFinals.all { it.isNotBlank() } && translationFinals.all { it.isNotBlank() }

/** Call only inside Debug menus; callbacks have no default side effects. */
@Composable
internal fun ConversationExportMenuItems(
    onSaveToHistory: () -> Unit,
    onRetryHistorySave: () -> Unit,
    onExport: (ExportFormat) -> Unit,
    busy: Boolean,
    onDismiss: () -> Unit,
) {
    HorizontalDivider()
    DropdownMenuItem(text = { Text("保存到历史") }, onClick = { onDismiss(); onSaveToHistory() })
    DropdownMenuItem(text = { Text("重试历史保存") }, onClick = { onDismiss(); onRetryHistorySave() })
    ExportFormat.entries.forEach { format ->
        DropdownMenuItem(
            text = { Text(if (busy) "导出处理中…" else "导出 ${format.name}（明文）") },
            enabled = !busy,
            onClick = { onDismiss(); onExport(format) },
        )
    }
}

internal class ConversationExportAction(
    val busy: Boolean,
    val export: (ExportSnapshot, ExportFormat) -> Unit,
    val invalidateAccount: () -> Unit,
)

/** Register at the root, outside navigation branches. Never put text into SavedState/Bundle. */
@Composable
internal fun rememberConversationExportAction(account: StateFlow<AccountUiState>): ConversationExportAction {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val controller = remember { DocumentExportController() }
    val state by controller.state.collectAsState()
    var confirmation by remember { mutableStateOf<DocumentExportController.Request?>(null) }
    // Not lifecycle-gated: an account transition while SAF covers the app still invalidates ownership.
    LaunchedEffect(account, controller) {
        account.collect { value ->
            controller.updateOwner(value.user?.id)
            confirmation?.takeUnless(controller::canLaunch)?.let {
                confirmation = null
                controller.documentResult(null)
            }
        }
    }
    val txt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        controller.updateOwner(account.value.user?.id)
        controller.documentResult(uri?.let { { resolver.openOutputStream(it, "wt") } })
    }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        controller.updateOwner(account.value.user?.id)
        controller.documentResult(uri?.let { { resolver.openOutputStream(it, "wt") } })
    }
    fun launch(request: DocumentExportController.Request) {
        if (!controller.canLaunch(request)) {
            controller.documentResult(null)
            return
        }
        try {
            when (request.format) {
                ExportFormat.TXT -> txt.launch(request.snapshot.fileName(request.format))
                ExportFormat.JSON -> json.launch(request.snapshot.fileName(request.format))
            }
        } catch (_: Exception) {
            controller.pickerLaunchFailed()
        }
    }
    confirmation?.let { request ->
        AlertDialog(
            onDismissRequest = { confirmation = null; controller.documentResult(null) },
            title = { Text("导出 ${request.format.name} 明文文件？") },
            text = { Column {
                Text("文件包含当前原文与译文，未经加密；所选位置可能由云端文档服务同步。请仅保存到可信位置。不会自动分享。")
                Text("导出的是点选时的快照，后续字幕不会加入；未完成内容会明确标记。离开应用可能停止翻译。")
            } },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                controller.updateOwner(account.value.user?.id)
                launch(request)
            }) { Text("选择保存位置") } },
            dismissButton = { TextButton(onClick = {
                confirmation = null
                controller.documentResult(null)
            }) { Text("取消") } },
        )
    }
    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = controller::dismissMessage,
            title = { Text("会话导出") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = controller::dismissMessage) { Text("知道了") } },
            dismissButton = if (state.canRetry) ({
                TextButton(onClick = {
                    controller.updateOwner(account.value.user?.id)
                    controller.retryPicker()?.let(::launch)
                }) { Text("重试原快照") }
            }) else null,
        )
    }
    return ConversationExportAction(
        busy = state.busy,
        export = { snapshot, format ->
            controller.updateOwner(account.value.user?.id)
            controller.prepare(snapshot, format)?.let { confirmation = it }
        },
        invalidateAccount = {
            if (confirmation != null) controller.documentResult(null)
            confirmation = null
            controller.invalidateAccount()
        },
    )
}
