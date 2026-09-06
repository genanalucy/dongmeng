package com.verba.interpretation.ui.facetoface

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.graphics.Color
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
import com.verba.interpretation.ui.FaceToFaceMode
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceState
import com.verba.interpretation.ui.TranslationLanguage

internal enum class MicPressOwner { POINTER, SEMANTICS }

internal class MicPressToken internal constructor(val owner: MicPressOwner, val sequence: Long) {
    override fun equals(other: Any?): Boolean = other is MicPressToken && owner == other.owner && sequence == other.sequence
    override fun hashCode(): Int = 31 * owner.hashCode() + sequence.hashCode()
}

internal enum class FaceToFaceAction { START_CONTINUOUS, PAUSE_CONTINUOUS, RESUME_CONTINUOUS, END_CONTINUOUS }

internal fun continuousActions(state: FaceToFaceState): List<FaceToFaceAction> =
    if (state.mode != FaceToFaceMode.AUTO) emptyList() else when (state.phase) {
        FaceToFacePhase.IDLE -> listOf(FaceToFaceAction.START_CONTINUOUS)
        FaceToFacePhase.LISTENING -> listOf(FaceToFaceAction.PAUSE_CONTINUOUS, FaceToFaceAction.END_CONTINUOUS)
        FaceToFacePhase.PAUSED -> listOf(FaceToFaceAction.RESUME_CONTINUOUS, FaceToFaceAction.END_CONTINUOUS)
        FaceToFacePhase.PROCESSING, FaceToFacePhase.STOPPING, FaceToFacePhase.ERROR -> emptyList()
    }

internal class MicPressGate(onPress: () -> Unit, onRelease: () -> Unit) {
    private var currentPress = onPress
    private var currentRelease = onRelease
    private var active: ActivePress? = null
    private var nextSequence = 0L
    private data class ActivePress(val token: MicPressToken, val release: () -> Unit)

    fun updateCallbacks(onPress: () -> Unit, onRelease: () -> Unit) {
        currentPress = onPress
        currentRelease = onRelease
    }

    fun acquire(owner: MicPressOwner): MicPressToken? {
        if (active != null) return null
        val token = MicPressToken(owner, ++nextSequence)
        active = ActivePress(token, currentRelease)
        currentPress()
        return token
    }

    fun release(token: MicPressToken?) = finish(token)
    fun cancel(token: MicPressToken?) = finish(token)
    fun press() { acquire(MicPressOwner.POINTER) }
    fun release() { active?.token?.let(::release) }
    fun cancel() { active?.token?.let(::cancel) }

    private fun finish(token: MicPressToken?) {
        val acquired = active ?: return
        if (token != acquired.token) return
        active = null
        acquired.release()
    }
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
    onSetLanguages: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val manual = state.mode == FaceToFaceMode.MANUAL
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
            EarMicButton(
                side = FaceToFaceSide.LEFT,
                language = state.leftLanguage,
                otherLanguage = state.rightLanguage,
                enabled = if (manual) state.phase == FaceToFacePhase.IDLE else false,
                active = presentation.activeMic == FaceToFaceSide.LEFT,
                stateLabel = if (manual) "按住说话" else "左侧连续收音",
                onPress = { requestMicrophone { if (manual) onManualPress(FaceToFaceSide.LEFT) } },
                onRelease = if (manual) onManualRelease else onPauseAuto,
                onLanguage = { onSetLanguages(it, state.rightLanguage) },
            )
            EarMicButton(
                side = FaceToFaceSide.RIGHT,
                language = state.rightLanguage,
                otherLanguage = state.leftLanguage,
                enabled = if (manual) state.phase == FaceToFacePhase.IDLE else state.phase == FaceToFacePhase.LISTENING,
                active = presentation.activeMic == FaceToFaceSide.RIGHT,
                stateLabel = if (manual) "按住说话" else "按住临时接话",
                onPress = {
                    if (manual) requestMicrophone { onManualPress(FaceToFaceSide.RIGHT) } else onPressRightAuto()
                },
                onRelease = if (manual) onManualRelease else onReleaseRightAuto,
                onLanguage = { onSetLanguages(state.leftLanguage, it) },
            )
        }
        AutoControls(state, requestMicrophone, onStartAuto, onPauseAuto, onResumeAuto, onStopAuto)
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
        Text("↔", color = Color(0xFF66778A), style = MaterialTheme.typography.labelSmall)
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
        Text(TranslationLanguage.displayName(language), color = Color(0xFFCBD5E1), style = MaterialTheme.typography.labelLarge)
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
private fun EarMicButton(
    side: FaceToFaceSide,
    language: String,
    otherLanguage: String,
    enabled: Boolean,
    active: Boolean,
    stateLabel: String,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onLanguage: (String) -> Unit,
) {
    val currentEnabled by rememberUpdatedState(enabled)
    val gate = remember(side) { MicPressGate(onPress, onRelease) }
    gate.updateCallbacks(onPress, onRelease)
    val color = if (side == FaceToFaceSide.LEFT) Color(0xFF91B5D5) else Color(0xFFE0BC83)
    val target = targetEarLabel(side)
    val description = "${earLabel(side)}，${TranslationLanguage.displayName(language)}，$stateLabel，译文送至$target"
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LanguageEntry(side, language, otherLanguage, enabled, onLanguage)
        Surface(
            modifier = Modifier.size(86.dp)
                .semantics {
                    role = Role.Button
                    contentDescription = description
                    stateDescription = stateLabel
                    if (!enabled) disabled()
                    if (enabled) onClick(label = "开始${TranslationLanguage.displayName(language)}收音") {
                        val token = gate.acquire(MicPressOwner.SEMANTICS)
                        gate.release(token)
                        token != null
                    }
                }
                .pointerInput(side, enabled) {
                    detectTapGestures(onPress = {
                        if (!currentEnabled) return@detectTapGestures
                        val token = gate.acquire(MicPressOwner.POINTER) ?: return@detectTapGestures
                        try { awaitRelease() } finally { gate.cancel(token) }
                    })
                },
            shape = CircleShape,
            color = color,
            tonalElevation = if (active) 5.dp else 1.dp,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = Color(0xFF07111F), modifier = Modifier.size(28.dp))
                Text(earLabel(side), color = Color(0xFF07111F), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Text("→ $target", color = Color(0xFF07111F), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun AutoControls(
    state: FaceToFaceState,
    requestMicrophone: (() -> Unit) -> Unit,
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
                FaceToFaceAction.START_CONTINUOUS -> ActionButton("开始连续翻译", Icons.Filled.Mic, { requestMicrophone(onStart) }, true)
                FaceToFaceAction.PAUSE_CONTINUOUS -> ActionButton("暂停连续翻译", Icons.Filled.Pause, onPause, true)
                FaceToFaceAction.RESUME_CONTINUOUS -> ActionButton("恢复连续翻译", Icons.Filled.PlayArrow, onResume, true)
                FaceToFaceAction.END_CONTINUOUS -> ActionButton("结束连续翻译", Icons.Filled.Stop, onStop, false)
            }
        }
    }
}

@Composable
private fun RowScope.ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, primary: Boolean) {
    val modifier = Modifier.weight(1f).heightIn(min = 48.dp)
    if (primary) {
        Button(onClick = onClick, modifier = modifier) { Icon(icon, contentDescription = label); Text(label, modifier = Modifier.padding(start = 4.dp)) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Icon(icon, contentDescription = label); Text(label, modifier = Modifier.padding(start = 4.dp)) }
    }
}
