package com.verba.interpretation.ui.facetoface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verba.interpretation.ui.FaceToFaceMode
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceState

internal enum class SubtitleSize(val label: String, val factor: Float) {
    SMALL("小", 0.9f), STANDARD("标准", 1f), LARGE("大", 1.2f),
}

internal val LocalSubtitleSize = staticCompositionLocalOf { SubtitleSize.STANDARD }

internal fun conversationStatus(state: FaceToFaceState): String = when {
    state.phase == FaceToFacePhase.ERROR -> "翻译已停止"
    state.connectionPending -> if (state.playbackActive) "正在连接 · 译音播放中" else "正在连接翻译服务…"
    state.captureActive -> "${state.activeSide?.let(::earLabel) ?: "当前一方"}方向收音中" + if (state.playbackActive) " · 译音播放中" else ""
    state.playbackActive -> "译音播放中"
    state.phase == FaceToFacePhase.PROCESSING -> "正在翻译，请稍候…"
    state.phase == FaceToFacePhase.STOPPING -> "正在结束，等待字幕与译音完成…"
    state.phase == FaceToFacePhase.PAUSED -> "已暂停收音"
    !state.configurationEditable -> "等待上一轮收尾…"
    else -> "准备就绪"
}

@Composable
internal fun SubtitleSizeSelector(size: SubtitleSize, onSelect: (SubtitleSize) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("字幕字号", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubtitleSize.entries.forEach { choice ->
                FilterChip(
                    selected = choice == size,
                    onClick = { onSelect(choice) },
                    label = { Text(choice.label) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "字幕字号：${choice.label}" },
                )
            }
        }
    }
}

@Composable
internal fun ConversationStatus(state: FaceToFaceState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(conversationStatus(state), style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
        Text("系统麦克风 · 非双耳独立收音", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!state.configurationEditable && state.phase != FaceToFacePhase.ERROR) {
            Text("结束并等待译音完成后，可交换语言或切换模式。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ConversationPreparation(continuous: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.padding(20.dp), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("让双方都听懂", style = MaterialTheme.typography.titleLarge)
            Text("① 选择双方语言，确认谁戴左耳、谁戴右耳。\n② 使用系统麦克风收音，不是双耳独立收音。\n③ 左侧发言的译音送右耳，右侧发言的译音送左耳。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (continuous) "点左侧开始连续收音；右侧按住临时接话，松开恢复左侧。" else "按住对应一侧说话，松开后等待翻译。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Text("实际输入与播放设备由系统路由决定；请先确认耳机连接和左右声道。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
