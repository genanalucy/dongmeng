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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verba.interpretation.cloud.AccountOverview
import com.verba.interpretation.cloud.CloudEntitlement
import com.verba.interpretation.cloud.UsageSummary
import com.verba.interpretation.ui.AccountUiState
import com.verba.interpretation.ui.RedeemUiState

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
    onRedeemCodeChange: (String) -> Unit = {},
    onRedeem: () -> Unit = {},
) {
    val overview = state.overview ?: AccountOverview(
        username = state.user?.username ?: "未登录",
        entitlement = state.entitlement,
        usage = UsageSummary(0, 0, null),
    )
    val displayName = overview.username.ifBlank { state.user?.username ?: "未登录" }
    val callbacks = AccountCallbacks(
        onBack = onBack,
        onUsage = onUsage,
        onHistory = onHistory,
        onSettings = onSettings,
        onServiceSettings = onServiceSettings,
        onLogout = onLogout,
    )

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("我的", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(
                    onClick = { AccountActionDispatcher.back(callbacks) },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "返回" },
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                        Text(
                            displayName,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            accountRoleLabel(state),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (state.loading) {
                            Text(
                                "正在同步账户信息…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
            }
            item {
                AccountSectionLabel("权益与用量")
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column {
                        AccountRow(
                            title = "使用与权益",
                            detail = "${entitlementSummary(overview.entitlement)} · ${usageSummary(overview.usage)}",
                            icon = Icons.Outlined.WorkspacePremium,
                            onClick = { AccountActionDispatcher.dispatch(AccountAction.USAGE, callbacks) },
                        )
                    }
                }
            }
            item {
                RedeemCard(
                    redeem = state.redeem,
                    enabled = !state.loading,
                    onCodeChange = onRedeemCodeChange,
                    onRedeem = onRedeem,
                )
            }
            item {
                AccountSectionLabel("账户管理")
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column {
                        AccountRow("历史记录", "查看本机保存的翻译记录", Icons.Outlined.History) {
                            AccountActionDispatcher.dispatch(AccountAction.HISTORY, callbacks)
                        }
                        AccountRow(
                            title = "账户设置",
                            detail = "查看账户状态或删除账户",
                            icon = Icons.Outlined.ManageAccounts,
                            accessibilityTitle = "账户管理",
                        ) {
                            AccountActionDispatcher.dispatch(AccountAction.SETTINGS, callbacks)
                        }
                        if (showServiceSettings) {
                            AccountRow("服务设置", "管理语言与播放偏好", Icons.Outlined.Settings) {
                                AccountActionDispatcher.dispatch(AccountAction.SERVICE_SETTINGS, callbacks)
                            }
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = { AccountActionDispatcher.dispatch(AccountAction.LOGOUT, callbacks) },
                    enabled = !state.loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                    Text("退出登录", modifier = Modifier.padding(start = 8.dp))
                }
            }
            val accountMessage = state.message?.takeIf { it.isNotBlank() }
            val feedback = accountMessage ?: when (val redeem = state.redeem) {
                is RedeemUiState.Error -> redeem.message
                is RedeemUiState.Success -> redeem.message
                else -> null
            }
            feedback?.let { message ->
                val success = accountMessage == null && state.redeem is RedeemUiState.Success
                item {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (success) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = if (success) "兑换结果：$message" else "账户错误：$message"
                            liveRegion = LiveRegionMode.Polite
                        },
                    ) {
                        Text(
                            message,
                            color = if (success) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RedeemCard(
    redeem: RedeemUiState,
    enabled: Boolean,
    onCodeChange: (String) -> Unit,
    onRedeem: () -> Unit,
) {
    val submitting = redeem is RedeemUiState.Submitting
    val code = redeem.code
    val error = (redeem as? RedeemUiState.Error)?.message
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("兑换权益码", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("输入四段六码兑换码以更新账户权益。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = code,
                onValueChange = onCodeChange,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "兑换码输入框" },
                label = { Text("兑换码") },
                placeholder = { Text("AAAAAA-BBBBBB-CCCCCC-DDDDDD") },
                supportingText = error?.let { { Text(it) } },
                isError = error != null,
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (enabled && !submitting) onRedeem() }),
            )
            Button(
                onClick = onRedeem,
                enabled = enabled && !submitting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "兑换权益码" },
            ) {
                Text(if (submitting) "正在兑换…" else "兑换")
            }
        }
    }
}

@Composable
internal fun AccountSectionLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

private fun accountRoleLabel(state: AccountUiState): String = when {
    !state.signedIn && state.loading -> "正在加载账户信息"
    !state.signedIn && !state.message.isNullOrBlank() -> "账户信息加载失败"
    !state.signedIn -> "未登录"
    state.isAdmin -> "管理员账户"
    else -> "正式用户"
}

internal fun entitlementSummary(entitlement: CloudEntitlement?): String = entitlement?.let {
    val kind = entitlementKindLabel(it.kind)
    val state = entitlementStateLabel(it)
    val expiry = formatAccountTime(it.expiresAt)
    val remaining = "，剩余 ${formatDuration(it.remainingSeconds)}"
    "$kind · $state · 到期 $expiry$remaining"
} ?: "暂无可用权益"

internal fun usageSummary(usage: UsageSummary): String =
    "累计 ${formatDuration(usage.totalSeconds)} · ${usage.sessionCount.coerceAtLeast(0)} 次会话 · 最近 ${formatAccountTime(usage.lastUsedAt)}"

private fun entitlementKindLabel(kind: String): String = when (kind.trim().lowercase()) {
    "trial" -> "试用"
    "subscription", "subscribed", "paid" -> "订阅"
    else -> "权益"
}

private fun entitlementStateLabel(entitlement: CloudEntitlement): String = when {
    !entitlement.active -> "已过期"
    isExpired(entitlement.expiresAt) -> "已过期"
    entitlement.expiresAt.isBlank() || parseAccountInstant(entitlement.expiresAt) == null -> "状态未知"
    else -> "有效"
}

internal fun isExpired(value: String): Boolean = parseAccountInstant(value)?.let { it.isBefore(java.time.Instant.now()) } ?: false

@Composable
private fun AccountRow(
    title: String,
    detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accessibilityTitle: String = title,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(detail) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = accessibilityTitle },
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
        return AccountSummary(
            if (state.signedIn) "已登录" else "未登录",
            role,
            detail,
            if (state.message.isNullOrBlank()) "" else "账户状态暂时无法更新，请稍后重试。",
        )
    }
}

internal fun formatDuration(seconds: Long): String {
    val minutes = seconds.coerceAtLeast(0) / 60
    return if (minutes >= 60) "${minutes / 60} 小时 ${minutes % 60} 分" else "$minutes 分钟"
}

internal fun formatAccountTime(value: String?): String {
    if (value.isNullOrBlank()) return "暂无记录"
    val instant = parseAccountInstant(value) ?: return "时间不可用"
    return java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
        .format(instant)
}

internal fun parseAccountInstant(value: String): java.time.Instant? = runCatching {
    java.time.Instant.parse(value)
}.getOrElse {
    runCatching { java.time.OffsetDateTime.parse(value).toInstant() }.getOrNull()
}
