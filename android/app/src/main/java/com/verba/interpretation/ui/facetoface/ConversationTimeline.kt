package com.verba.interpretation.ui.facetoface

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verba.interpretation.ui.ChatFollowEvent
import com.verba.interpretation.ui.ChatFollowPolicy
import com.verba.interpretation.ui.ChatFollowState
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceTurn
import com.verba.interpretation.ui.TranslationLanguage

internal fun conversationTimelineLatestIndex(turnCount: Int, hasListeningPlaceholder: Boolean): Int =
    (turnCount + if (hasListeningPlaceholder) 1 else 0).coerceAtLeast(1) - 1

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
    val turnToken = remember(turns) { turns.map { "${it.id}:${it.sourceText}:${it.translatedText}:${it.finished}" } }
    var previousToken by remember { mutableStateOf<List<String>?>(null) }
    var previousHasPlaceholder by remember { mutableStateOf(hasListeningPlaceholder) }
    val latestIndex = conversationTimelineLatestIndex(turns.size, hasListeningPlaceholder)
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
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 184.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (turns.isEmpty()) {
            item {
                Text(
                    "对话会按双方方向显示在这里",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                )
            }
        }
        items(turns, key = { it.id }) { turn -> ConversationBubble(turn) }
        if (hasListeningPlaceholder) item(key = "listening") { ListeningPlaceholder(activeMic, listeningPlaceholder) }
    }
}

@Composable
private fun ConversationBubble(turn: FaceToFaceTurn) {
    val isRight = faceToFaceTurnAlignment(turn) == FaceToFaceTurnAlignment.END
    val sourceName = TranslationLanguage.displayName(turn.sourceLanguage)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isRight) Alignment.End else Alignment.Start) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp).semantics {
                contentDescription = "$sourceName 对话。原文${turn.sourceText.ifBlank { "等待识别" }}。译文${turn.translatedText.ifBlank { "等待翻译" }}"
            },
            shape = RoundedCornerShape(20.dp),
            color = if (isRight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            tonalElevation = if (isRight) 0.dp else 1.dp,
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(sourceName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(turn.sourceText.ifBlank { "…" }, style = MaterialTheme.typography.bodyLarge)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(turn.translatedText.ifBlank { "…" }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
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
