package com.verba.interpretation.ui.interpretation

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal fun interpretationTimelineLatestIndex(bubbleCount: Int, hasError: Boolean): Int =
    interpretationTimelineScrollIndex(bubbleCount, hasError) ?: 0

internal fun interpretationTimelineScrollIndex(bubbleCount: Int, hasError: Boolean): Int? =
    (bubbleCount + if (hasError) 1 else 0).takeIf { it > 0 }?.minus(1)

internal fun interpretationTimelineUpdateCount(
    previousToken: List<String>,
    currentToken: List<String>,
): Int = when {
    currentToken != previousToken -> (currentToken.size - previousToken.size).coerceAtLeast(1)
    else -> 0
}

private fun InterpretationScreenModel.timelineToken(): List<String> =
    bubbles.map { "${it.key}:${it.sourceText}:${it.translationText}" } +
        errorMessage?.let { listOf("error:$it") }.orEmpty()

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
    val isSessionActive = model.actions.any {
        it == InterpretationAction.PAUSE || it == InterpretationAction.RESUME || it == InterpretationAction.FINISH
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val timelineToken = remember(model) { model.timelineToken() }
    val scrollIndex = interpretationTimelineScrollIndex(model.bubbles.size, model.errorMessage != null)
    val latestIndex = scrollIndex ?: 0
    val currentScrollIndex by rememberUpdatedState(scrollIndex)
    val currentLatestIndex by rememberUpdatedState(latestIndex)
    var previousToken by remember { mutableStateOf<List<String>?>(null) }
    var follow by remember { mutableStateOf(InterpretationTimelineFollowState()) }
    var programmaticScrollCount by remember { mutableStateOf(0) }
    var userDraggedDuringScroll by remember { mutableStateOf(false) }

    suspend fun animateToLatest(index: Int) {
        programmaticScrollCount += 1
        try {
            listState.animateScrollToItem(index)
        } finally {
            programmaticScrollCount -= 1
            follow = InterpretationTimelineFollowReducer.reduce(
                follow,
                InterpretationTimelineFollowEvent.ProgrammaticScrollFinished,
            )
        }
    }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) userDraggedDuringScroll = true
        }
    }
    LaunchedEffect(listState) {
        var wasScrolling = false
        var scrollStartedProgrammatically = false
        snapshotFlow { listState.isScrollInProgress }.collect { isScrolling ->
            when {
                isScrolling && !wasScrolling -> {
                    scrollStartedProgrammatically = programmaticScrollCount > 0
                }
                !isScrolling && wasScrolling -> {
                    if (!scrollStartedProgrammatically || userDraggedDuringScroll) {
                        val atLatest = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                            ?.let { it >= currentLatestIndex }
                            ?: true
                        follow = InterpretationTimelineFollowReducer.reduce(
                            follow,
                            InterpretationTimelineFollowEvent.UserScrollFinished(atLatest),
                        )
                    }
                    userDraggedDuringScroll = false
                }
            }
            wasScrolling = isScrolling
        }
    }
    LaunchedEffect(timelineToken, latestIndex) {
        val before = previousToken
        val updates = if (before == null) 1 else interpretationTimelineUpdateCount(before, timelineToken)
        if (updates > 0) {
            follow = InterpretationTimelineFollowReducer.reduce(
                follow,
                InterpretationTimelineFollowEvent.TranscriptAppended(updates),
            )
            if (follow.scrollToLatestRequested) {
                follow = InterpretationTimelineFollowReducer.reduce(
                    follow,
                    InterpretationTimelineFollowEvent.ScrollRequestStarted,
                )
                scrollIndex?.let { animateToLatest(it) }
            }
        }
        previousToken = timelineToken
    }

    Column(modifier = modifier.fillMaxSize()) {
        CompactHeader(
            languageDirection = model.languageDirection,
            sessionActive = isSessionActive,
            microphoneRunning = model.showMicrophoneRipple,
            onExit = { InterpretationActionDispatcher.exit(callbacks) },
        )
        // Keep long transcript/error content scrollable so the pinned controls remain reachable.
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(model.bubbles, key = InterpretationDisplayBubble::key) { bubble ->
                    InterpretationBubble(bubble)
                }
                model.errorMessage?.let { error ->
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Text(
                                text = error,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
            if (!follow.followsLatest) {
                FloatingActionButton(
                    onClick = {
                        follow = InterpretationTimelineFollowReducer.reduce(
                            follow,
                            InterpretationTimelineFollowEvent.UserTappedLatest,
                        )
                        if (follow.scrollToLatestRequested) {
                            follow = InterpretationTimelineFollowReducer.reduce(
                                follow,
                                InterpretationTimelineFollowEvent.ScrollRequestStarted,
                            )
                            currentScrollIndex?.let { index ->
                                scope.launch { animateToLatest(index) }
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDownward,
                        contentDescription = "回到最新字幕",
                    )
                }
            }
        }
        PinnedControls(
            actions = model.actions,
            microphoneRunning = model.showMicrophoneRipple,
            onAction = { action -> InterpretationActionDispatcher.dispatch(action, callbacks) },
        )
    }
}

@Composable
private fun CompactHeader(
    languageDirection: String,
    sessionActive: Boolean,
    microphoneRunning: Boolean,
    onExit: () -> Unit,
) {
    val languages = languageDirection.split(" → ", limit = 2)
    val stateLabel = when {
        microphoneRunning -> "正在收音"
        sessionActive -> "会话进行中"
        else -> "准备开始"
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onExit,
                modifier = Modifier.semantics { contentDescription = "退出实时同传" },
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("实时同传", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stateLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (sessionActive) {
                LiveMarker(microphoneRunning)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 48.dp, top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LanguageChip(
                text = "${languages.firstOrNull().orEmpty()} 原文",
                modifier = Modifier.weight(1f),
            )
            LanguageChip(
                text = "${languages.getOrNull(1).orEmpty()} 译文",
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LiveMarker(microphoneRunning: Boolean) {
    val markerColor = if (microphoneRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.semantics { contentDescription = if (microphoneRunning) "实时字幕正在更新" else "实时字幕已暂停" },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(markerColor))
        Text("实时", style = MaterialTheme.typography.labelSmall, color = markerColor)
    }
}

@Composable
private fun LanguageChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InterpretationBubble(bubble: InterpretationDisplayBubble) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = listOfNotNull(
                bubble.sourceText?.let { "原文。$it" },
                "译文。${bubble.translationText}",
            ).joinToString(" ")
        },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            bubble.sourceText?.let { source ->
                Text(text = source, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(9.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Spacer(Modifier.height(9.dp))
            }
            Text(
                text = bubble.translationText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PinnedControls(
    actions: List<InterpretationAction>,
    microphoneRunning: Boolean,
    onAction: (InterpretationAction) -> Unit,
) {
    val primaryAction = actions.firstOrNull { it != InterpretationAction.FINISH }
    val finishAction = actions.firstOrNull { it == InterpretationAction.FINISH }
    val statusLabel = when {
        microphoneRunning -> "正在收音"
        actions.contains(InterpretationAction.RESUME) -> "同传已暂停"
        actions.contains(InterpretationAction.FINISH) -> "正在准备同传"
        else -> "轻触开始同传"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MicrophoneStatus(running = microphoneRunning)
            Text(
                text = statusLabel,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            primaryAction?.let { action ->
                ActionButton(action = action, onClick = { onAction(action) })
            }
            finishAction?.let { action ->
                OutlinedButton(
                    onClick = { onAction(action) },
                    modifier = Modifier.height(48.dp).widthIn(min = 48.dp).semantics { contentDescription = "结束同传" },
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null)
                    Text("结束", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun MicrophoneStatus(running: Boolean) {
    val motionEnabled = ValueAnimator.areAnimatorsEnabled()
    val transition = if (running && motionEnabled) rememberInfiniteTransition(label = "microphoneRipple") else null
    val rippleAlpha = transition?.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.24f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "rippleAlpha",
    )?.value ?: if (running) 0.18f else 0.08f
    val border = if (running && !motionEnabled) {
        Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), CircleShape)
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .size(48.dp)
            .then(border)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = rippleAlpha))
            .semantics { contentDescription = if (running) "麦克风正在收音" else "麦克风未收音" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ActionButton(action: InterpretationAction, onClick: () -> Unit) {
    val (label, icon) = when (action) {
        InterpretationAction.START -> "开始" to Icons.Filled.Mic
        InterpretationAction.PAUSE -> "暂停" to Icons.Filled.Pause
        InterpretationAction.RESUME -> "继续" to Icons.Filled.PlayArrow
        InterpretationAction.RESET -> interpretationActionLabel(action) to Icons.Filled.Refresh
        InterpretationAction.FINISH -> error("结束同传使用独立操作按钮")
    }
    Button(
        onClick = onClick,
        modifier = Modifier.height(48.dp).widthIn(min = 48.dp).semantics {
            contentDescription = when (action) {
                InterpretationAction.START -> "开始同传"
                InterpretationAction.PAUSE -> "暂停同传"
                InterpretationAction.RESUME -> "继续同传"
                InterpretationAction.RESET -> "重新开始翻译"
                InterpretationAction.FINISH -> "结束同传"
            }
        },
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Icon(icon, contentDescription = null)
        Text(label, modifier = Modifier.padding(start = 6.dp))
    }
}
