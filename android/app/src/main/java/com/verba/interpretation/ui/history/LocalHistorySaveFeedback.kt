package com.verba.interpretation.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.verba.interpretation.history.LocalHistorySaveState
import com.verba.interpretation.history.LocalHistorySaveStatus

/** Fixed safe copy for the failure notice; never derived from errorCode or the raw error. */
internal const val LOCAL_HISTORY_SAVE_FAILED_TEXT = "部分文字未能保存到本机"

/** Compact status of the device-local transaction, never a cloud-sync claim. */
internal data class LocalHistorySaveFeedbackMessage(val text: String)

/** Fixed status copy never includes provider errors or private document contents. */
internal fun LocalHistorySaveState.visibleFeedback(): LocalHistorySaveFeedbackMessage? = when (status) {
    LocalHistorySaveStatus.IDLE -> message?.let(::LocalHistorySaveFeedbackMessage)
    LocalHistorySaveStatus.SAVING -> LocalHistorySaveFeedbackMessage("正在保存到本机…")
    LocalHistorySaveStatus.SAVED -> LocalHistorySaveFeedbackMessage("已保存到本机")
    LocalHistorySaveStatus.FAILED -> LocalHistorySaveFeedbackMessage(LOCAL_HISTORY_SAVE_FAILED_TEXT)
}

/** Render one stable status rather than per-turn notifications. */
@Composable
internal fun LocalHistorySaveFeedback(
    state: LocalHistorySaveState,
    canOpenHistory: Boolean,
    onViewHistory: (String) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
) {
    val feedback = remember(state) { state.visibleFeedback() } ?: return
    Surface(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                if (state.status == LocalHistorySaveStatus.FAILED) liveRegion = LiveRegionMode.Polite
            },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(
            feedback.text,
            style = MaterialTheme.typography.labelMedium,
            color = if (state.status == LocalHistorySaveStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        if (state.status == LocalHistorySaveStatus.FAILED) {
            androidx.compose.material3.TextButton(onClick = onRetry) { Text("重试保存") }
        } else if (state.status == LocalHistorySaveStatus.SAVED && canOpenHistory) {
            state.sessionId?.let { sessionId -> androidx.compose.material3.TextButton(onClick = { onViewHistory(sessionId) }) { Text("查看历史") } }
        }
        }
    }
}
