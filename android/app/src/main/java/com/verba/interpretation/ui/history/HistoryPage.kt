package com.verba.interpretation.ui.history

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.verba.interpretation.ui.HistoryEmptyStatePolicy
import com.verba.interpretation.ui.HistoryFilter
import com.verba.interpretation.ui.HistoryViewModel
import com.verba.interpretation.ui.TranslationLanguage

@Composable
fun HistoryPage(modifier: Modifier, viewModel: HistoryViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var editingId by remember { mutableStateOf<String?>(null) }
    var titleDraft by remember { mutableStateOf("") }
    LaunchedEffect(state.pendingDelete?.id) {
        if (state.pendingDelete != null) {
            val action = snackbar.showSnackbar("已移至删除队列", "撤销", withDismissAction = true)
            if (action == androidx.compose.material3.SnackbarResult.ActionPerformed) viewModel.undoDelete() else viewModel.confirmDelete()
        }
    }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { inner -> LazyColumn(
        modifier = modifier.fillMaxSize().padding(inner), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("回看每一次交流", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                    Text("已完成的文字翻译会在所有已登录设备间同步；不保存音频。", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                IconButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, viewModel.export()), "分享全部历史")) }, enabled = state.visibleSessions.isNotEmpty()) { Icon(Icons.Filled.Share, "分享全部") }
                IconButton(onClick = viewModel::showClearConfirmation, enabled = state.sessions.isNotEmpty()) { Icon(Icons.Filled.Delete, "清空历史") }
            }
        }
        item { OutlinedTextField(value = state.query, onValueChange = viewModel::setQuery, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("搜索历史") }, placeholder = { Text("搜索原文、译文或语言") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }) }
        if (state.visibleSessions.isEmpty()) item { HistoryEmptyState(state.query, HistoryFilter.ALL) }
        items(state.visibleSessions, key = { it.id }) { session ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (editingId == session.id) OutlinedTextField(titleDraft, { titleDraft = it }, Modifier.weight(1f), singleLine = true, label = { Text("会话标题") }) else Column(Modifier.weight(1f)) {
                        Text(session.title ?: if (session.kind == "face_to_face") "面对面翻译" else "同传翻译", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("${session.turns.size} 条完成记录", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { if (editingId == session.id) { viewModel.rename(session.id, titleDraft); editingId = null } else { editingId = session.id; titleDraft = session.title.orEmpty() } }) { Icon(Icons.Filled.Edit, if (editingId == session.id) "保存标题" else "编辑标题") }
                    IconButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, viewModel.export(session)), "分享历史")) }) { Icon(Icons.Filled.Share, "分享会话") }
                    IconButton(onClick = { viewModel.requestDelete(session.id) }) { Icon(Icons.Filled.Delete, "删除会话") }
                }
                session.turns.forEach { turn ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) { Column(Modifier.padding(12.dp)) {
                        Text("${TranslationLanguage.displayName(turn.sourceLanguage)} → ${TranslationLanguage.displayName(turn.targetLanguage)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(turn.sourceText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                        Text(turn.translatedText, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                    } }
                }
            } }
        }
    } }
    if (state.clearConfirmationVisible) AlertDialog(onDismissRequest = viewModel::dismissClearConfirmation, title = { Text("清空全部历史？") }, text = { Text("所有本机会话将被删除，并同步删除云端记录。") }, confirmButton = { TextButton(viewModel::clearAll) { Text("清空") } }, dismissButton = { TextButton(viewModel::dismissClearConfirmation) { Text("取消") } })
}

@Composable
private fun HistoryEmptyState(query: String, filter: HistoryFilter) {
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
            Text("暂无记录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
            Text(
                HistoryEmptyStatePolicy.message(query, filter),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "当前版本不会展示云端或其他设备的数据",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}
