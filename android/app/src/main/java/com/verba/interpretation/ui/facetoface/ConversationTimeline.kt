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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
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
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun ConversationTimeline(
    turns: List<FaceToFaceTurn>,
    activeMic: FaceToFaceSide?,
    listeningPlaceholder: String,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val token = remember(turns) { turns.map { listOf(it.id, it.sourceText, it.translatedText, it.finished) } }
    val atLatest by remember(listState) {
        androidx.compose.runtime.derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == turns.lastIndex
        }
    }
    val follow = remember { androidx.compose.runtime.mutableStateOf(ChatFollowState()) }
    LaunchedEffect(atLatest) {
        follow.value = ChatFollowPolicy.reduce(
            follow.value,
            if (atLatest) ChatFollowEvent.UserReachedLatest else ChatFollowEvent.UserLeftLatest,
        )
    }
    LaunchedEffect(token) {
        follow.value = ChatFollowPolicy.reduce(follow.value, ChatFollowEvent.TranscriptChanged(1))
        if (follow.value.followsLatest && turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex)
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
        if (activeMic != null) {
            item(key = "listening") { ListeningPlaceholder(activeMic, listeningPlaceholder) }
        }
    }
}

@Composable
private fun ConversationBubble(turn: FaceToFaceTurn) {
    val alignment = faceToFaceTurnAlignment(turn)
    val isRight = alignment == FaceToFaceTurnAlignment.END
    val sourceName = TranslationLanguage.displayName(turn.sourceLanguage)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isRight) Alignment.End else Alignment.Start,
    ) {
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
                Text(
                    turn.translatedText.ifBlank { "…" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                if (turn.translatedText.isNotBlank()) {
                    IconButton(
                        onClick = {},
                        modifier = Modifier.align(Alignment.End).semantics { contentDescription = "播放译文" },
                    ) { Icon(Icons.Filled.PlayArrow, contentDescription = null) }
                }
            }
        }
    }
}

@Composable
private fun ListeningPlaceholder(side: FaceToFaceSide, label: String) {
    val alignment = if (side == FaceToFaceSide.LEFT) Alignment.CenterStart else Alignment.CenterEnd
    Box(Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
