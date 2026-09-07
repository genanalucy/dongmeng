package com.verba.interpretation.ui.facetoface

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.sp
import com.verba.interpretation.ui.FaceToFaceMode
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceState
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceView
import com.verba.interpretation.ui.FaceToFaceViewModel
import com.verba.interpretation.ui.MicrophonePermissionAction
import com.verba.interpretation.ui.design.ConversationTimelineVisualSpec
import com.verba.interpretation.ui.design.TranslationVisualTokens
import com.verba.interpretation.ui.design.VerbaColors

private fun recoveryLabel(presentation: FaceToFacePresentation): String? = presentation.recoveryMessage

@Composable
internal fun FaceToFaceScreen(
    state: FaceToFaceState,
    viewModel: FaceToFaceViewModel,
    requestMicrophone: (MicrophonePermissionAction) -> Unit,
    clearMicrophoneRequest: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val presentation = faceToFacePresentation(state)
    Column(modifier.fillMaxSize().background(VerbaColors.Canvas)) {
        TranslationTopBar(
            title = if (state.view == FaceToFaceView.FACE_TO_FACE) "面对面" else "对话",
            state = state,
            onToggleView = {
                clearMicrophoneRequest()
                viewModel.setView(if (state.view == FaceToFaceView.CONVERSATION) FaceToFaceView.FACE_TO_FACE else FaceToFaceView.CONVERSATION)
            },
            onSelectMode = { mode -> clearMicrophoneRequest(); viewModel.setMode(mode) },
            onStartAuto = { requestMicrophone(MicrophonePermissionAction.ContinuousStart) },
            onPauseAuto = viewModel::pauseAuto,
            onResumeAuto = { requestMicrophone(MicrophonePermissionAction.ContinuousResume) },
            onStopAuto = { clearMicrophoneRequest(); viewModel.stopAuto() },
        )

        recoveryLabel(presentation)?.let { message ->
            Surface(
                color = VerbaColors.ErrorSurface,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, color = VerbaColors.Danger, modifier = Modifier.weight(1f))
                    Button(
                        onClick = { clearMicrophoneRequest(); viewModel.cancel() },
                        modifier = Modifier.semantics { contentDescription = FACE_TO_FACE_RECOVERY_ACTION_LABEL },
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Text(FACE_TO_FACE_RECOVERY_ACTION_LABEL, modifier = Modifier.padding(start = 5.dp))
                    }
                }
            }
        }

        if (state.view == FaceToFaceView.CONVERSATION) {
            ConversationLayout(
                state = state,
                presentation = presentation,
                requestMicrophone = requestMicrophone,
                clearMicrophoneRequest = clearMicrophoneRequest,
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )
        } else {
            FaceToFacePanels(
                state = state,
                presentation = presentation,
                requestMicrophone = requestMicrophone,
                clearMicrophoneRequest = clearMicrophoneRequest,
                viewModel = viewModel,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TranslationTopBar(
    title: String,
    state: FaceToFaceState,
    onToggleView: () -> Unit,
    onSelectMode: (FaceToFaceMode) -> Unit,
    onStartAuto: () -> Unit,
    onPauseAuto: () -> Unit,
    onResumeAuto: () -> Unit,
    onStopAuto: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().height(TranslationVisualTokens.TopBarHeight).padding(horizontal = 16.dp),
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(64.dp)
                .height(44.dp)
                .clickable(
                    enabled = state.phase != FaceToFacePhase.PROCESSING && state.phase != FaceToFacePhase.STOPPING && state.phase != FaceToFacePhase.ERROR,
                    role = Role.Button,
                    onClick = onToggleView,
                )
                .semantics {
                    testTag = "face-view-toggle"
                    contentDescription = if (state.view == FaceToFaceView.CONVERSATION) "切换到面对面布局" else "切换到对话布局"
                },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            color = VerbaColors.TopControl,
            border = androidx.compose.foundation.BorderStroke(1.dp, VerbaColors.ShellStroke),
        ) { Box(contentAlignment = Alignment.Center) { Text("视图", color = VerbaColors.Ink, fontSize = 16.sp) } }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(title, color = VerbaColors.Ink, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() }, textAlign = TextAlign.Center)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            FaceToFaceOverflowMenu(
                state = state,
                onSelectMode = onSelectMode,
                onStartAuto = onStartAuto,
                onPauseAuto = onPauseAuto,
                onResumeAuto = onResumeAuto,
                onStopAuto = onStopAuto,
            )
        }
    }
}

@Composable
private fun HorizontalMoreIcon() {
    Canvas(Modifier.size(22.dp)) {
        val radius = 2.dp.toPx()
        val y = size.height / 2f
        drawCircle(VerbaColors.Ink, radius, androidx.compose.ui.geometry.Offset(size.width * 0.2f, y))
        drawCircle(VerbaColors.Ink, radius, androidx.compose.ui.geometry.Offset(size.width * 0.5f, y))
        drawCircle(VerbaColors.Ink, radius, androidx.compose.ui.geometry.Offset(size.width * 0.8f, y))
    }
}

