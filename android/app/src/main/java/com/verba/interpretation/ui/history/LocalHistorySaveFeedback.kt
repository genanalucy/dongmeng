package com.verba.interpretation.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verba.interpretation.history.LocalHistorySaveState
import com.verba.interpretation.history.LocalHistorySaveStatus

/** Device-local persistence only: this component makes no cloud synchronization claim. */
@Composable
internal fun LocalHistorySaveFeedback(
    state: LocalHistorySaveState,
    canOpenHistory: Boolean,
    onViewHistory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = when (state.status) {
        LocalHistorySaveStatus.IDLE -> "完成的文字自动保存，不保存音频"
        LocalHistorySaveStatus.SAVING -> "正在保存到本机…"
        LocalHistorySaveStatus.SAVED -> "已保存到本机"
        LocalHistorySaveStatus.FAILED -> "部分文字未能保存到本机"
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(
            message,
            style = MaterialTheme.typography.labelMedium,
            color = if (state.status == LocalHistorySaveStatus.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val sessionId = state.sessionId
        if (state.status == LocalHistorySaveStatus.SAVED && sessionId != null) {
            TextButton(
                onClick = { onViewHistory(sessionId) },
                enabled = canOpenHistory,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(if (canOpenHistory) "查看本次记录" else "结束翻译后可查看记录") }
        }
    }
}
