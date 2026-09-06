package com.verba.interpretation.ui.facetoface

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceTurn
import com.verba.interpretation.ui.TranslationLanguage
import com.verba.interpretation.ui.display.EventBoundaryDisplay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal fun conversationTimelineLatestIndex(turnCount: Int, hasListeningPlaceholder: Boolean): Int =
    (turnCount + if (hasListeningPlaceholder) 1 else 0).coerceAtLeast(1) - 1

internal data class ConversationDisplayBubble(
    val key: String,
    val sourceText: String?,
    val translationText: String,
    val side: FaceToFaceSide,
    val sourceLanguage: String,
    val targetLanguage: String,
    val alignment: FaceToFaceTurnAlignment,
    val isLive: Boolean = false,
    val livePhase: FaceToFacePhase? = null,
)

/**
 * Maps server subtitle events to stable timeline articles. An unfinished turn remains the
 * same article while partial source/translation text changes, and becomes history only when
 * the coordinator marks the turn finished.
 */
internal fun displayConversationBubbles(
    turns: List<FaceToFaceTurn>,
    phase: FaceToFacePhase = FaceToFacePhase.IDLE,
    activeTurnId: Long? = null,
): List<ConversationDisplayBubble> {
    val liveTurnId = activeTurnId ?: activeConversationTurnId(turns, phase)
    return turns.filter { turn ->
        turn.finished || phase != FaceToFacePhase.ERROR
    }.flatMap { turn ->
        val alignment = faceToFaceTurnAlignment(turn)
        val live = turn.id == liveTurnId
    if (live) {
        // Keep one stable article for the active turn. Source and translation partials are
        // rendered in the same bilingual bubble rather than as two unrelated rows.
        listOf(
            ConversationDisplayBubble(
                // A single-turn live article uses the first historical row key so completion
                // updates the same LazyColumn item instead of replacing it.
                key = conversationBubbleKey(turn.id, "0"),
                sourceText = turn.sourceText.takeIf(String::isNotBlank),
                translationText = turn.translatedText,
                side = turn.side,
                sourceLanguage = turn.sourceLanguage,
                targetLanguage = turn.targetLanguage,
                alignment = alignment,
                isLive = true,
                livePhase = phase,
            ),
        )
    } else {
        val hasFinalRows = turn.sourceFinals.isNotEmpty() || turn.translationFinals.isNotEmpty()
        EventBoundaryDisplay.rows(
            sourceFinals = turn.sourceFinals,
            sourcePartial = turn.sourcePartial,
            translationFinals = turn.translationFinals,
            translationPartial = turn.translationPartial,
        ).map { row ->
            ConversationDisplayBubble(
                key = conversationBubbleKey(turn.id, row.key, hasFinalRows),
                sourceText = row.sourceText,
                translationText = row.translationText,
                side = turn.side,
                sourceLanguage = turn.sourceLanguage,
                targetLanguage = turn.targetLanguage,
                alignment = alignment,
            )
        }
        }
    }
}

/** Compatibility fallback for callers that do not yet provide the coordinator's active turn ID. */
internal fun activeConversationTurnId(
    turns: List<FaceToFaceTurn>,
    phase: FaceToFacePhase,
): Long? = if (phase == FaceToFacePhase.LISTENING || phase == FaceToFacePhase.PROCESSING) {
    turns.asReversed().firstOrNull { !it.finished }?.id
} else {
    null
}

private fun conversationBubbleKey(turnId: Long, rowKey: String, hasFinalRows: Boolean = false): String =
    if (rowKey == "0" || (!hasFinalRows && (rowKey == "source-partial" || rowKey == "translation-partial"))) "$turnId:0" else "$turnId:$rowKey"

