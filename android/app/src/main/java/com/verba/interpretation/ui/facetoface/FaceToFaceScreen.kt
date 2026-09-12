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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.stateDescription
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
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TranslationTopBar(
            title = if (state.view == FaceToFaceView.FACE_TO_FACE) "面对面" else "对话",
            state = state,
            onSelectView = { view ->
                clearMicrophoneRequest()
                viewModel.setView(view)
            },
            onSelectMode = { mode -> clearMicrophoneRequest(); viewModel.setMode(mode) },
            onStartAuto = {
                requestMicrophone(
                    if (state.mode == FaceToFaceMode.MANUAL) {
                        MicrophonePermissionAction.ContinuousEnable
                    } else {
                        MicrophonePermissionAction.ContinuousStart
                    },
                )
            },
            onPauseAuto = viewModel::pauseAuto,
            onResumeAuto = { requestMicrophone(MicrophonePermissionAction.ContinuousResume) },
            onStopAuto = { clearMicrophoneRequest(); viewModel.stopAuto() },
        )

        recoveryLabel(presentation)?.let { message ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
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
    onSelectView: (FaceToFaceView) -> Unit,
    onSelectMode: (FaceToFaceMode) -> Unit,
    onStartAuto: () -> Unit,
    onPauseAuto: () -> Unit,
    onResumeAuto: () -> Unit,
    onStopAuto: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().height(TranslationVisualTokens.TopBarHeight).padding(horizontal = 16.dp),
    ) {
        FaceToFaceViewMenu(
            selectedView = state.view,
            enabled = state.phase != FaceToFacePhase.PROCESSING && state.phase != FaceToFacePhase.STOPPING && state.phase != FaceToFacePhase.ERROR,
            onSelectView = onSelectView,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() }, textAlign = TextAlign.Center)
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            FaceToFaceOverflowMenu(
                state = state,
                onSelectMode = onSelectMode,
                onStopAuto = onStopAuto,
                onStartAutomaticMode = onStartAuto,
            )
        }
    }
}

@Composable
private fun FaceToFaceViewMenu(
    selectedView: FaceToFaceView,
    enabled: Boolean,
    onSelectView: (FaceToFaceView) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(modifier) {
        Surface(
            modifier = Modifier
                .width(64.dp)
                .height(48.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                .clickable(enabled = enabled, role = Role.Button) { expanded = true }
                .semantics {
                    testTag = "face-view-menu"
                    contentDescription = "选择视图"
                    stateDescription = if (selectedView == FaceToFaceView.CONVERSATION) "已选对话视图" else "已选面对面视图"
                },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) { Box(contentAlignment = Alignment.Center) { Text("视图", color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 16.sp) } }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FaceToFaceView.entries.forEach { view ->
                val selected = view == selectedView
                DropdownMenuItem(
                    text = { Text(if (selected) "✓  ${faceViewLabel(view)}" else faceViewLabel(view)) },
                    onClick = { onSelectView(view); expanded = false },
                    modifier = Modifier.semantics {
                        testTag = "face-view-option-${view.name.lowercase()}"
                        contentDescription = faceViewLabel(view)
                        stateDescription = if (selected) "已选中" else "未选中"
                    },
                )
            }
        }
    }
}

private fun faceViewLabel(view: FaceToFaceView): String = when (view) {
    FaceToFaceView.CONVERSATION -> "对话视图"
    FaceToFaceView.FACE_TO_FACE -> "面对面视图"
}

@Composable
private fun HorizontalMoreIcon() {
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
        Box(Modifier.fillMaxWidth().padding(horizontal = 49.dp).height(1.dp).background(MaterialTheme.colorScheme.outline))
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
    val far = position == FaceToFacePanelPosition.FAR
    val side = if (far) FaceToFaceSide.RIGHT else FaceToFaceSide.LEFT
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                testTag = "face-to-face-panel-${position.name.lowercase()}"
                contentDescription = if (far) "远端右耳阅读区和麦克风，文字倒向对方，滑动方向自然" else "近端左耳阅读区和麦克风，正向"
            },
    ) {
        ConversationTimeline(
            turns = emptyList(),
            activeMic = presentation.activeMic,
            listeningPlaceholder = presentation.timelinePlaceholder,
            phase = state.phase,
            activeTurnId = state.activeTurnId,
            contentDescription = "${earLabel(side)}对话记录",
            visualSpec = ConversationTimelineVisualSpec.Face,
            displayBubbles = faceToFacePanelBubbles(state, side),
            bubbleModifier = if (far) Modifier.graphicsLayer { rotationZ = 180f } else Modifier,
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
            modifier = (if (far) Modifier.graphicsLayer { rotationZ = 180f } else Modifier)
                .semantics { testTag = "face-to-face-mic-${position.name.lowercase()}" },
            visibleSides = setOf(side),
            showAutoControls = false,
        )
    }
}
