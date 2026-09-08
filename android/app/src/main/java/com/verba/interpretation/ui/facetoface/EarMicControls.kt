package com.verba.interpretation.ui.facetoface

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verba.interpretation.ui.design.TranslationVisualTokens
import com.verba.interpretation.ui.design.VerbaColors
import com.verba.interpretation.ui.FaceToFaceMode
import kotlinx.coroutines.CancellationException
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.MicrophonePermissionAction
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceState
import com.verba.interpretation.ui.TranslationLanguage

internal enum class MicPressOwner { POINTER, SEMANTICS }

internal class MicPressToken internal constructor(val owner: MicPressOwner, val sequence: Long) {
    override fun equals(other: Any?): Boolean = other is MicPressToken && owner == other.owner && sequence == other.sequence
    override fun hashCode(): Int = 31 * owner.hashCode() + sequence.hashCode()
}

internal enum class FaceToFaceAction { START_CONTINUOUS, PAUSE_CONTINUOUS, RESUME_CONTINUOUS, END_CONTINUOUS }

internal data class MicVisualPolicy(val scale: Float, val breathing: Boolean)

internal fun micVisualPolicy(
    side: FaceToFaceSide,
    activeSide: FaceToFaceSide?,
    phase: FaceToFacePhase,
): MicVisualPolicy = if (phase == FaceToFacePhase.LISTENING && activeSide != null) {
    MicVisualPolicy(scale = if (side == activeSide) 1.065f else 0.92f, breathing = side == activeSide)
} else {
    MicVisualPolicy(scale = 1f, breathing = false)
}

internal fun continuousActions(state: FaceToFaceState): List<FaceToFaceAction> =
    if (state.mode != FaceToFaceMode.AUTO) emptyList() else when (state.phase) {
        FaceToFacePhase.IDLE -> listOf(FaceToFaceAction.START_CONTINUOUS)
        FaceToFacePhase.LISTENING -> listOf(FaceToFaceAction.PAUSE_CONTINUOUS, FaceToFaceAction.END_CONTINUOUS)
        FaceToFacePhase.PAUSED -> listOf(FaceToFaceAction.RESUME_CONTINUOUS, FaceToFaceAction.END_CONTINUOUS)
        FaceToFacePhase.PROCESSING, FaceToFacePhase.STOPPING, FaceToFacePhase.ERROR -> emptyList()
    }

internal fun continuousLeftAction(phase: FaceToFacePhase): FaceToFaceAction? = when (phase) {
    FaceToFacePhase.IDLE -> FaceToFaceAction.START_CONTINUOUS
    FaceToFacePhase.LISTENING -> FaceToFaceAction.PAUSE_CONTINUOUS
    FaceToFacePhase.PAUSED -> FaceToFaceAction.RESUME_CONTINUOUS
    else -> null
}

internal fun runContinuousLeftAction(
    phase: FaceToFacePhase,
    requestMicrophone: (MicrophonePermissionAction) -> Unit,
    pause: () -> Unit,
) {
    when (continuousLeftAction(phase)) {
        FaceToFaceAction.START_CONTINUOUS -> requestMicrophone(MicrophonePermissionAction.ContinuousStart)
        FaceToFaceAction.RESUME_CONTINUOUS -> requestMicrophone(MicrophonePermissionAction.ContinuousResume)
        FaceToFaceAction.PAUSE_CONTINUOUS -> pause()
        else -> Unit
    }
}

internal class MicPressGate(
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit = onRelease,
) {
    private var currentPress = onPress
    private var currentRelease = onRelease
    private var currentCancel = onCancel
    private var active: ActivePress? = null
    private var nextSequence = 0L
    private data class ActivePress(
        val token: MicPressToken,
        val release: () -> Unit,
        val cancel: () -> Unit,
    )

    fun updateCallbacks(onPress: () -> Unit, onRelease: () -> Unit, onCancel: () -> Unit = onRelease) {
        currentPress = onPress
        currentRelease = onRelease
        currentCancel = onCancel
    }

    fun acquire(owner: MicPressOwner): MicPressToken? {
        if (active != null) return null
        val token = MicPressToken(owner, ++nextSequence)
        active = ActivePress(token, currentRelease, currentCancel)
        currentPress()
        return token
    }

    fun release(token: MicPressToken?) = finish(token) { it.release() }
    fun cancel(token: MicPressToken?) = finish(token) { it.cancel() }

    /** A semantics click ends an active pointer press instead of invoking the action twice. */
    fun accessibleClick(action: () -> Unit): Boolean {
        val pointer = active?.takeIf { it.token.owner == MicPressOwner.POINTER }
        if (pointer != null) {
            release(pointer.token)
        } else {
            action()
        }
        return true
    }

    fun press() { acquire(MicPressOwner.POINTER) }
    fun release() { active?.token?.let(::release) }
    fun cancel() { active?.token?.let(::cancel) }

    private fun finish(token: MicPressToken?, callback: (ActivePress) -> Unit) {
        val acquired = active ?: return
        if (token != acquired.token) return
        active = null
        callback(acquired)
    }
}