internal fun conversationTimelineUpdateCount(
    previousTurnToken: List<String>,
    currentTurnToken: List<String>,
    previousHasListeningPlaceholder: Boolean,
    hasListeningPlaceholder: Boolean,
): Int = when {
    currentTurnToken != previousTurnToken -> (currentTurnToken.size - previousTurnToken.size).coerceAtLeast(1)
    hasListeningPlaceholder != previousHasListeningPlaceholder -> 1
    else -> 0
}

@Composable
internal fun ConversationTimeline(
    turns: List<FaceToFaceTurn>,
    activeMic: FaceToFaceSide?,
    listeningPlaceholder: String,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    phase: FaceToFacePhase = FaceToFacePhase.IDLE,
    activeTurnId: Long? = null,
) {
    // The old arguments remain source-compatible for continuous mode callers. Live content is
    // now represented by the actual unfinished turn, never by a fixed input row.
    val bubbles = remember(turns, phase, activeTurnId) { displayConversationBubbles(turns, phase, activeTurnId) }
    val turnToken = remember(bubbles) { bubbles.map { "${it.key}:${it.sourceText}:${it.translationText}:${it.isLive}" } }
    val scope = rememberCoroutineScope()
    var previousToken by remember { mutableStateOf<List<String>?>(null) }
    var previousHasPlaceholder by remember { mutableStateOf(false) }
    val latestIndex = conversationTimelineLatestIndex(turnCount = bubbles.size, hasListeningPlaceholder = false)
    val currentLatestIndex by rememberUpdatedState(latestIndex)
    var follow by remember { mutableStateOf(ConversationTimelineFollowState()) }
    var programmaticScrollCount by remember { mutableStateOf(0) }
    var userDraggedDuringScroll by remember { mutableStateOf(false) }

    suspend fun animateToLatest(index: Int) {
        programmaticScrollCount += 1
        try {
            listState.animateScrollToItem(index)
        } finally {
            programmaticScrollCount -= 1
            follow = ConversationTimelineFollowReducer.reduce(follow, ConversationTimelineFollowEvent.ProgrammaticScrollFinished)
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
                isScrolling && !wasScrolling -> scrollStartedProgrammatically = programmaticScrollCount > 0
                !isScrolling && wasScrolling -> {
                    if (!scrollStartedProgrammatically || userDraggedDuringScroll) {
                        val atLatest = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                            ?.let { it >= currentLatestIndex } ?: true
                        follow = ConversationTimelineFollowReducer.reduce(follow, ConversationTimelineFollowEvent.UserScrollFinished(atLatest))
                    }
                    userDraggedDuringScroll = false
                }
            }
            wasScrolling = isScrolling
        }
    }
    LaunchedEffect(turnToken, latestIndex) {
        val before = previousToken
        val updates = if (before == null) 1 else conversationTimelineUpdateCount(before, turnToken, previousHasPlaceholder, false)
        if (updates > 0) {
            follow = ConversationTimelineFollowReducer.reduce(follow, ConversationTimelineFollowEvent.TranscriptAppended(updates))
            if (follow.scrollToLatestRequested) {
                follow = ConversationTimelineFollowReducer.reduce(follow, ConversationTimelineFollowEvent.ScrollRequestStarted)
                animateToLatest(latestIndex)
            }
        }
        previousToken = turnToken
        previousHasPlaceholder = false
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
        ) {
            if (bubbles.isEmpty() && phase != FaceToFacePhase.ERROR) {
                item {
                    Text(
                        "按住对应麦克风开始对话",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 28.dp),
                    )
                }
            }
            items(bubbles, key = { it.key }) { bubble -> ConversationBubble(bubble) }
            if (phase == FaceToFacePhase.ERROR) {
                item(key = "conversation-error") {
                    Text(
                        "当前会话已停止，请从下方重新开始。",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 18.dp),
                    )
                }
            }
        }
        if (!follow.followsLatest && bubbles.isNotEmpty()) {
            FloatingActionButton(
                onClick = {
                    follow = ConversationTimelineFollowReducer.reduce(follow, ConversationTimelineFollowEvent.UserTappedLatest)
                    if (follow.scrollToLatestRequested) {
                        follow = ConversationTimelineFollowReducer.reduce(follow, ConversationTimelineFollowEvent.ScrollRequestStarted)
                        scope.launch { animateToLatest(latestIndex) }
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 16.dp).height(48.dp).widthIn(min = 48.dp, max = 48.dp),
            ) { Icon(Icons.Filled.ArrowDownward, contentDescription = "回到最新对话") }
        }
    }
}

