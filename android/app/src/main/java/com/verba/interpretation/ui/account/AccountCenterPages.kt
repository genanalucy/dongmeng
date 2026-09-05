package com.verba.interpretation.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.verba.interpretation.cloud.AccountOverview
import com.verba.interpretation.cloud.UsagePage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountUsageScreen(overview: AccountOverview?, usage: UsagePage?, loading: Boolean, message: String?, onBack: () -> Unit, onLoadMore: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("使用与权益") }, navigationIcon = { BackButton(onBack) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { val entitlement = overview?.entitlement; Text("权益详情", style = MaterialTheme.typography.titleMedium); Text(entitlement?.let { "${it.kind} · 到期时间 ${it.expiresAt} · 剩余 ${formatDuration(it.remainingSeconds)}" } ?: "暂无可用权益") }
            item { Text("使用统计", style = MaterialTheme.typography.titleMedium); val summary = overview?.usage; Text(summary?.let { "累计 ${formatDuration(it.totalSeconds)}，${it.sessionCount} 次会话" } ?: "正在加载使用统计") }
            when { loading && usage == null -> item { Text("正在加载账户信息…") }; message != null -> item { Text(message, color = MaterialTheme.colorScheme.error) }; usage?.items.isNullOrEmpty() -> item { Text("暂无使用记录") }; else -> items(usage?.items?.size ?: 0) { index -> val item = usage!!.items[index]; Text("${formatDuration(item.durationSeconds)} · ${item.startedAt}"); item.sourceLanguage?.let { Text("$it → ${item.targetLanguage ?: "未知"}", color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
            if (usage != null && usage.items.size < usage.total) item { Button(onClick = onLoadMore, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("加载更多") } }
        }
    }
}

/** 产品不支持身份编辑或恢复；仅保留破坏性自助删除。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountIdentitySettingsScreen(
    username: String,
    loading: Boolean,
    message: String?,
    onBack: () -> Unit,
    onDeleteAccount: (String) -> Unit,
    isAdmin: Boolean,
    modifier: Modifier = Modifier,
) {
    val availability = AccountDeletionPolicy.deletionAvailability(username, isAdmin)
    var dialogVisible by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("账户管理") }, navigationIcon = { BackButton(onBack) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Text("当前账户", style = MaterialTheme.typography.titleMedium); Text(username) }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            when (availability) {
                AccountDeletionPolicy.Availability.AVAILABLE -> item {
                    Text("删除账户", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Text("删除后将撤销登录状态和账户数据，且无法恢复。", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    OutlinedButton(onClick = { dialogVisible = true }, enabled = !loading, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp).semantics { testTag = "delete-account" }) { Text("删除账户") }
                }
                AccountDeletionPolicy.Availability.DISABLED -> item { Text(AccountDeletionPolicy.LegacyUnavailableMessage, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                AccountDeletionPolicy.Availability.HIDDEN -> Unit
            }
        }
    }
    if (dialogVisible) DeleteAccountDialog(username, loading, onDismiss = { dialogVisible = false }, onConfirm = onDeleteAccount)
}

@Composable
private fun DeleteAccountDialog(username: String, loading: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var confirmation by remember { mutableStateOf("") }
    val matches = AccountDeletionPolicy.confirmationMatches(username, confirmation)
    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text("确认删除账户") },
        text = { Column { Text("此操作不可恢复。请输入用户名“$username”以确认。") ; OutlinedTextField(confirmation, { confirmation = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).semantics { testTag = "delete-account-confirmation" }) } },
        confirmButton = { Button(onClick = { onConfirm(confirmation); onDismiss() }, enabled = !loading && matches) { Text(if (loading) "正在删除…" else "永久删除") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !loading) { Text("取消") },
    )
}

@Composable
private fun BackButton(onBack: () -> Unit) = IconButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "返回" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
