package com.verba.interpretation.ui.facetoface

import androidx.compose.material.icons.Icons
import androidx.compose.foundation.Canvas
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.verba.interpretation.ui.FaceToFaceMode
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceState
import androidx.compose.ui.unit.dp

@Composable
internal fun FaceToFaceOverflowMenu(
    state: FaceToFaceState,
    onSelectMode: (FaceToFaceMode) -> Unit,
    onStopAuto: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(
        onClick = { expanded = true },
        modifier = Modifier.semantics { contentDescription = "面对面翻译更多选项" },
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier.size(44.dp),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            // Canvas draw lambdas are not composable, so resolve the dot color first.
            val dotColor = MaterialTheme.colorScheme.onSecondaryContainer
            Canvas(Modifier.size(22.dp)) {
                val radius = 2.dp.toPx()
                val y = size.height / 2f
                drawCircle(dotColor, radius, androidx.compose.ui.geometry.Offset(size.width * 0.2f, y))
                drawCircle(dotColor, radius, androidx.compose.ui.geometry.Offset(size.width * 0.5f, y))
                drawCircle(dotColor, radius, androidx.compose.ui.geometry.Offset(size.width * 0.8f, y))
            }
        }
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(19.dp)),
    ) {
        DropdownMenuItem(
            text = { Text(if (state.mode == FaceToFaceMode.MANUAL) "✓  按住说话模式" else "按住说话模式", color = if (state.mode == FaceToFaceMode.MANUAL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
            onClick = { onSelectMode(FaceToFaceMode.MANUAL); expanded = false },
            enabled = state.phase == FaceToFacePhase.IDLE,
        )
        DropdownMenuItem(
            text = { Text(if (state.mode == FaceToFaceMode.AUTO) "✓  连续翻译模式" else "连续翻译模式", color = if (state.mode == FaceToFaceMode.AUTO) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
            onClick = { onSelectMode(FaceToFaceMode.AUTO); expanded = false },
            enabled = state.phase == FaceToFacePhase.IDLE,
        )
        if (state.mode == FaceToFaceMode.AUTO && state.phase in setOf(FaceToFacePhase.LISTENING, FaceToFacePhase.PAUSED)) {
            // 主操作（播放/暂停）在双麦之间；结束是次要会话操作，保留在更多菜单。
            DropdownMenuItem(text = { Text("结束连续翻译") }, onClick = { onStopAuto(); expanded = false })
        }
    }
}