@Composable
private fun ConversationLayout(
    state: FaceToFaceState,
    presentation: FaceToFacePresentation,
    requestMicrophone: (MicrophonePermissionAction) -> Unit,
    clearMicrophoneRequest: () -> Unit,
    viewModel: FaceToFaceViewModel,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        ConversationTimeline(
            turns = state.turns,
            activeMic = presentation.activeMic,
            listeningPlaceholder = presentation.timelinePlaceholder,
            phase = state.phase,
            activeTurnId = state.activeTurnId,
            modifier = Modifier.weight(1f),
        )
        EarMicControls(
            state = state,
            presentation = presentation,
            requestMicrophone = requestMicrophone,
            onManualPress = viewModel::manualPress,
            onManualRelease = viewModel::manualRelease,
            onManualCancel = viewModel::manualCancel,
            clearMicrophoneRequest = clearMicrophoneRequest,
            onStartAuto = viewModel::startAuto,
            onPressRightAuto = viewModel::pressRightAuto,
            onReleaseRightAuto = viewModel::releaseRightAuto,
            onCancelRightAuto = viewModel::cancelRightAuto,
            onPauseAuto = viewModel::pauseAuto,
            onResumeAuto = viewModel::resumeAuto,
            onStopAuto = { clearMicrophoneRequest(); viewModel.stopAuto() },
            onSetLanguages = viewModel::setLanguages,
        )
    }
}

@Composable
private fun FaceToFacePanels(
    state: FaceToFaceState,
    presentation: FaceToFacePresentation,
    requestMicrophone: (MicrophonePermissionAction) -> Unit,
    clearMicrophoneRequest: () -> Unit,
    viewModel: FaceToFaceViewModel,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().semantics { testTag = "face-to-face-panels" },
    ) {
        FaceReadingHalf(
            state = state,
            presentation = presentation,
            position = FaceToFacePanelPosition.FAR,
            requestMicrophone = requestMicrophone,
            clearMicrophoneRequest = clearMicrophoneRequest,
            viewModel = viewModel,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.fillMaxWidth().padding(horizontal = 49.dp).height(1.dp).background(VerbaColors.ShellStroke))
        FaceReadingHalf(
            state = state,
            presentation = presentation,
            position = FaceToFacePanelPosition.NEAR,
            requestMicrophone = requestMicrophone,
            clearMicrophoneRequest = clearMicrophoneRequest,
            viewModel = viewModel,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun FaceReadingHalf(
    state: FaceToFaceState,
    presentation: FaceToFacePresentation,
    position: FaceToFacePanelPosition,
    requestMicrophone: (MicrophonePermissionAction) -> Unit,
    clearMicrophoneRequest: () -> Unit,
    viewModel: FaceToFaceViewModel,
    modifier: Modifier,
) {
    val rotated = position == FaceToFacePanelPosition.FAR
    val side = if (rotated) FaceToFaceSide.RIGHT else FaceToFaceSide.LEFT
    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                rotationZ = if (rotated) 180f else 0f
                clip = true
            }
            .semantics {
                testTag = "face-to-face-panel-${position.name.lowercase()}"
                contentDescription = if (rotated) "远端右耳阅读区和麦克风，旋转180度" else "近端左耳阅读区和麦克风，正向"
            },
    ) {
        ConversationTimeline(
            turns = faceToFacePanelTurns(state, side),
            activeMic = presentation.activeMic,
            listeningPlaceholder = presentation.timelinePlaceholder,
            phase = state.phase,
            activeTurnId = state.activeTurnId,
            contentDescription = "${earLabel(side)}对话记录",
            visualSpec = ConversationTimelineVisualSpec.Face,
            modifier = Modifier.weight(1f),
        )
        EarMicControls(
            state = state,
            presentation = presentation,
            requestMicrophone = requestMicrophone,
            onManualPress = viewModel::manualPress,
            onManualRelease = viewModel::manualRelease,
            onManualCancel = viewModel::manualCancel,
            clearMicrophoneRequest = clearMicrophoneRequest,
            onStartAuto = viewModel::startAuto,
            onPressRightAuto = viewModel::pressRightAuto,
            onReleaseRightAuto = viewModel::releaseRightAuto,
            onCancelRightAuto = viewModel::cancelRightAuto,
            onPauseAuto = viewModel::pauseAuto,
            onResumeAuto = viewModel::resumeAuto,
            onStopAuto = { clearMicrophoneRequest(); viewModel.stopAuto() },
            onSetLanguages = viewModel::setLanguages,
            modifier = Modifier.semantics { testTag = "face-to-face-mic-${position.name.lowercase()}" },
            visibleSides = setOf(side),
            showAutoControls = false,
        )
    }
}
