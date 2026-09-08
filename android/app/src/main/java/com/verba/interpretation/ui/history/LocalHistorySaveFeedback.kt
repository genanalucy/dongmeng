package com.verba.interpretation.ui.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.verba.interpretation.history.LocalHistorySaveState
import com.verba.interpretation.history.LocalHistorySaveStatus
import kotlinx.coroutines.delay

/** Save feedback is event-driven; IDLE deliberately renders no persistent footer. */
@Composable
internal fun LocalHistorySaveFeedback(
    state: LocalHistorySaveState,
    canOpenHistory: Boolean,
    onViewHistory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSaved by remember { mutableStateOf(false) }
    LaunchedEffect(state.status, state.sessionId) {
        showSaved = state.status != LocalHistorySaveStatus.SAVED
        if (state.status == LocalHistorySaveStatus.SAVED) {
            showSaved = true
            delay(SAVED_FEEDBACK_MILLIS)
            showSaved = false
        }
    }
    if (state.status == LocalHistorySaveStatus.SAVED && !showSaved) return
    val message = when (state.status) {
        LocalHistorySaveStatus.IDLE -> null
        LocalHistorySaveStatus.SAVING -> "正在保存到本机…"
        LocalHistorySaveStatus.SAVED -> "已保存到本机"
        LocalHistorySaveStatus.FAILED -> "部分文字未能保存到本机"
    } ?: return
    Surface(
        modifier = modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            message,
            style = MaterialTheme.typography.labelMedium,
            color = if (state.status == LocalHistorySaveStatus.FAILED) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        val sessionId = state.sessionId
        if (state.status == LocalHistorySaveStatus.SAVED && sessionId != null) {
            TextButton(
                onClick = { onViewHistory(sessionId) },
                enabled = canOpenHistory,
            ) { Text("查看本次记录") }
        }
    }
    }
}

private const val SAVED_FEEDBACK_MILLIS = 3_000L