internal suspend fun finishMicPress(
    gate: MicPressGate,
    token: MicPressToken,
    awaitRelease: suspend () -> Boolean,
) {
    try {
        if (awaitRelease()) {
            gate.release(token)
        } else {
            gate.cancel(token)
        }
    } catch (error: CancellationException) {
        gate.cancel(token)
        throw error
    }
}

@Composable
internal fun EarMicControls(
    state: FaceToFaceState,
    presentation: FaceToFacePresentation,
    requestMicrophone: (MicrophonePermissionAction) -> Unit,
    onManualPress: (FaceToFaceSide) -> Unit,
    onManualRelease: () -> Unit,
    onManualCancel: () -> Unit,
    onStartAuto: () -> Unit,
    onPressRightAuto: () -> Unit,
    onReleaseRightAuto: () -> Unit,
    onCancelRightAuto: () -> Unit,
    onPauseAuto: () -> Unit,
    onResumeAuto: () -> Unit,
    onStopAuto: () -> Unit,
    onSetLanguages: (String, String) -> Unit,
    clearMicrophoneRequest: () -> Unit = {},
    modifier: Modifier = Modifier,
    visibleSides: Set<FaceToFaceSide> = setOf(FaceToFaceSide.LEFT, FaceToFaceSide.RIGHT),
    showAutoControls: Boolean = true,
) {
    val manual = state.mode == FaceToFaceMode.MANUAL
    val activeSide = presentation.activeMic
    val leftRelease: () -> Unit = {
        clearMicrophoneRequest()
        if (manual) onManualRelease()
    }
    val leftCancel: () -> Unit = {
        clearMicrophoneRequest()
        if (manual) onManualCancel()
    }
    Column(
        modifier.fillMaxWidth().height(TranslationVisualTokens.OperationHeight).padding(horizontal = 20.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(TranslationVisualTokens.MicGroupGap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (FaceToFaceSide.LEFT in visibleSides) EarMicButton(
                side = FaceToFaceSide.LEFT,
                language = state.leftLanguage,
                otherLanguage = state.rightLanguage,
                pointerEnabled = if (manual) state.phase == FaceToFacePhase.IDLE else state.phase in setOf(FaceToFacePhase.IDLE, FaceToFacePhase.LISTENING, FaceToFacePhase.PAUSED),
                actionEnabled = if (manual) state.phase == FaceToFacePhase.IDLE || activeSide == FaceToFaceSide.LEFT else continuousLeftAction(state.phase) != null,
                active = activeSide == FaceToFaceSide.LEFT,
                activeSide = activeSide,
                phase = state.phase,
                stateLabel = if (manual) "按住说话" else when (state.phase) {
                    FaceToFacePhase.IDLE -> "开始连续收音"
                    FaceToFacePhase.PAUSED -> "继续连续收音"
                    else -> "暂停连续收音"
                },
                onPress = {
                    if (manual) requestMicrophone(MicrophonePermissionAction.Manual(FaceToFaceSide.LEFT))
                    else runContinuousLeftAction(state.phase, requestMicrophone) { clearMicrophoneRequest(); onPauseAuto() }
                },
                onRelease = leftRelease,
                onCancel = leftCancel,
                onAccessibleClick = if (manual) {
                    {
                        if (state.phase == FaceToFacePhase.IDLE) requestMicrophone(MicrophonePermissionAction.Manual(FaceToFaceSide.LEFT))
                        else { clearMicrophoneRequest(); onManualRelease() }
                    }
                } else {
                    { runContinuousLeftAction(state.phase, requestMicrophone) { clearMicrophoneRequest(); onPauseAuto() } }
                },
                onLanguage = { onSetLanguages(it, state.rightLanguage) },
            )
            // 连续控制只属于已选中的连续翻译模式；手动模式不展示，避免误以为
            // 可以直接从双麦中间切换会话模式。
            if (showAutoControls && visibleSides.size == 2 && !manual) {
                ContinuousControls(
                    phase = state.phase,
                    requestMicrophone = requestMicrophone,
                    onPause = { clearMicrophoneRequest(); onPauseAuto() },
                )
            }
            if (FaceToFaceSide.RIGHT in visibleSides) EarMicButton(
                side = FaceToFaceSide.RIGHT,
                language = state.rightLanguage,
                otherLanguage = state.leftLanguage,
                pointerEnabled = if (manual) state.phase == FaceToFacePhase.IDLE else state.phase == FaceToFacePhase.LISTENING,
                actionEnabled = if (manual) state.phase == FaceToFacePhase.IDLE || activeSide == FaceToFaceSide.RIGHT else state.phase == FaceToFacePhase.LISTENING,
                active = activeSide == FaceToFaceSide.RIGHT,
                activeSide = activeSide,
                phase = state.phase,
                stateLabel = if (manual) "按住说话" else if (activeSide == FaceToFaceSide.RIGHT) "结束右侧临时接话" else "开始右侧临时接话",
                onPress = {
                    if (manual) requestMicrophone(MicrophonePermissionAction.Manual(FaceToFaceSide.RIGHT)) else onPressRightAuto()
                },
                onRelease = {
                    clearMicrophoneRequest()
                    if (manual) onManualRelease() else onReleaseRightAuto()
                },
                onCancel = {
                    clearMicrophoneRequest()
                    if (manual) onManualCancel() else onCancelRightAuto()
                },
                onAccessibleClick = if (manual) {
                    {
                        if (state.phase == FaceToFacePhase.IDLE) requestMicrophone(MicrophonePermissionAction.Manual(FaceToFaceSide.RIGHT))
                        else { clearMicrophoneRequest(); onManualRelease() }
                    }
                } else {
                    {
                        if (activeSide == FaceToFaceSide.RIGHT) {
                            clearMicrophoneRequest()
                            onReleaseRightAuto()
                        } else {
                            requestMicrophone(MicrophonePermissionAction.ContinuousTakeover)
                        }
                    }
                },
                onLanguage = { onSetLanguages(state.leftLanguage, it) },
            )
        }
    }
}

@Composable
private fun LanguagePairRow(
    state: FaceToFaceState,
    enabled: Boolean,
    onSetLanguages: (String, String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        LanguageEntry(FaceToFaceSide.LEFT, state.leftLanguage, state.rightLanguage, enabled) { onSetLanguages(it, state.rightLanguage) }
        Text("↔", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        LanguageEntry(FaceToFaceSide.RIGHT, state.rightLanguage, state.leftLanguage, enabled) { onSetLanguages(state.leftLanguage, it) }
    }
}

@Composable
private fun LanguageEntry(side: FaceToFaceSide, language: String, otherLanguage: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.material3.TextButton(
        onClick = { expanded = true },
        enabled = enabled,
        modifier = Modifier.heightIn(min = 44.dp).semantics {
            contentDescription = "选择${TranslationLanguage.displayName(language)}语言"
        },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                TranslationLanguage.displayName(language),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Medium,
            )
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
    androidx.compose.material3.DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
        TranslationLanguage.entries.filter { it.code != otherLanguage }.forEach { choice ->
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(choice.displayName) },
                onClick = { onSelect(choice.code); expanded = false },
            )
        }
    }
}

