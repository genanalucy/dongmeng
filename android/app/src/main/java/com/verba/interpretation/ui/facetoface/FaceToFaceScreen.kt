package com.verba.interpretation.ui.facetoface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verba.interpretation.ui.FaceToFaceMode
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceState
import com.verba.interpretation.ui.FaceToFaceViewModel
import com.verba.interpretation.ui.TranslationLanguage

private fun faceStatusLabel(state: FaceToFaceState): String = when (state.phase) {
    FaceToFacePhase.IDLE -> if (state.mode == FaceToFaceMode.AUTO) "连续翻译待开始" else "按住麦克风开始"
    FaceToFacePhase.LISTENING -> "正在收音"
    FaceToFacePhase.PAUSED -> "连续翻译已暂停"
    FaceToFacePhase.PROCESSING -> "正在翻译"
    FaceToFacePhase.STOPPING -> "正在结束"
    FaceToFacePhase.ERROR -> "需要处理"
}

@Composable
internal fun FaceToFaceScreen(
    state: FaceToFaceState,
    viewModel: FaceToFaceViewModel,
    requestMicrophone: (() -> Unit) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = faceToFacePresentation(state)
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onExit, modifier = Modifier.semantics { contentDescription = "退出面对面翻译" }) {
                Icon(Icons.Filled.Close, contentDescription = null)
            }
            Column(Modifier.weight(1f)) {
                Text("面对面翻译", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(faceStatusLabel(state), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            FaceToFaceOverflowMenu(
                state = state,
                onSelectMode = viewModel::setMode,
                onStartAuto = { requestMicrophone(viewModel::startAuto) },
                onPauseAuto = viewModel::pauseAuto,
                onResumeAuto = viewModel::resumeAuto,
                onStopAuto = viewModel::stopAuto,
            )
        }
        LanguageChips(state, presentation.canChangeLanguages, viewModel::setLanguages)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        ConversationTimeline(
            turns = state.turns,
            activeMic = presentation.activeMic,
            listeningPlaceholder = presentation.timelinePlaceholder,
            modifier = Modifier.weight(1f),
        )
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
            EarMicControls(
                state = state,
                presentation = presentation,
                requestMicrophone = requestMicrophone,
                onManualPress = viewModel::manualPress,
                onManualRelease = viewModel::manualRelease,
                onStartAuto = viewModel::startAuto,
                onPressRightAuto = viewModel::pressRightAuto,
                onReleaseRightAuto = viewModel::releaseRightAuto,
                onPauseAuto = viewModel::pauseAuto,
                onResumeAuto = viewModel::resumeAuto,
                onStopAuto = viewModel::stopAuto,
            )
        }
    }
}

@Composable
private fun LanguageChips(
    state: FaceToFaceState,
    enabled: Boolean,
    onSetLanguages: (String, String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        LanguageChip(
            language = state.leftLanguage,
            otherLanguage = state.rightLanguage,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onSelect = { onSetLanguages(it, state.rightLanguage) },
        )
        Text("↔", modifier = Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LanguageChip(
            language = state.rightLanguage,
            otherLanguage = state.leftLanguage,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onSelect = { onSetLanguages(state.leftLanguage, it) },
        )
    }
}

@Composable
private fun LanguageChip(
    language: String,
    otherLanguage: String,
    enabled: Boolean,
    modifier: Modifier,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        androidx.compose.material3.AssistChip(
            onClick = { if (enabled) expanded = true },
            enabled = enabled,
            label = { Text(TranslationLanguage.displayName(language)) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "选择${TranslationLanguage.displayName(language)}语言" },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TranslationLanguage.entries.filter { it.code != otherLanguage }.forEach { choice ->
                DropdownMenuItem(text = { Text(choice.displayName) }, onClick = { onSelect(choice.code); expanded = false })
            }
        }
    }
}
