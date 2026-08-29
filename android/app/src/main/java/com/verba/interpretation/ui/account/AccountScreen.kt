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
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
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
import com.verba.interpretation.cloud.CloudRole
import com.verba.interpretation.ui.AccountUiState

data class AccountSummary(
    val title: String,
    val role: String,
    val detail: String,
    val message: String,
) {
    val renderedText: List<String> get() = listOf(title, role, detail, message)
}

object AccountSummaryMapper {
    fun map(state: AccountUiState): AccountSummary = when {
        !state.signedIn -> AccountSummary("未登录", "访客", "登录后可使用云端翻译服务。", "")
        state.entitlement?.kind == "trial" -> AccountSummary("已登录", roleLabel(state), "试用权益已启用。", safeMessage(state.message))
        state.entitlement != null -> AccountSummary("已登录", roleLabel(state), "权益已启用。", safeMessage(state.message))
        else -> AccountSummary("已登录", roleLabel(state), "暂未获得可用权益。", safeMessage(state.message))
    }

    private fun roleLabel(state: AccountUiState): String = when (state.user?.role) {
        CloudRole.ADMIN -> "管理员"
        CloudRole.USER -> "正式用户"
        null -> "访客"
    }

    private fun safeMessage(message: String?): String = if (message.isNullOrBlank()) "" else "账户状态暂时无法更新，请稍后重试。"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    state: AccountUiState,
    onBack: () -> Unit,
    onHistory: () -> Unit,
    onServiceSettings: () -> Unit,
    onHelp: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = AccountSummaryMapper.map(state)
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("账户", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回" }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(summary.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(summary.role, modifier = Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(summary.detail, modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                        if (summary.message.isNotBlank()) {
                            Text(summary.message, modifier = Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                }
            }
            item { AccountRow("历史记录", "查看本机保存的翻译记录", Icons.Outlined.History, onHistory) }
            item { AccountRow("服务设置", "管理语言与播放偏好", Icons.Outlined.Settings, onServiceSettings) }
            item { AccountRow("帮助与反馈", "查看使用说明", Icons.AutoMirrored.Outlined.HelpOutline, onHelp) }
            if (state.signedIn) {
                item {
                    OutlinedButton(
                        onClick = onLogout,
                        enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "退出登录" },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                        Text("退出登录", modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    title: String,
    detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(detail) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).semantics { contentDescription = "$title。$detail" },
    )
}
