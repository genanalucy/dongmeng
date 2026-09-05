package com.verba.interpretation.ui.facetoface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verba.interpretation.ui.FaceToFaceMode
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceSide
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
    modifier: Modifier = Modifier,
) {
    val presentation = faceToFacePresentation(state)
    Column(
        modifier.fillMaxSize().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "面对面翻译",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    faceStatusLabel(state),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
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

        DirectionPicker(
            state = state,
            enabled = presentation.canChangeLanguages,
            onSetLanguages = viewModel::setLanguages,
        )

        if (presentation.showRecoveryAction) {
            presentation.recoveryMessage?.let { message ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                        Button(
                            onClick = viewModel::cancel,
                            modifier = Modifier.padding(top = 10.dp).heightIn(min = 48.dp),
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Text(FACE_TO_FACE_RECOVERY_ACTION_LABEL, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
        }

        ConversationTimeline(
            turns = state.turns,
            activeMic = presentation.activeMic,
            listeningPlaceholder = presentation.timelinePlaceholder,
            modifier = Modifier.weight(1f),
        )

        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
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
private fun DirectionPicker(
    state: FaceToFaceState,
    enabled: Boolean,
    onSetLanguages: (String, String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DirectionColumn(
            side = FaceToFaceSide.LEFT,
            language = state.leftLanguage,
            otherLanguage = state.rightLanguage,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onSelect = { onSetLanguages(it, state.rightLanguage) },
        )
        IconButton(
            onClick = { onSetLanguages(state.rightLanguage, state.leftLanguage) },
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(Icons.Outlined.SwapHoriz, contentDescription = "交换左右耳语言")
        }
        DirectionColumn(
            side = FaceToFaceSide.RIGHT,
            language = state.rightLanguage,
            otherLanguage = state.leftLanguage,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            onSelect = { onSetLanguages(state.leftLanguage, it) },
        )
    }
}

@Composable
private fun DirectionColumn(
    side: FaceToFaceSide,
    language: String,
    otherLanguage: String,
    enabled: Boolean,
    modifier: Modifier,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = modifier.semantics {
            contentDescription = "${earLabel(side)}，${TranslationLanguage.displayName(language)}"
        },
    ) {
        Text(
            earLabel(side),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LanguageSelector(language, otherLanguage, enabled, onSelect)
    }
}

@Composable
private fun LanguageSelector(
    language: String,
    otherLanguage: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) {
        if (!enabled) expanded = false
    }
    Box {
        androidx.compose.material3.TextButton(
            onClick = { if (enabled) expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = "选择${TranslationLanguage.displayName(language)}语言"
            },
        ) {
            Text(TranslationLanguage.displayName(language), style = MaterialTheme.typography.titleMedium)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            TranslationLanguage.entries.filter { it.code != otherLanguage }.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(choice.displayName) },
                    enabled = enabled,
                    onClick = {
                        if (enabled) onSelect(choice.code)
                        expanded = false
                    },
                )
            }
        }
    }
}
