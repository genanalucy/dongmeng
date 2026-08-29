package com.verba.interpretation.ui.interpretation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun InterpretationScreen(
    model: InterpretationScreenModel,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("实时同传", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(model.languageDirection, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MicrophoneStatus(model.showMicrophoneRipple)
        TranscriptCard("原文", model.sourceText.ifBlank { "正在等待语音输入" })
        TranscriptCard("译文", model.translationText.ifBlank { "翻译结果将显示在这里" })
        model.errorMessage?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        Spacer(Modifier.weight(1f))
        model.actions.forEach { action ->
            when (action) {
                InterpretationAction.START -> PrimaryAction("开始同传", Icons.Filled.Mic, onStart)
                InterpretationAction.PAUSE -> PrimaryAction("暂停", Icons.Filled.Pause, onPause)
                InterpretationAction.RESUME -> PrimaryAction("继续", Icons.Filled.PlayArrow, onResume)
                InterpretationAction.FINISH -> OutlinedButton(onClick = onFinish, modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = "结束同传" }) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Text("结束同传", modifier = Modifier.padding(start = 8.dp))
                }
                InterpretationAction.RESET -> PrimaryAction("重新开始", Icons.Filled.Refresh, onReset)
            }
        }
    }
}

@Composable
private fun MicrophoneStatus(running: Boolean) {
    val transition = rememberInfiniteTransition(label = "microphoneRipple")
    val alpha = if (running) transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "rippleAlpha",
    ).value else 0.25f
    Row(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = if (running) "麦克风正在收音" else "麦克风未收音" },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = alpha), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
private fun TranscriptCard(label: String, text: String) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(text, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun PrimaryAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = label }) {
        Icon(icon, contentDescription = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
