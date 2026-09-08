package com.verba.interpretation.ui.facetoface

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceTurn
import com.verba.interpretation.ui.TranslationLanguage
import com.verba.interpretation.ui.design.ConversationTimelineVisualSpec
import com.verba.interpretation.ui.design.TranslationVisualTokens
import com.verba.interpretation.ui.display.EventBoundaryDisplay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal fun conversationTimelineLatestIndex(turnCount: Int, hasListeningPlaceholder: Boolean): Int =
    (turnCount + if (hasListeningPlaceholder) 1 else 0).coerceAtLeast(1) - 1

internal data class ConversationDisplayBubble(
    val key: String,
    val sourceText: String?,
    val translationText: String?,
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
        val live = turn.id == liveTurnId && !turn.finished
    if (live) {
        // Keep one stable article for the active turn. Source and translation partials are
        // rendered in the same bilingual bubble rather than as two unrelated rows.
        listOf(
            ConversationDisplayBubble(
                // A single-turn live article uses the first historical row key so completion
                // updates the same LazyColumn item instead of replacing it.
                key = conversationBubbleKey(turn.id, "0"),
                sourceText = turn.sourceText.takeIf(String::isNotBlank),
                translationText = turn.translatedText.takeIf(String::isNotBlank),
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
    contentDescription: String = "对话记录",
    visualSpec: ConversationTimelineVisualSpec = ConversationTimelineVisualSpec.Conversation,
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
            modifier = Modifier.fillMaxSize().semantics { this.contentDescription = contentDescription },
            contentPadding = PaddingValues(
                start = visualSpec.horizontalPadding,
                top = 12.dp,
                end = visualSpec.horizontalPadding,
                bottom = 5.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(visualSpec.turnSpacing),
        ) {
            items(bubbles, key = { it.key }) { bubble -> ConversationBubble(bubble, visualSpec) }
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
private fun ConversationBubble(
    bubble: ConversationDisplayBubble,
    visualSpec: ConversationTimelineVisualSpec,
) {
    val isRight = bubble.alignment == FaceToFaceTurnAlignment.END
    val isLive = bubble.isLive
    // Static bubbles read MaterialTheme.colorScheme (BrandDarkColors keeps the exact legacy
    // pixels in dark mode); live bubbles keep the theme-stable ear-identity colors below.
    val colorScheme = MaterialTheme.colorScheme
    val sourceColor = if (isLive) ConversationColors.liveInk else colorScheme.onSurface
    val translationColor = if (isLive) ConversationColors.liveTranslation else colorScheme.primary
    val sourceLanguage = TranslationLanguage.displayName(bubble.sourceLanguage)
    val targetLanguage = TranslationLanguage.displayName(bubble.targetLanguage)
    val targetEar = targetEarLabel(bubble.side)
    val sourceLineHeight = visualSpec.sourceLineHeight.value.dp
    val liveLabel = when (bubble.livePhase) {
        FaceToFacePhase.LISTENING -> "${earLabel(bubble.side)} · 收音中"
        FaceToFacePhase.PROCESSING -> "${earLabel(bubble.side)} · 翻译中"
        else -> null
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isRight) Alignment.End else Alignment.Start) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isRight) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (isRight) 54.dp else 0.dp, end = if (isRight) 0.dp else 54.dp)
                .semantics {
                contentDescription = listOfNotNull(
                    liveLabel,
                    bubble.sourceText?.let { "$sourceLanguage 原文。$it" },
                    bubble.translationText?.let { "$targetLanguage 译文。$it" },
                    "译音送至$targetEar",
                ).joinToString(" ")
            },
            shape = RoundedCornerShape(
                topStart = TranslationVisualTokens.BubbleRadius,
                topEnd = TranslationVisualTokens.BubbleRadius,
                bottomStart = if (isRight) TranslationVisualTokens.BubbleRadius else TranslationVisualTokens.BubbleTailRadius,
                bottomEnd = if (isRight) TranslationVisualTokens.BubbleTailRadius else TranslationVisualTokens.BubbleRadius,
            ),
            color = if (isLive) {
                if (isRight) ConversationColors.rightLive else ConversationColors.leftLive
            } else colorScheme.surfaceContainer,
            border = BorderStroke(
                1.dp,
                if (isLive) {
                    if (isRight) ConversationColors.rightAccent else ConversationColors.leftAccent
                } else colorScheme.outline,
            ),
        ) {
            Column(Modifier.padding(horizontal = visualSpec.bubbleHorizontalPadding, vertical = visualSpec.bubbleVerticalPadding)) {
                Box(Modifier.fillMaxWidth().height(22.dp)) {
                    if (liveLabel != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LiveWaveform(color = if (isRight) ConversationColors.rightAccent else ConversationColors.leftAccent)
                            Text(liveLabel, fontSize = 11.sp, lineHeight = 16.sp, color = if (isRight) ConversationColors.rightAccent else ConversationColors.leftAccent, fontWeight = FontWeight.Medium)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (bubble.sourceText != null) {
                    LiveText(
                        text = bubble.sourceText,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = visualSpec.sourceFontSize,
                            lineHeight = visualSpec.sourceLineHeight,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = sourceColor,
                        cursor = bubble.isLive && bubble.livePhase == FaceToFacePhase.LISTENING,
                    )
                } else {
                    EmptyLiveLine(
                        height = sourceLineHeight,
                        color = sourceColor,
                        cursor = bubble.isLive && bubble.livePhase == FaceToFacePhase.LISTENING,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Spacer(Modifier.fillMaxWidth().height(1.dp).background(if (isLive) ConversationColors.liveDivider else colorScheme.outlineVariant))
                Spacer(Modifier.height(12.dp))
                bubble.translationText?.let { translation ->
                    LiveText(
                        text = translation,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontSize = visualSpec.translationFontSize,
                            lineHeight = visualSpec.translationLineHeight,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = translationColor,
                        cursor = bubble.isLive && bubble.livePhase == FaceToFacePhase.PROCESSING,
                        modifier = Modifier.heightIn(min = 42.dp),
                    )
                } ?: EmptyLiveLine(
                    height = 42.dp,
                    color = translationColor,
                    cursor = bubble.isLive && bubble.livePhase == FaceToFacePhase.PROCESSING,
                )
            }
        }
        // No playback callback is available in this model. Keep the outside slot empty rather than
        // exposing a fake control or changing the article's geometry when playback is added.
        Spacer(Modifier.width(TranslationVisualTokens.BubbleGap))
        Spacer(Modifier.width(TranslationVisualTokens.BubbleOuterSlot).height(62.dp))
        }
    }
}

@Composable
private fun LiveWaveform(color: Color) {
    Canvas(Modifier.width(22.dp).height(17.dp)) {
        val barWidth = 2.dp.toPx()
        val gap = 3.dp.toPx()
        val heights = floatArrayOf(8.dp.toPx(), 13.dp.toPx(), 17.dp.toPx(), 11.dp.toPx(), 8.dp.toPx())
        heights.forEachIndexed { index, height ->
            val x = index * (barWidth + gap)
            drawRoundRect(
                color = color,
                topLeft = androidx.compose.ui.geometry.Offset(x, (size.height - height) / 2),
                size = androidx.compose.ui.geometry.Size(barWidth, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx(), 1.dp.toPx()),
            )
        }
    }
}

@Composable
private fun LiveText(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    cursor: Boolean,
    modifier: Modifier = Modifier,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val cursorWidth = with(LocalDensity.current) { 2.dp.toPx() }
    Text(
        text = text,
        style = style,
        color = color,
        modifier = modifier.drawBehind {
            if (cursor && layout != null) {
                val line = layout!!.lineCount - 1
                val top = layout!!.getLineTop(line)
                val bottom = layout!!.getLineBottom(line)
                drawRect(color, androidx.compose.ui.geometry.Offset(layout!!.getLineRight(line) + 2.dp.toPx(), top), androidx.compose.ui.geometry.Size(cursorWidth, bottom - top))
            }
        },
        onTextLayout = { layout = it },
    )
}

@Composable
private fun EmptyLiveLine(height: androidx.compose.ui.unit.Dp, color: Color, cursor: Boolean) {
    Box(Modifier.fillMaxWidth().height(height).drawBehind {
        if (cursor) drawRect(color, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
    })
}

/**
 * Ear-identity colors for the live (still-streaming) turn bubble. The left/right live
 * surfaces and accents have no MaterialTheme.colorScheme role; they are the brand's
 * active-capture highlight and are intentionally theme-stable (see VerbaColors), with
 * light content kept alongside them so the pairs stay readable in dark and light mode.
 */
private object ConversationColors {
    val leftLive = androidx.compose.ui.graphics.Color(0xFF182533)
    val rightLive = androidx.compose.ui.graphics.Color(0xFF28272A)
    val leftAccent = androidx.compose.ui.graphics.Color(0xFF91B5D5)
    val rightAccent = androidx.compose.ui.graphics.Color(0xFFE0BC83)
    val liveInk = androidx.compose.ui.graphics.Color(0xFFF5F5F2)
    val liveTranslation = androidx.compose.ui.graphics.Color(0xFFFFC46B)
    val liveDivider = androidx.compose.ui.graphics.Color(0xFF48515E)
}
