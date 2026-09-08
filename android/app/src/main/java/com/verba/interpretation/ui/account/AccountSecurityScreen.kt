package com.verba.interpretation.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verba.interpretation.ui.AccountSecurityUiState
import com.verba.interpretation.ui.SecurityDeviceSummary

/**
 * 账户与安全二级页：关联设备 + 退出登录。
 *
 * 真实能力边界（与当前 Cloud API 一一对应，不做任何夸大）：
 * - 关联设备：GET /users/me/devices 返回本账户名下的设备记录；后端在签发翻译会话时
 *   按 install_id 登记或刷新记录，因此这是「使用过云翻译的设备」清单，
 *   不是完整登录会话列表，last_seen_at 即最近一次签发翻译会话的时间；
 * - 退出登录：撤销 refresh token 并清除本机令牌（真实存在），经确认弹窗二次确认；
 * - 不存在单设备远程下线 API，也不存在设备位置/型号数据：本页不提供任何伪装操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSecurityScreen(
    state: AccountSecurityUiState,
    username: String?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var logoutConfirmationPending by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("账户与安全") },
            navigationIcon = {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "返回" },
                ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item { LoginStatusCard(username, onRequestLogout = { logoutConfirmationPending = true }) }
            item { AssociatedDevicesSection(state, onRefresh) }
            item {
                Text(
                    "当前版本不支持远程移除其他设备的关联记录。如需在其他设备退出登录，请直接在该设备上操作。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        }
    }
    if (logoutConfirmationPending) {
        LogoutConfirmationDialog(
            onDismiss = { logoutConfirmationPending = false },
            onConfirm = {
                logoutConfirmationPending = false
                onLogout()
            },
        )
    }
}

@Composable
private fun LoginStatusCard(username: String?, onRequestLogout: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("登录状态", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                username?.takeIf(String::isNotBlank)?.let { "已登录：$it" } ?: "账户信息不可用",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "登录令牌仅以 Android Keystore 加密后保存在本机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = onRequestLogout,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { testTag = "security-logout" },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Text("退出登录", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun LogoutConfirmationDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认退出登录") },
        text = { Text("退出后将撤销本机登录令牌并清除本地数据，需要重新登录才能继续使用云翻译。") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("退出登录", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AssociatedDevicesSection(state: AccountSecurityUiState, onRefresh: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "关联设备",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            TextButton(onClick = onRefresh, enabled = !state.loading) {
                Text(if (state.loading) "刷新中…" else "刷新")
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "以下设备曾在本账户下使用云翻译，按最近活跃排序；设备在开始翻译时登记或更新，" +
                        "因此列表不代表完整的登录会话记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    state.loading ->
                        Text("正在加载关联设备…", style = MaterialTheme.typography.bodyLarge)
                    state.message != null -> {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                        )
                        Button(
                            onClick = onRefresh,
                            enabled = !state.loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text("重试") }
                    }
                    state.devices != null && state.devices.isEmpty() ->
                        Text("当前没有关联设备记录。登录并在设备上开始翻译后，设备会出现在这里。", style = MaterialTheme.typography.bodyLarge)
                    else -> state.devices.orEmpty().forEachIndexed { index, summary ->
                        AssociatedDeviceRow(summary)
                        if (index < state.devices.orEmpty().lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AssociatedDeviceRow(summary: SecurityDeviceSummary) {
    val title = if (summary.isCurrentDevice) "本机" else "其他设备"
    val lastSeen = formatSecurityTime(summary.lastSeenAt)
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text("设备标识 ${summary.installIdMasked} · 最近活跃 $lastSeen") },
        leadingContent = {
            Icon(Icons.Outlined.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = "$title。设备标识已缩略，最近活跃 $lastSeen。"
        },
    )
}

private fun formatSecurityTime(value: String): String {
    val instant = runCatching { java.time.Instant.parse(value) }.getOrElse {
        runCatching { java.time.OffsetDateTime.parse(value).toInstant() }.getOrNull()
    } ?: return "时间不可用"
    return java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
        .format(instant)
}
