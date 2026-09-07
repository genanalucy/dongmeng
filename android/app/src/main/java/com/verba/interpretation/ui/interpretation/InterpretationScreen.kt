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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.sp
import com.verba.interpretation.ui.SessionPhase
import com.verba.interpretation.ui.design.TranslationVisualTokens
import com.verba.interpretation.ui.design.VerbaColors
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
    overlayContent: @Composable BoxScope.() -> Unit = {},
) {
    val callbacks = InterpretationCallbacks(onExit, onStart, onPause, onResume, onFinish, onReset)
    val isSessionActive = model.phase in setOf(
        SessionPhase.STARTING,
        SessionPhase.RUNNING,
        SessionPhase.PAUSED,
        SessionPhase.STOPPING,
    )
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
            statusLabel = model.statusLabel,
            phase = model.phase,
            sessionActive = isSessionActive,
            microphoneRunning = model.showMicrophoneRipple,
            onExit = { InterpretationActionDispatcher.exit(callbacks) },
        )
        // Keep long transcript/error content scrollable so the pinned controls remain reachable.
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(model.bubbles, key = InterpretationDisplayBubble::key) { bubble ->
                    InterpretationBubble(bubble)
                }
                model.errorMessage?.let { error ->
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = VerbaColors.ErrorSurface)) {
                            Text(
                                text = error,
                                modifier = Modifier.padding(16.dp),
                                color = VerbaColors.Danger,
                            )
                        }
                    }
                }
            }
            overlayContent()
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
            primaryAction = model.primaryAction,
            statusLabel = model.statusLabel,
            microphoneRunning = model.showMicrophoneRipple,
            phase = model.phase,
            onAction = { action -> InterpretationActionDispatcher.dispatch(action, callbacks) },
        )
    }
}

@Composable
private fun CompactHeader(
    languageDirection: String,
    statusLabel: String,
    phase: SessionPhase,
    sessionActive: Boolean,
    microphoneRunning: Boolean,
    onExit: () -> Unit,
) {
    val languages = languageDirection.split(" → ", limit = 2)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(Modifier.height(TranslationVisualTokens.TopBarHeight), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onExit, modifier = Modifier.semantics { contentDescription = "退出实时同传" }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = VerbaColors.Ink)
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("实时同传", fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, color = VerbaColors.Ink)
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (sessionActive && phase in setOf(SessionPhase.RUNNING, SessionPhase.PAUSED)) LiveMarker(microphoneRunning)
            }
        }
        Row(
            Modifier.fillMaxWidth().height(TranslationVisualTokens.InterpretationDirectionRowHeight),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(languages.firstOrNull().orEmpty(), fontSize = 13.sp, lineHeight = 18.sp, color = VerbaColors.Muted)
            Text(" → ", fontSize = 13.sp, lineHeight = 18.sp, color = VerbaColors.Muted)
            Text(languages.getOrNull(1).orEmpty(), fontSize = 13.sp, lineHeight = 18.sp, color = VerbaColors.Translation)
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
        shape = RoundedCornerShape(18.dp),
        color = VerbaColors.Canvas,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = VerbaColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InterpretationEmptyState(phase: SessionPhase, statusLabel: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = VerbaColors.Canvas,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun InterpretationBubble(bubble: InterpretationDisplayBubble) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = listOfNotNull(
                bubble.translationText?.let { "译文。$it" },
                bubble.sourceText?.let { "原文。$it" },
            ).joinToString(" ")
        },
        shape = RoundedCornerShape(22.dp),
        color = VerbaColors.History,
        border = BorderStroke(1.dp, VerbaColors.ShellStroke),
    ) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 16.dp)) {
            if (bubble.sourceText != null) {
                Text(
                    text = bubble.sourceText,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium),
                    color = VerbaColors.Ink,
                )
            } else {
                Text(
                    text = "",
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 19.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    minLines = 1,
                    color = VerbaColors.Ink,
                )
            }
            Spacer(Modifier.height(12.dp))
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(VerbaColors.Divider))
            Spacer(Modifier.height(12.dp))
            bubble.translationText?.let { translation ->
                Text(
                    text = translation,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.Medium),
                    color = VerbaColors.Translation,
                    modifier = Modifier.heightIn(min = TranslationVisualTokens.TranslationMinHeight),
                )
            } ?: Spacer(Modifier.height(TranslationVisualTokens.TranslationMinHeight))
        }
    }
}

@Composable
private fun PinnedControls(
    actions: List<InterpretationAction>,
    primaryAction: InterpretationAction?,
    statusLabel: String,
    microphoneRunning: Boolean,
    phase: SessionPhase,
    onAction: (InterpretationAction) -> Unit,
) {
    val finishAction = actions.firstOrNull { it == InterpretationAction.FINISH }
    val visiblePrimary = primaryAction?.takeIf { it in actions }
    Surface(
        modifier = Modifier.fillMaxWidth().height(76.dp),        color = VerbaColors.Canvas,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (visiblePrimary != null) ActionButton(visiblePrimary, onClick = { onAction(visiblePrimary) })
            if (visiblePrimary == null && statusLabel.isNotBlank()) {
                Text(statusLabel, color = VerbaColors.Muted, fontSize = 13.sp, lineHeight = 18.sp)
            }
            if (finishAction != null) {
                if (visiblePrimary != null) Spacer(Modifier.width(12.dp))
                OutlinedButton(
                    onClick = { onAction(finishAction) },
                    modifier = Modifier
                        .widthIn(min = TranslationVisualTokens.SecondaryActionWidth)
                        .height(48.dp)
                        .semantics {
                            contentDescription = if (phase == SessionPhase.STARTING) "取消连接" else "结束同传"
                        },
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = VerbaColors.TopControl,
                        contentColor = VerbaColors.Ink,
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(22.dp))
                    Text(if (phase == SessionPhase.STARTING) "取消" else "结束", modifier = Modifier.padding(start = 6.dp))
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
        modifier = Modifier
            .then(if (action == InterpretationAction.RESET) Modifier.widthIn(min = TranslationVisualTokens.PrimaryActionMinWidth) else Modifier.width(TranslationVisualTokens.PrimaryActionMinWidth))
            .height(48.dp)
            .semantics {
            contentDescription = when (action) {
                InterpretationAction.START -> "开始同传"
                InterpretationAction.PAUSE -> "暂停同传"
                InterpretationAction.RESUME -> "继续同传"
                InterpretationAction.RESET -> "重新开始翻译"
                InterpretationAction.FINISH -> "结束同传"
            }
        },
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = VerbaColors.LeftMic, contentColor = VerbaColors.Canvas),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Text(label, modifier = Modifier.padding(start = 6.dp))
    }
}
