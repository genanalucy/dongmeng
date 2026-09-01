package com.verba.interpretation.ui.facetoface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.verba.interpretation.ui.ChatFollowEvent
import com.verba.interpretation.ui.ChatFollowPolicy
import com.verba.interpretation.ui.ChatFollowState
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceTurn
import com.verba.interpretation.ui.TranslationLanguage
import com.verba.interpretation.ui.display.EventBoundaryDisplay

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
)

internal fun displayConversationBubbles(turns: List<FaceToFaceTurn>): List<ConversationDisplayBubble> =
    turns.flatMap { turn ->
        val alignment = faceToFaceTurnAlignment(turn)
        EventBoundaryDisplay.rows(
            sourceFinals = turn.sourceFinals,
            sourcePartial = turn.sourcePartial,
            translationFinals = turn.translationFinals,
            translationPartial = turn.translationPartial,
        ).map { row ->
            ConversationDisplayBubble(
                key = "${turn.id}:${row.key}",
                sourceText = row.sourceText,
                translationText = row.translationText,
                side = turn.side,
                sourceLanguage = turn.sourceLanguage,
                targetLanguage = turn.targetLanguage,
                alignment = alignment,
            )
        }
    }

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
) {
    val hasListeningPlaceholder = activeMic != null
    val bubbles = remember(turns) { displayConversationBubbles(turns) }
    val turnToken = remember(bubbles, turns) { bubbles.map { "${it.key}:${it.sourceText}:${it.translationText}" } + turns.filter { it.sourceText.isBlank() && it.translatedText.isBlank() }.map { "${it.id}:empty" } }
    var previousToken by remember { mutableStateOf<List<String>?>(null) }
    var previousHasPlaceholder by remember { mutableStateOf(hasListeningPlaceholder) }
    val latestIndex = conversationTimelineLatestIndex(turnCount = bubbles.size, hasListeningPlaceholder = hasListeningPlaceholder)
    val atLatest by remember(listState, latestIndex) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= latestIndex } ?: true
        }
    }
    val follow = remember { mutableStateOf(ChatFollowState()) }
    LaunchedEffect(atLatest) {
        follow.value = ChatFollowPolicy.reduce(
            follow.value,
            if (atLatest) ChatFollowEvent.UserReachedLatest else ChatFollowEvent.UserLeftLatest,
        )
    }
    LaunchedEffect(turnToken, hasListeningPlaceholder) {
        val before = previousToken
        val updates = if (before == null) 0 else conversationTimelineUpdateCount(
            before,
            turnToken,
            previousHasPlaceholder,
            hasListeningPlaceholder,
        )
        if (updates > 0) {
            follow.value = ChatFollowPolicy.reduce(follow.value, ChatFollowEvent.TranscriptChanged(updates))
            if (follow.value.followsLatest) listState.animateScrollToItem(latestIndex)
        }
        previousToken = turnToken
        previousHasPlaceholder = hasListeningPlaceholder
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (turns.isEmpty() && !hasListeningPlaceholder) {
            item {
                Text(
                    "对话会按双方方向显示在这里",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                )
            }
        }
        items(bubbles, key = { it.key }) { bubble -> ConversationBubble(bubble) }
        if (hasListeningPlaceholder) item(key = "listening") { ListeningPlaceholder(activeMic, listeningPlaceholder) }
    }
}

@Composable
private fun ConversationBubble(bubble: ConversationDisplayBubble) {
    val isRight = bubble.alignment == FaceToFaceTurnAlignment.END
    val sourceLanguage = TranslationLanguage.displayName(bubble.sourceLanguage)
    val targetLanguage = TranslationLanguage.displayName(bubble.targetLanguage)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isRight) Alignment.End else Alignment.Start) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp).semantics {
                contentDescription = listOfNotNull(
                    bubble.sourceText?.let { "$sourceLanguage 原文。$it" },
                    "$targetLanguage 译文。${bubble.translationText}",
                ).joinToString(" ")
            },
            shape = RoundedCornerShape(20.dp),
            color = if (isRight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            border = if (isRight) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(14.dp)) {
                bubble.sourceText?.let { source ->
                    Text(source, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(9.dp))
                }
                if (bubble.sourceText != null) Spacer(
                    Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant),
                )
                if (bubble.sourceText != null) Spacer(Modifier.height(9.dp))
                Text(
                    bubble.translationText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isRight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ListeningPlaceholder(side: FaceToFaceSide, label: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (side == FaceToFaceSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}
