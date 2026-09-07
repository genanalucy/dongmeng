package com.verba.interpretation.ui.facetoface

import androidx.compose.material.icons.Icons
import androidx.compose.foundation.Canvas
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import com.verba.interpretation.ui.design.VerbaColors
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
    onStartAuto: () -> Unit,
    onPauseAuto: () -> Unit,
    onResumeAuto: () -> Unit,
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
            color = VerbaColors.TopControl,
            border = androidx.compose.foundation.BorderStroke(1.dp, VerbaColors.ShellStroke),
        ) {
            Canvas(Modifier.size(22.dp)) {
                val radius = 2.dp.toPx()
                val y = size.height / 2f
                drawCircle(VerbaColors.Ink, radius, androidx.compose.ui.geometry.Offset(size.width * 0.2f, y))
                drawCircle(VerbaColors.Ink, radius, androidx.compose.ui.geometry.Offset(size.width * 0.5f, y))
                drawCircle(VerbaColors.Ink, radius, androidx.compose.ui.geometry.Offset(size.width * 0.8f, y))
            }
        }
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        modifier = Modifier.background(VerbaColors.History, RoundedCornerShape(19.dp)),
    ) {
        DropdownMenuItem(
            text = { Text(if (state.mode == FaceToFaceMode.MANUAL) "✓  按住说话模式" else "按住说话模式", color = if (state.mode == FaceToFaceMode.MANUAL) VerbaColors.Translation else VerbaColors.Ink) },
            onClick = { onSelectMode(FaceToFaceMode.MANUAL); expanded = false },
            enabled = state.phase == FaceToFacePhase.IDLE,
        )
        DropdownMenuItem(
            text = { Text(if (state.mode == FaceToFaceMode.AUTO) "✓  连续翻译模式" else "连续翻译模式", color = if (state.mode == FaceToFaceMode.AUTO) VerbaColors.Translation else VerbaColors.Ink) },
            onClick = { onSelectMode(FaceToFaceMode.AUTO); expanded = false },
            enabled = state.phase == FaceToFacePhase.IDLE,
        )
        if (state.mode == FaceToFaceMode.AUTO) {
            when (state.phase) {
                FaceToFacePhase.IDLE -> DropdownMenuItem(text = { Text("开始连续翻译") }, onClick = { onStartAuto(); expanded = false })
                FaceToFacePhase.LISTENING -> {
                    DropdownMenuItem(text = { Text("暂停连续翻译") }, onClick = { onPauseAuto(); expanded = false })
                    DropdownMenuItem(text = { Text("结束连续翻译") }, onClick = { onStopAuto(); expanded = false })
                }
                FaceToFacePhase.PAUSED -> {
                    DropdownMenuItem(text = { Text("恢复连续翻译") }, onClick = { onResumeAuto(); expanded = false })
                    DropdownMenuItem(text = { Text("结束连续翻译") }, onClick = { onStopAuto(); expanded = false })
                }
                else -> Unit
            }
        }
    }
}
