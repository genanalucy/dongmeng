package com.verba.interpretation.ui.facetoface

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verba.interpretation.ui.FaceToFaceMode
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceState
import com.verba.interpretation.ui.TranslationLanguage

internal class MicPressGate(
    private val onPress: () -> Unit,
    private val onRelease: () -> Unit,
) {
    private var pressed = false

    fun press() {
        if (pressed) return
        pressed = true
        onPress()
    }

    fun release() {
        if (!pressed) return
        pressed = false
        onRelease()
    }

    fun cancel() = release()
}

@Composable
internal fun EarMicControls(
    state: FaceToFaceState,
    presentation: FaceToFacePresentation,
    requestMicrophone: (() -> Unit) -> Unit,
    onManualPress: (FaceToFaceSide) -> Unit,
    onManualRelease: () -> Unit,
    onStartAuto: () -> Unit,
    onPressRightAuto: () -> Unit,
    onReleaseRightAuto: () -> Unit,
    onPauseAuto: () -> Unit,
    onResumeAuto: () -> Unit,
    onStopAuto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val manualMode = state.mode == FaceToFaceMode.MANUAL
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        EarMicButton(
            modifier = Modifier.weight(1f), side = FaceToFaceSide.LEFT, language = state.leftLanguage,
            active = presentation.activeMic == FaceToFaceSide.LEFT,
            pointerEnabled = manualMode && state.phase == FaceToFacePhase.IDLE,
            actionEnabled = manualMode && state.phase == FaceToFacePhase.IDLE,
            stateLabel = if (!manualMode && state.phase == FaceToFacePhase.LISTENING) "连续收音" else "按住说话",
            onPress = { requestMicrophone { onManualPress(FaceToFaceSide.LEFT) } }, onRelease = onManualRelease,
        )
        AutoControls(state, requestMicrophone, onStartAuto, onPauseAuto, onResumeAuto, onStopAuto)
        EarMicButton(
            modifier = Modifier.weight(1f), side = FaceToFaceSide.RIGHT, language = state.rightLanguage,
            active = presentation.activeMic == FaceToFaceSide.RIGHT,
            pointerEnabled = if (manualMode) state.phase == FaceToFacePhase.IDLE else state.phase == FaceToFacePhase.LISTENING,
            actionEnabled = if (manualMode) state.phase == FaceToFacePhase.IDLE else state.phase == FaceToFacePhase.LISTENING,
            stateLabel = if (manualMode) "按住说话" else "按住临时切换",
            onPress = { if (manualMode) requestMicrophone { onManualPress(FaceToFaceSide.RIGHT) } else onPressRightAuto() },
            onRelease = if (manualMode) onManualRelease else onReleaseRightAuto,
        )
    }
}

@Composable
private fun AutoControls(state: FaceToFaceState, requestMicrophone: (() -> Unit) -> Unit, onStart: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    if (state.mode != FaceToFaceMode.AUTO) return
    when (state.phase) {
        FaceToFacePhase.IDLE -> Button(onClick = { requestMicrophone(onStart) }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Filled.Mic, contentDescription = "开始连续翻译") }
        FaceToFacePhase.LISTENING -> OutlinedButton(onClick = onPause, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Filled.Pause, contentDescription = "暂停连续翻译") }
        FaceToFacePhase.PAUSED -> Button(onClick = onResume, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Filled.PlayArrow, contentDescription = "继续连续翻译") }
        else -> OutlinedButton(onClick = onStop, enabled = false, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Filled.Stop, contentDescription = "停止连续翻译") }
    }
}

@Composable
private fun EarMicButton(
    modifier: Modifier,
    side: FaceToFaceSide,
    language: String,
    active: Boolean,
    pointerEnabled: Boolean,
    actionEnabled: Boolean,
    stateLabel: String,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val currentPress by rememberUpdatedState(onPress)
    val currentRelease by rememberUpdatedState(onRelease)
    val currentPointerEnabled by rememberUpdatedState(pointerEnabled)
    val gate = remember(side) { MicPressGate(onPress = { currentPress() }, onRelease = { currentRelease() }) }
    val animatorScale = Settings.Global.getFloat(LocalContext.current.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    val color = MaterialTheme.colorScheme.primary
    Surface(
        modifier = modifier.heightIn(min = 72.dp)
            .semantics {
                contentDescription = "${TranslationLanguage.displayName(language)}麦克风"
                stateDescription = stateLabel
                if (actionEnabled) {
                    onClick(label = "开始${TranslationLanguage.displayName(language)}收音") {
                        gate.press()
                        gate.release()
                        true
                    }
                }
            }
            .pointerInput(side) {
                detectTapGestures(onPress = {
                    if (!currentPointerEnabled) return@detectTapGestures
                    gate.press()
                    try { awaitRelease() } finally { gate.cancel() }
                })
            },
        shape = CircleShape,
        color = if (active) color.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (active) MicRipple(animatorScale, color)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                Text(TranslationLanguage.displayName(language), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun MicRipple(animatorScale: Float, color: androidx.compose.ui.graphics.Color) {
    if (animatorScale == 0f) StaticMicRipple(color) else AnimatedMicRipple(color)
}

@Composable
private fun StaticMicRipple(color: androidx.compose.ui.graphics.Color) = DrawMicRipple(color, 1.15f)

@Composable
private fun AnimatedMicRipple(color: androidx.compose.ui.graphics.Color) {
    val transition = rememberInfiniteTransition(label = "micRipple")
    val radius by transition.animateFloat(0.8f, 1.35f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "micRippleRadius")
    DrawMicRipple(color, radius)
}

@Composable
private fun DrawMicRipple(color: androidx.compose.ui.graphics.Color, radius: Float) {
    Canvas(Modifier.size(68.dp)) {
        drawCircle(color.copy(alpha = 0.22f), size.minDimension * radius / 2f, style = Stroke(2.dp.toPx()))
        drawCircle(color.copy(alpha = 0.12f), size.minDimension * (radius + 0.2f) / 2f, style = Stroke(1.dp.toPx()))
    }
}
