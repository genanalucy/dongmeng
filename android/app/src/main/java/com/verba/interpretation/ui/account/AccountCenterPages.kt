package com.verba.interpretation.ui.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verba.interpretation.brand.ThemeMode
import com.verba.interpretation.brand.ThemeModePresentation
import com.verba.interpretation.cloud.AccountIdentityProfile
import com.verba.interpretation.cloud.AccountOverview
import com.verba.interpretation.cloud.CloudUsage
import com.verba.interpretation.cloud.UsagePage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountUsageScreen(
    overview: AccountOverview?,
    usage: UsagePage?,
    loading: Boolean,
    message: String?,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("使用与权益") },
            navigationIcon = { BackButton(onBack) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                AccountSectionLabel("当前权益")
                AccountUsageSummary(overview, Modifier.padding(top = 8.dp))
            }
            item {
                AccountSectionLabel("使用记录")
                when {
                    loading && usage == null -> AccountFeedback("正在加载使用记录…", Modifier.padding(top = 8.dp))
                    message != null -> AccountFeedback(message, Modifier.padding(top = 8.dp), isError = true)
                    usage?.items.isNullOrEmpty() -> AccountFeedback("暂无使用记录", Modifier.padding(top = 8.dp))
                    else -> UsageList(usage!!.items, Modifier.padding(top = 8.dp))
                }
            }
            if (usage != null && usage.items.size < usage.total) {
                item {
                    Button(
                        onClick = onLoadMore,
                        enabled = !loading,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(if (loading) "正在加载…" else "加载更多") }
                }
            }
        }
    }
}

@Composable
private fun AccountUsageSummary(overview: AccountOverview?, modifier: Modifier = Modifier) {
    val entitlement = overview?.entitlement
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(entitlementSummary(entitlement), style = MaterialTheme.typography.bodyLarge)
            overview?.usage?.let {
                Text(
                    usageSummary(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun UsageList(items: List<CloudUsage>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            items.forEachIndexed { index, usage ->
                UsageRow(usage)
                if (index < items.lastIndex) Divider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun UsageRow(usage: CloudUsage) {
    val language = when {
        usage.sourceLanguage.isNullOrBlank() && usage.targetLanguage.isNullOrBlank() -> null
        else -> "${usage.sourceLanguage ?: "未知"} → ${usage.targetLanguage ?: "未知"}"
    }
    ListItem(
        headlineContent = { Text(formatAccountTime(usage.startedAt), style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            Text(
                listOfNotNull(formatDuration(usage.durationSeconds), language).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

@Composable
private fun AccountFeedback(message: String, modifier: Modifier = Modifier, isError: Boolean = false) {
    Text(
        message,
        modifier = modifier,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
    )
}

/** 产品不支持身份编辑或恢复；仅保留破坏性自助删除与真实资料展示。 */
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
    identityProfile: AccountIdentityProfile? = null,
    showServiceSettings: Boolean = false,
    onServiceSettings: () -> Unit = {},
    showTranslationSettings: Boolean = false,
    onTranslationSettings: () -> Unit = {},
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onSelectThemeMode: (ThemeMode) -> Unit = {},
) {
    val availability = AccountDeletionPolicy.deletionAvailability(username, isAdmin)
    var dialogVisible by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("账户") },
            navigationIcon = { BackButton(onBack) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                AccountSectionLabel("账户资料")
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (identityProfile != null) {
                            ProfileField("用户名", identityProfile.username)
                            ProfileField("邮箱", identityProfile.email.ifBlank { "未设置" })
                            ProfileField("手机号", identityProfile.maskedPhone ?: "未绑定")
                        } else {
                            Text(
                                username.ifBlank { "账户信息不可用" },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                "账户资料尚未加载。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                AccountSectionLabel("外观")
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    ThemeModeOptionList(current = themeMode, onSelect = onSelectThemeMode)
                }
            }
            message?.takeIf { it.isNotBlank() }?.let {
                item { AccountFeedback(it, isError = true) }
            }
            if (showServiceSettings) {
                item {
                    AccountSectionLabel("服务偏好")
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        ServiceSettingsRow(onServiceSettings)
                    }
                }
            }
            if (showTranslationSettings) {
                item {
                    AccountSectionLabel("翻译测试")
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        TranslationSettingsRow(onTranslationSettings)
                    }
                }
            }
            when (availability) {
                AccountDeletionPolicy.Availability.AVAILABLE -> item {
                    AccountSectionLabel("危险操作")
                    Column(Modifier.padding(top = 8.dp)) {
                        Text("删除账户", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                        Text(
                            "删除后将撤销登录状态和账户数据，且无法恢复。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        OutlinedButton(
                            onClick = { dialogVisible = true },
                            enabled = !loading,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp).semantics { testTag = "delete-account" },
                        ) { Text("删除账户") }
                    }
                }
                AccountDeletionPolicy.Availability.DISABLED -> item {
                    AccountFeedback(AccountDeletionPolicy.LegacyUnavailableMessage)
                }
                AccountDeletionPolicy.Availability.HIDDEN -> Unit
            }
        }
    }
    if (dialogVisible) DeleteAccountDialog(username, loading, onDismiss = { dialogVisible = false }, onConfirm = onDeleteAccount)
}

@Composable
private fun ProfileField(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * 应用内主题模式三选一（跟随系统/浅色/深色）。每行不低于 64dp（≥48dp 触控目标），
 * 整行可选、带 RadioButton 角色与选中状态语义；文案与语义字符串统一出自
 * [ThemeModePresentation]，便于 JVM 侧直接测试呈现策略。
 */
@Composable
private fun ThemeModeOptionList(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Column {
        ThemeModePresentation.options.forEachIndexed { index, option ->
            if (index > 0) Divider(color = MaterialTheme.colorScheme.outlineVariant)
            ThemeModeOptionRow(option, selected = option == current, onClick = { onSelect(option) })
        }
    }
}

@Composable
private fun ThemeModeOptionRow(option: ThemeMode, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(ThemeModePresentation.label(option)) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics {
                testTag = ThemeModePresentation.optionTestTag(option)
                contentDescription = ThemeModePresentation.optionAnnouncement(option)
                stateDescription = ThemeModePresentation.optionStateDescription(selected)
            },
    )
}

/** 服务偏好入口：仅开发构建可见，指向测试服务地址设置。 */
@Composable
private fun ServiceSettingsRow(onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text("服务偏好设置", fontWeight = FontWeight.Medium) },
        supportingContent = { Text("服务连接与偏好（仅开发构建可用）") },
        leadingContent = { Icon(Icons.Outlined.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "服务偏好设置，仅开发构建可用" },
    )
}

@Composable
private fun TranslationSettingsRow(onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text("翻译引擎与声音", fontWeight = FontWeight.Medium) },
        supportingContent = { Text("Azure 测试与目标语言合成声音") },
        leadingContent = { Icon(Icons.Outlined.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick),
    )
}

@Composable
private fun DeleteAccountDialog(username: String, loading: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var confirmation by remember { mutableStateOf("") }
    val matches = AccountDeletionPolicy.confirmationMatches(username, confirmation)
    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text("确认删除账户") },
        text = {
            Column {
                Text("此操作不可恢复。请输入用户名“$username”以确认。")
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).semantics { testTag = "delete-account-confirmation" },
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(confirmation); onDismiss() }, enabled = !loading && matches) {
                Text(if (loading) "正在删除…" else "永久删除")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !loading) { Text("取消") } },
    )
}

@Composable
private fun BackButton(onBack: () -> Unit) = IconButton(
    onClick = onBack,
    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "返回" },
) {
    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
}