@Composable
private fun ConversationBubble(bubble: ConversationDisplayBubble) {
    val isRight = bubble.alignment == FaceToFaceTurnAlignment.END
    val sourceLanguage = TranslationLanguage.displayName(bubble.sourceLanguage)
    val targetLanguage = TranslationLanguage.displayName(bubble.targetLanguage)
    val liveLabel = when (bubble.livePhase) {
        FaceToFacePhase.LISTENING -> "${earLabel(bubble.side)} · 收音中"
        FaceToFacePhase.PROCESSING -> "${earLabel(bubble.side)} · 翻译中"
        else -> null
    }
    val targetEar = targetEarLabel(bubble.side)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isRight) Alignment.End else Alignment.Start) {
        Surface(
            modifier = Modifier.widthIn(max = 360.dp).semantics {
                contentDescription = listOfNotNull(
                    liveLabel,
                    bubble.sourceText?.let { "$sourceLanguage 原文。$it" },
                    bubble.translationText.takeIf { it.isNotBlank() }?.let { "$targetLanguage 译文。$it" },
                    "译音送至$targetEar",
                ).joinToString(" ")
            },
            shape = RoundedCornerShape(22.dp),
            color = if (bubble.isLive) {
                if (isRight) ConversationColors.rightLive else ConversationColors.leftLive
            } else ConversationColors.history,
            border = BorderStroke(
                1.dp,
                if (bubble.isLive) {
                    if (isRight) ConversationColors.rightAccent else ConversationColors.leftAccent
                } else ConversationColors.historyBorder,
            ),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
                liveLabel?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = if (isRight) ConversationColors.rightAccent else ConversationColors.leftAccent, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                }
                if (bubble.sourceText != null) {
                    Text(bubble.sourceText, style = MaterialTheme.typography.bodyLarge, color = ConversationColors.ink)
                } else if (bubble.isLive && bubble.livePhase == FaceToFacePhase.LISTENING) {
                    Text("原文正在识别…", style = MaterialTheme.typography.bodyLarge, color = ConversationColors.muted)
                } else {
                    Spacer(Modifier.height(25.dp))
                }
                Spacer(Modifier.height(9.dp))
                Spacer(Modifier.fillMaxWidth().height(1.dp).background(ConversationColors.divider))
                Spacer(Modifier.height(9.dp))
                Text(
                    bubble.translationText.takeIf { it.isNotBlank() }
                        ?: if (bubble.isLive) "译文将在识别完成后显示" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ConversationColors.translation,
                )
                Text(
                    "译音 → $targetEar",
                    style = MaterialTheme.typography.labelSmall,
                    color = ConversationColors.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

private object ConversationColors {
    val history = androidx.compose.ui.graphics.Color(0xFF202630)
    val historyBorder = androidx.compose.ui.graphics.Color(0xFF37414E)
    val leftLive = androidx.compose.ui.graphics.Color(0xFF182533)
    val rightLive = androidx.compose.ui.graphics.Color(0xFF28272A)
    val leftAccent = androidx.compose.ui.graphics.Color(0xFF91B5D5)
    val rightAccent = androidx.compose.ui.graphics.Color(0xFFE0BC83)
    val ink = androidx.compose.ui.graphics.Color(0xFFF5F5F2)
    val muted = androidx.compose.ui.graphics.Color(0xFFABB5C3)
    val translation = androidx.compose.ui.graphics.Color(0xFFFFC46B)
    val divider = androidx.compose.ui.graphics.Color(0xFF48515E)
}