@Composable
internal fun EarMicButton(
    side: FaceToFaceSide,
    language: String,
    otherLanguage: String,
    pointerEnabled: Boolean,
    actionEnabled: Boolean,
    active: Boolean,
    activeSide: FaceToFaceSide?,
    phase: FaceToFacePhase,
    stateLabel: String,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
    onAccessibleClick: (() -> Unit)?,
    onLanguage: (String) -> Unit,
) {
    val currentPointerEnabled by rememberUpdatedState(pointerEnabled)
    val currentOnPress by rememberUpdatedState(onPress)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val currentOnCancel by rememberUpdatedState(onCancel)
    val gate = remember(side) { MicPressGate(currentOnPress, currentOnRelease, currentOnCancel) }
    gate.updateCallbacks(currentOnPress, currentOnRelease, currentOnCancel)
    val color = if (side == FaceToFaceSide.LEFT) VerbaColors.LeftMic else VerbaColors.RightMic
    // LeftMic/RightMic are the ear-identity accents (theme-stable per VerbaColors docs);
    // the dark canvas ink painted on them keeps >= 7:1 contrast in both themes.
    val target = targetEarLabel(side)
    val motionEnabled = android.animation.ValueAnimator.areAnimatorsEnabled()
    val transition = rememberInfiniteTransition(label = "${side.name.lowercase()}MicBreathing")
    val breathing by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathing",
    )
    val visualPolicy = micVisualPolicy(side, activeSide, phase)
    val activeScale = visualPolicy.scale
    val description = "${earLabel(side)}，${TranslationLanguage.displayName(language)}，$stateLabel，译文送至$target"
    Column(
        modifier = Modifier.width(TranslationVisualTokens.MicGroupWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LanguageEntry(side, language, otherLanguage, actionEnabled && !active, onLanguage)
        androidx.compose.foundation.layout.Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .size(TranslationVisualTokens.MicDiameter)
                .drawBehind {
                    if (visualPolicy.breathing) {
                        val scaledRadius = size.minDimension / 2f * visualPolicy.scale
                        val extra = if (motionEnabled) {
                            (5.dp + 4.dp * breathing).toPx()
                        } else {
                            5.dp.toPx()
                        }
                        drawCircle(
                            color = color.copy(alpha = if (motionEnabled) 0.08f + 0.07f * breathing else 0.28f),
                            radius = scaledRadius + extra,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .size(TranslationVisualTokens.MicDiameter)
                    .graphicsLayer { scaleX = activeScale; scaleY = activeScale }
                    .then(if (active && !motionEnabled) Modifier.border(2.dp, color.copy(alpha = 0.8f), CircleShape) else Modifier)
                    .semantics {
                    role = Role.Button
                    contentDescription = description
                    stateDescription = stateLabel
                    if (!actionEnabled) disabled()
                    onAccessibleClick?.let { click ->
                        onClick(label = if (active) "停止${TranslationLanguage.displayName(language)}收音" else "开始${TranslationLanguage.displayName(language)}收音") {
                            gate.accessibleClick(click)
                        }
                    }
                }
                .pointerInput(side) {
                    detectTapGestures(onPress = {
                        if (!currentPointerEnabled) return@detectTapGestures
                        val token = gate.acquire(MicPressOwner.POINTER) ?: return@detectTapGestures
                        finishMicPress(gate, token) { tryAwaitRelease() }
                    })
                },
            shape = CircleShape,
            color = color,
            tonalElevation = 0.dp,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = VerbaColors.Canvas, modifier = Modifier.size(26.dp))
                Text(
                    if (side == FaceToFaceSide.LEFT) "L" else "R",
                    color = VerbaColors.Canvas,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
        }
    }
}

@Composable
private fun ContinuousControls(
    phase: FaceToFacePhase,
    requestMicrophone: (MicrophonePermissionAction) -> Unit,
    onPause: () -> Unit,
) {
    val action = continuousLeftAction(phase)
    val label = when (action) {
        FaceToFaceAction.START_CONTINUOUS -> "开始连续翻译"
        FaceToFaceAction.PAUSE_CONTINUOUS -> "暂停连续翻译"
        FaceToFaceAction.RESUME_CONTINUOUS -> "继续连续翻译"
        null -> "连续翻译不可用"
        else -> "连续翻译不可用"
    }
    val icon = when (action) {
        FaceToFaceAction.PAUSE_CONTINUOUS -> Icons.Filled.Pause
        else -> Icons.Filled.PlayArrow
    }
    fun runAction() {
        when (action) {
            FaceToFaceAction.START_CONTINUOUS -> requestMicrophone(MicrophonePermissionAction.ContinuousStart)
            FaceToFaceAction.PAUSE_CONTINUOUS -> onPause()
            FaceToFaceAction.RESUME_CONTINUOUS -> requestMicrophone(MicrophonePermissionAction.ContinuousResume)
            else -> Unit
        }
    }
    Surface(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .clickable(enabled = action != null, role = Role.Button, onClick = ::runAction)
            .semantics {
                contentDescription = label
                role = Role.Button
                if (action == null) disabled()
                onClick(label = label) { runAction(); true }
            },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/* Legacy action-row prototype retained below for source history. */
/*
    state: FaceToFaceState,
    requestMicrophone: (MicrophonePermissionAction) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    val actions = continuousActions(state)
    if (actions.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.forEach { action ->
            when (action) {
                FaceToFaceAction.START_CONTINUOUS -> ActionButton("开始连续翻译", Icons.Filled.Mic, { requestMicrophone(MicrophonePermissionAction.ContinuousStart) }, true)
                FaceToFaceAction.PAUSE_CONTINUOUS -> ActionButton("暂停连续翻译", Icons.Filled.Pause, onPause, true)
                FaceToFaceAction.RESUME_CONTINUOUS -> ActionButton("恢复连续翻译", Icons.Filled.PlayArrow, { requestMicrophone(MicrophonePermissionAction.ContinuousResume) }, true)
                FaceToFaceAction.END_CONTINUOUS -> ActionButton("结束连续翻译", Icons.Filled.Stop, onStop, false)
            }
        }
    }
}

*/
