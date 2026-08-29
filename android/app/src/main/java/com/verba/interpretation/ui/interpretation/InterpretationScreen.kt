package com.verba.interpretation.ui.interpretation

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun InterpretationScreen(
    model: InterpretationScreenModel,
    onExit: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val callbacks = InterpretationCallbacks(onExit, onStart, onPause, onResume, onFinish, onReset)
    val layout = InterpretationLayoutPolicy.forViewport(viewportHeightDp = Int.MAX_VALUE, actionCount = model.actions.size)
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { InterpretationActionDispatcher.exit(callbacks) }, modifier = Modifier.semantics { contentDescription = "退出实时同传" }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Column(Modifier.weight(1f)) {
                Text("实时同传", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(model.languageDirection, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { MicrophoneStatus(model.showMicrophoneRipple) }
            item { TranscriptCard("原文", model.sourceText.ifBlank { "正在等待语音输入" }) }
            item { TranscriptCard("译文", model.translationText.ifBlank { "翻译结果将显示在这里" }) }
            model.errorMessage?.let { error ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(error, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (layout.actionsPinned) model.actions.forEach { action ->
                when (action) {
                    InterpretationAction.START -> PrimaryAction("开始同传", Icons.Filled.Mic) { InterpretationActionDispatcher.dispatch(action, callbacks) }
                    InterpretationAction.PAUSE -> PrimaryAction("暂停", Icons.Filled.Pause) { InterpretationActionDispatcher.dispatch(action, callbacks) }
                    InterpretationAction.RESUME -> PrimaryAction("继续", Icons.Filled.PlayArrow) { InterpretationActionDispatcher.dispatch(action, callbacks) }
                    InterpretationAction.FINISH -> OutlinedButton(onClick = { InterpretationActionDispatcher.dispatch(action, callbacks) }, modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = "结束同传" }) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Text("结束同传", modifier = Modifier.padding(start = 8.dp))
                    }
                    InterpretationAction.RESET -> PrimaryAction("重新开始", Icons.Filled.Refresh) { InterpretationActionDispatcher.dispatch(action, callbacks) }
                }
            }
        }
    }
}

@Composable
private fun MicrophoneStatus(running: Boolean) {
    val motionEnabled = ValueAnimator.areAnimatorsEnabled()
    val transition = if (running && motionEnabled) rememberInfiniteTransition(label = "microphoneRipple") else null
    val alpha = transition?.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "rippleAlpha",
    )?.value ?: if (running) 0.55f else 0.25f
    Row(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = if (running) "麦克风正在收音" else "麦克风未收音" },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Card(
            shape = CircleShape,
            border = if (running && !motionEnabled) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = alpha)),
        ) {
            Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(32.dp))
            }
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
