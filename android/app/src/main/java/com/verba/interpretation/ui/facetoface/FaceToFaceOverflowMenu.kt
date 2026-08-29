package com.verba.interpretation.ui.facetoface

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.verba.interpretation.ui.FaceToFaceMode
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceState

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
    ) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("按住说话") },
            onClick = { onSelectMode(FaceToFaceMode.MANUAL); expanded = false },
            enabled = state.phase == FaceToFacePhase.IDLE,
        )
        DropdownMenuItem(
            text = { Text("连续翻译") },
            onClick = { onSelectMode(FaceToFaceMode.AUTO); expanded = false },
            enabled = state.phase == FaceToFacePhase.IDLE,
        )
        if (state.mode == FaceToFaceMode.AUTO) {
            when (state.phase) {
                FaceToFacePhase.IDLE -> DropdownMenuItem(text = { Text("开始连续翻译") }, onClick = { onStartAuto(); expanded = false })
                FaceToFacePhase.LISTENING -> {
                    DropdownMenuItem(text = { Text("暂停连续翻译") }, onClick = { onPauseAuto(); expanded = false })
                    DropdownMenuItem(text = { Text("停止连续翻译") }, onClick = { onStopAuto(); expanded = false })
                }
                FaceToFacePhase.PAUSED -> {
                    DropdownMenuItem(text = { Text("继续连续翻译") }, onClick = { onResumeAuto(); expanded = false })
                    DropdownMenuItem(text = { Text("停止连续翻译") }, onClick = { onStopAuto(); expanded = false })
                }
                else -> Unit
            }
        }
    }
}
