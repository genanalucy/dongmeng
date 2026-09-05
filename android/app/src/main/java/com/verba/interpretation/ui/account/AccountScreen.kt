package com.verba.interpretation.ui.account

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verba.interpretation.cloud.AccountOverview
import com.verba.interpretation.cloud.CloudEntitlement
import com.verba.interpretation.cloud.UsageSummary
import com.verba.interpretation.ui.AccountUiState

enum class AccountAction { USAGE, HISTORY, SETTINGS, SERVICE_SETTINGS, HELP, LOGOUT }

data class AccountCallbacks(
    val onBack: () -> Unit,
    val onUsage: () -> Unit = {},
    val onHistory: () -> Unit,
    val onSettings: () -> Unit = {},
    val onServiceSettings: () -> Unit,
    val onHelp: () -> Unit = {},
    val onLogout: () -> Unit,
)

object AccountActionDispatcher {
    fun back(callbacks: AccountCallbacks) = callbacks.onBack()
    fun dispatch(action: AccountAction, callbacks: AccountCallbacks) = when (action) {
        AccountAction.USAGE -> callbacks.onUsage()
        AccountAction.HISTORY -> callbacks.onHistory()
        AccountAction.SETTINGS -> callbacks.onSettings()
        AccountAction.SERVICE_SETTINGS -> callbacks.onServiceSettings()
        AccountAction.HELP -> callbacks.onHelp()
        AccountAction.LOGOUT -> callbacks.onLogout()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    state: AccountUiState,
    onBack: () -> Unit,
    onUsage: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    onServiceSettings: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    showServiceSettings: Boolean = true,
) {
    val overview = state.overview ?: AccountOverview(
        username = state.user?.username ?: "未登录",
        entitlement = state.entitlement,
        usage = UsageSummary(0, 0, null),
    )
    val callbacks = AccountCallbacks(onBack, onUsage, onHistory, onSettings, onServiceSettings, onLogout = onLogout)
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("账户与权益", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = { AccountActionDispatcher.back(callbacks) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "返回" }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(20.dp)) {
                        Text(overview.username, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("账户与权益", modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
            item { AccountStatusCard(overview.entitlement, overview.usage) }
            item { AccountRow("使用与权益", "查看权益详情与使用记录", Icons.Outlined.WorkspacePremium) { AccountActionDispatcher.dispatch(AccountAction.USAGE, callbacks) } }
            item { AccountRow("历史记录", "查看本机保存的翻译记录", Icons.Outlined.History) { AccountActionDispatcher.dispatch(AccountAction.HISTORY, callbacks) } }
            item { AccountRow("账户管理", "查看账户状态或删除账户", Icons.Outlined.ManageAccounts) { AccountActionDispatcher.dispatch(AccountAction.SETTINGS, callbacks) } }
            if (showServiceSettings) {
                item { AccountRow("服务设置", "管理语言与播放偏好", Icons.Outlined.Settings) { AccountActionDispatcher.dispatch(AccountAction.SERVICE_SETTINGS, callbacks) } }
            }
            item {
                OutlinedButton(
                    onClick = { AccountActionDispatcher.dispatch(AccountAction.LOGOUT, callbacks) }, enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "退出登录" },
                ) { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null); Text("退出登录", modifier = Modifier.padding(start = 8.dp)) }
            }
            state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        }
    }
}

@Composable
private fun AccountStatusCard(entitlement: CloudEntitlement?, usage: UsageSummary) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("权益", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(entitlement?.let { "${if (it.kind == "trial") "试用" else "订阅"}${if (it.active) "有效" else "未生效"}，至 ${it.expiresAt}" } ?: "暂无可用权益", modifier = Modifier.padding(top = 4.dp))
            Text("使用情况", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
            Text("累计 ${formatDuration(usage.totalSeconds)} · ${usage.sessionCount} 次会话", modifier = Modifier.padding(top = 4.dp))
            Text("最近使用：${usage.lastUsedAt ?: "暂无记录"}", modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AccountRow(title: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) }, supportingContent = { Text(detail) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }, trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).semantics { contentDescription = title },
    )
}

data class AccountSummary(val title: String, val role: String, val detail: String, val message: String) {
    val renderedText: List<String> get() = listOf(title, role, detail, message)
}

object AccountSummaryMapper {
    fun map(state: AccountUiState): AccountSummary {
        val role = when (state.user?.role) {
            com.verba.interpretation.cloud.CloudRole.ADMIN -> "管理员"
            com.verba.interpretation.cloud.CloudRole.USER -> "正式用户"
            null -> "访客"
        }
        val detail = when (state.entitlement?.kind) {
            "trial" -> "试用权益已启用。"
            null -> if (state.signedIn) "暂未获得可用权益。" else "登录后可使用云端翻译服务。"
            else -> "权益已启用。"
        }
        return AccountSummary(if (state.signedIn) "已登录" else "未登录", role, detail, if (state.message.isNullOrBlank()) "" else "账户状态暂时无法更新，请稍后重试。")
    }
}

internal fun formatDuration(seconds: Long): String {
    val minutes = seconds.coerceAtLeast(0) / 60
    return if (minutes >= 60) "${minutes / 60} 小时 ${minutes % 60} 分" else "$minutes 分钟"
}
