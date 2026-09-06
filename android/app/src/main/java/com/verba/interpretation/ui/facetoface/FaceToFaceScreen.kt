package com.verba.interpretation.ui.facetoface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verba.interpretation.ui.FaceToFaceMode
import com.verba.interpretation.ui.MicrophonePermissionAction
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceState
import com.verba.interpretation.ui.FaceToFaceViewModel
import com.verba.interpretation.ui.TranslationLanguage

private val conversationCanvas = Color(0xFF07111F)

private fun faceStatusLabel(state: FaceToFaceState): String = when (state.phase) {
    FaceToFacePhase.IDLE -> "按住对应麦克风开始"
    FaceToFacePhase.LISTENING -> "${state.activeSide?.let(::earLabel) ?: "麦克风"}正在收音"
    FaceToFacePhase.PAUSED -> "连续翻译已暂停"
    FaceToFacePhase.PROCESSING -> "正在翻译，暂不可操作"
    FaceToFacePhase.STOPPING -> "正在结束，暂不可操作"
    FaceToFacePhase.ERROR -> "会话已停止"
}

@Composable
internal fun FaceToFaceScreen(
    state: FaceToFaceState,
    viewModel: FaceToFaceViewModel,
    requestMicrophone: (MicrophonePermissionAction) -> Unit,
    clearMicrophoneRequest: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val presentation = faceToFacePresentation(state)
    Column(
        modifier.fillMaxSize().background(conversationCanvas),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, top = 14.dp, end = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "对话",
                    color = Color(0xFFF5F5F2),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    faceStatusLabel(state),
                    color = if (state.phase == FaceToFacePhase.ERROR) Color(0xFFFF9B9B) else Color(0xFFABB5C3),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.semantics {
                        contentDescription = "phase=${state.phase.name}，${faceStatusLabel(state)}"
                    },
                )
            }
            FaceToFaceOverflowMenu(
                state = state,
                onSelectMode = viewModel::setMode,
                onStartAuto = { requestMicrophone(MicrophonePermissionAction.Continuous) },
                onPauseAuto = viewModel::pauseAuto,
                onResumeAuto = viewModel::resumeAuto,
                onStopAuto = viewModel::stopAuto,
            )
        }

        ConversationTimeline(
            turns = state.turns,
            activeMic = presentation.activeMic,
            listeningPlaceholder = presentation.timelinePlaceholder,
            phase = state.phase,
            activeTurnId = state.activeTurnId,
            modifier = Modifier.weight(1f),
        )

        presentation.recoveryMessage?.let { message ->
            Surface(
                color = Color(0xFF35242A),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, color = Color(0xFFFFDAD6), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Button(onClick = viewModel::cancel, modifier = Modifier.heightIn(min = 44.dp)) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(FACE_TO_FACE_RECOVERY_ACTION_LABEL)
                    }
                }
            }
        }

        Surface(
            color = Color(0xFF0E1927),
            modifier = Modifier.fillMaxWidth(),
        ) {
            EarMicControls(
                state = state,
                presentation = presentation,
                requestMicrophone = requestMicrophone,
                onManualPress = viewModel::manualPress,
                onManualRelease = { clearMicrophoneRequest(); viewModel.manualRelease() },
                onManualCancel = { clearMicrophoneRequest(); viewModel.manualCancel() },
                onStartAuto = viewModel::startAuto,
                onPressRightAuto = viewModel::pressRightAuto,
                onReleaseRightAuto = viewModel::releaseRightAuto,
                onPauseAuto = viewModel::pauseAuto,
                onResumeAuto = viewModel::resumeAuto,
                onStopAuto = viewModel::stopAuto,
                onSetLanguages = viewModel::setLanguages,
            )
        }
    }
}
