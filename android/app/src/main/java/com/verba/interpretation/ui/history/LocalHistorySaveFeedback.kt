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

/** The only renderable local-save feedback: a persistent, non-blocking failure notice. */
internal data class LocalHistorySaveFeedbackMessage(val text: String)

/**
 * Successful and in-flight saves are silent by design: finishing a translation must not raise a
 * per-turn "加入历史记录" notice. Only FAILED produces renderable feedback, with copy fixed to
 * [LOCAL_HISTORY_SAVE_FAILED_TEXT] so no error detail can leak to the UI.
 */
internal fun LocalHistorySaveState.visibleFeedback(): LocalHistorySaveFeedbackMessage? = when (status) {
    LocalHistorySaveStatus.IDLE,
    LocalHistorySaveStatus.SAVING,
    LocalHistorySaveStatus.SAVED,
    -> null
    LocalHistorySaveStatus.FAILED -> LocalHistorySaveFeedbackMessage(LOCAL_HISTORY_SAVE_FAILED_TEXT)
}

/**
 * Renders nothing while saves succeed. [canOpenHistory] and [onViewHistory] are kept so existing
 * call sites (solo and face screens) do not change; success no longer renders a view action.
 */
@Composable
internal fun LocalHistorySaveFeedback(
    state: LocalHistorySaveState,
    canOpenHistory: Boolean,
    onViewHistory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val feedback = remember(state) { state.visibleFeedback() } ?: return
    Surface(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            feedback.text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
