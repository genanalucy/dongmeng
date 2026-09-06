package com.verba.interpretation.ui.history

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.verba.interpretation.history.HistorySession
import com.verba.interpretation.history.HistoryTurn
import com.verba.interpretation.ui.HistoryEmptyStatePolicy
import com.verba.interpretation.ui.HistoryFilter
import com.verba.interpretation.ui.HistoryUiState
import com.verba.interpretation.ui.HistoryViewModel
import com.verba.interpretation.ui.TranslationLanguage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val historyTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    .withZone(ZoneId.systemDefault())

@Composable
fun HistoryPage(modifier: Modifier, viewModel: HistoryViewModel, sessionId: String? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var detailSessionId by remember(sessionId) { mutableStateOf(sessionId) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var titleDraft by remember { mutableStateOf("") }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { message ->
            snackbar.showSnackbar(message, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    val detailSession = state.sessions.firstOrNull { it.id == detailSessionId }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            UndoDeleteBar(
                state = state,
                onUndo = viewModel::undoDelete,
            )
        },
    ) { innerPadding ->
        if (detailSession != null) {
            HistoryDetail(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                session = detailSession,
                onBack = { detailSessionId = null },
                onShare = { shareText(context, viewModel.exportSession(detailSession.id), "分享会话") },
            )
        } else {
            HistorySummary(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                state = state,
                editingId = editingId,
                titleDraft = titleDraft,
                onQueryChange = viewModel::setQuery,
                onShareSearch = { shareText(context, viewModel.exportVisibleResults(), "分享搜索结果") },
                onShareAll = { shareText(context, viewModel.exportAll(), "分享全部历史") },
                onClear = viewModel::showClearConfirmation,
                onOpen = { detailSessionId = it },
                onStartRename = { id, title -> editingId = id; titleDraft = title },
                onTitleChange = { titleDraft = it },
                onSaveRename = { id -> if (viewModel.rename(id, titleDraft)) editingId = null },
                onDelete = viewModel::requestDelete,
            )
        }
    }

    if (state.clearConfirmationVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissClearConfirmation,
            title = { Text("清空全部历史？") },
            text = { Text("所有本机会话都会删除。删除后的同步结果需在联网后核验。") },
            confirmButton = { TextButton(onClick = viewModel::clearAll) { Text("清空") } },
            dismissButton = { TextButton(onClick = viewModel::dismissClearConfirmation) { Text("取消") } },
        )
    }
}

@Composable
private fun HistorySummary(
    modifier: Modifier,
    state: HistoryUiState,
    editingId: String?,
    titleDraft: String,
    onQueryChange: (String) -> Unit,
    onShareSearch: () -> Unit,
    onShareAll: () -> Unit,
    onClear: () -> Unit,
    onOpen: (String) -> Unit,
    onStartRename: (String, String) -> Unit,
    onTitleChange: (String) -> Unit,
    onSaveRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("历史", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text(
                        "记录保存在本机；同步状态需在联网后核验。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                IconButton(onClick = onShareSearch, enabled = state.visibleSessions.isNotEmpty()) {
                    Icon(Icons.Filled.Share, contentDescription = "分享搜索结果")
                }
                IconButton(onClick = onShareAll, enabled = state.sessions.isNotEmpty()) {
                    Icon(Icons.Filled.Share, contentDescription = "分享全部历史")
                }
                IconButton(onClick = onClear, enabled = state.sessions.isNotEmpty()) {
                    Icon(Icons.Filled.Delete, contentDescription = "清空历史")
                }
            }
        }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索历史") },
                placeholder = { Text("搜索标题、原文、译文或语言") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            )
        }
        if (state.visibleSessions.isEmpty()) {
            item { HistoryEmptyState(query = state.query, hasSessions = state.sessions.isNotEmpty()) }
        }
        items(state.visibleSessions, key = { it.id }) { session ->
            SessionSummaryCard(
                session = session,
                editing = editingId == session.id,
                titleDraft = if (editingId == session.id) titleDraft else session.title.orEmpty(),
                onOpen = { onOpen(session.id) },
                onStartRename = { onStartRename(session.id, session.title.orEmpty()) },
                onTitleChange = onTitleChange,
                onSaveRename = { onSaveRename(session.id) },
                onDelete = { onDelete(session.id) },
            )
        }
    }
}

@Composable
private fun SessionSummaryCard(
    session: HistorySession,
    editing: Boolean,
    titleDraft: String,
    onOpen: () -> Unit,
    onStartRename: () -> Unit,
    onTitleChange: (String) -> Unit,
    onSaveRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(enabled = !editing, onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (editing) {
                    OutlinedTextField(
                        value = titleDraft,
                        onValueChange = onTitleChange,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("会话标题") },
                    )
                } else {
                    Column(Modifier.weight(1f)) {
                        Text(session.displayTitle(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(formatTime(session.createdAtMillis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = if (editing) onSaveRename else onStartRename) {
                    Icon(Icons.Filled.Edit, contentDescription = if (editing) "保存标题" else "编辑标题")
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除会话") }
            }
            Text(session.languageSummary(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("${session.turns.size} 条记录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("查看详情", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun HistoryDetail(
    modifier: Modifier,
    session: HistorySession,
    onBack: () -> Unit,
    onShare: () -> Unit,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回历史") }
                Column(Modifier.weight(1f)) {
                    Text(session.displayTitle(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${formatTime(session.createdAtMillis)} · ${session.languageSummary()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onShare) { Icon(Icons.Filled.Share, contentDescription = "分享会话") }
            }
        }
        item { HorizontalDivider() }
        items(session.turns, key = { it.id }) { turn -> TurnDetailItem(turn) }
    }
}

@Composable
private fun TurnDetailItem(turn: HistoryTurn) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "${TranslationLanguage.displayName(turn.sourceLanguage)} → ${TranslationLanguage.displayName(turn.targetLanguage)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("原文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(turn.sourceText, style = MaterialTheme.typography.bodyMedium)
            Text("译文", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(turn.translatedText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun UndoDeleteBar(state: HistoryUiState, onUndo: (String) -> Unit) {
    if (state.pendingDeletes.isEmpty()) return
    Surface(shadowElevation = 4.dp, color = MaterialTheme.colorScheme.inverseSurface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            state.pendingDeletes.forEach { pending ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "已移除“${pending.session.displayTitle()}”，5 秒后删除",
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { onUndo(pending.session.id) }) { Text("撤销") }
                }
            }
        }
    }
}

@Composable
private fun HistoryEmptyState(query: String, hasSessions: Boolean) {
    val isSearch = query.isNotBlank() && hasSessions
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null, modifier = Modifier.padding(18.dp).size(30.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                if (isSearch) "没有找到结果" else "暂无本机记录",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                if (isSearch) HistoryEmptyStatePolicy.message(query, HistoryFilter.ALL) else "完成一次翻译后，记录会保存在这里。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "当前页面仅展示本机记录；同步状态需在联网后核验。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}

private fun HistorySession.displayTitle(): String = title?.takeIf { it.isNotBlank() } ?: if (kind == "face_to_face") "面对面翻译" else "同传翻译"

private fun HistorySession.languageSummary(): String = turns.map { turn ->
    "${TranslationLanguage.displayName(turn.sourceLanguage)} → ${TranslationLanguage.displayName(turn.targetLanguage)}"
}.distinct().joinToString("、").ifBlank { "语言待识别" }

private fun formatTime(millis: Long): String = historyTimeFormatter.format(Instant.ofEpochMilli(millis))

private fun shareText(context: Context, text: String, chooserTitle: String) {
    if (text.isBlank()) return
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), chooserTitle))
}
