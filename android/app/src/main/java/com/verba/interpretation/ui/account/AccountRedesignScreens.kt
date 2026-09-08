package com.verba.interpretation.ui.account

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
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.verba.interpretation.cloud.CloudUsage
import com.verba.interpretation.cloud.UsageSummary
import com.verba.interpretation.ui.AccountUiState
import com.verba.interpretation.ui.RedeemUiState

/**
 * 「我的」改版新增二级页：权益详情与兑换。
 *
 * 两页均通过 MaterialTheme 语义色适配深浅主题，共用首页的权益大卡组件与工具函数；
 * 页面内容使用 LazyColumn，支持大字体缩放滚动；可点区域最小 48dp。
 */

/**
 * 权益详情页：完整权益状态、到期时间、累计用量、最近使用记录与兑换入口。
 * [onRetry] 用于权益数据加载失败后的卡内重试。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountEntitlementScreen(
    state: AccountUiState,
    onBack: () -> Unit,
    onRedeemNavigate: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val overview = state.overview
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("权益详情", fontWeight = FontWeight.SemiBold) },
            navigationIcon = { AccountRedesignBackButton(onBack) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            state.message?.takeIf { it.isNotBlank() }?.let { message ->
                item { AccountNoticeLine(message) }
            }
            item {
                AccountEntitlementCard(
                    entitlement = overview?.entitlement ?: state.entitlement,
                    loading = state.loading,
                    failed = !state.message.isNullOrBlank(),
                    onOpenDetails = null,
                    onRedeemNavigate = onRedeemNavigate,
                    onRetry = onRetry,
                    // 详情页每页仅一个兑换主 CTA：卡内不放兑换按钮，由底部按钮承担。
                    showRedeemAction = false,
                )
            }
            item {
                AccountUsageSummaryCard(overview?.usage, state.loading)
            }
            state.usage?.takeIf { it.items.isNotEmpty() }?.let { usage ->
                item {
                    AccountSectionLabel("最近使用")
                    AccountRecentUsageList(usage.items, Modifier.padding(top = 8.dp))
                }
            }
            item {
                // 本页唯一兑换主 CTA：无论权益为空、过期还是正常，都只在这里提供一个兑换入口。
                Button(
                    onClick = onRedeemNavigate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = "兑换权益码" },
                    colors = AccountPrimaryCtaColors(),
                ) { Text("兑换权益码") }
            }
        }
    }
}

/** 累计用量摘要：权益详情展示用量，首页不展示。 */
@Composable
private fun AccountUsageSummaryCard(usage: UsageSummary?, loading: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text("使用情况", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val detail = when {
                usage != null -> usageSummary(usage)
                loading -> "正在同步使用情况…"
                else -> "使用情况暂不可确认"
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun AccountRecentUsageList(items: List<CloudUsage>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            items.forEachIndexed { index, usage ->
                AccountRecentUsageRow(usage)
                if (index < items.lastIndex) Divider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun AccountRecentUsageRow(usage: CloudUsage) {
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
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * 兑换页：独立的权益码输入页面。
 *
 * 输入内容仅保存在 ViewModel 内存态（[state] 的 redeem 字段），不持久化到磁盘；
 * 兑换成功后由状态层清空输入，失败时保留以便修正。反馈优先级与旧版一致：
 * 账户级错误（如会话失效）优先于兑换结果提示，避免旧成功提示遮挡登录失效。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountRedemptionScreen(
    state: AccountUiState,
    onBack: () -> Unit,
    onCodeChange: (String) -> Unit,
    onRedeem: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val redeem = state.redeem
    val submitting = redeem is RedeemUiState.Submitting
    val enabled = !state.loading && !submitting
    val error = (redeem as? RedeemUiState.Error)?.message
    val accountMessage = state.message?.takeIf { it.isNotBlank() }
    val feedback = accountMessage ?: when (redeem) {
        is RedeemUiState.Error -> redeem.message
        is RedeemUiState.Success -> redeem.message
        else -> null
    }
    val success = accountMessage == null && redeem is RedeemUiState.Success

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("兑换权益码", fontWeight = FontWeight.SemiBold) },
            navigationIcon = { AccountRedesignBackButton(onBack) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            feedback?.let { message ->
                item { AccountRedeemFeedback(message, success) }
            }
            item {
                Text(
                    "输入四段六码兑换码以更新账户权益。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(
                    value = redeem.code,
                    onValueChange = onCodeChange,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "兑换码输入框" },
                    label = { Text("兑换码") },
                    placeholder = { Text("AAAAAA-BBBBBB-CCCCCC-DDDDDD") },
                    supportingText = error?.let { { Text(it) } },
                    isError = error != null,
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (enabled) onRedeem() }),
                )
            }
            item {
                Button(
                    onClick = onRedeem,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "兑换权益码" },
                    colors = AccountPrimaryCtaColors(),
                ) { Text(if (submitting) "正在兑换…" else "兑换") }
            }
        }
    }
}

@Composable
private fun AccountRedeemFeedback(message: String, success: Boolean) {
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

@Composable
private fun AccountRedesignBackButton(onBack: () -> Unit) = IconButton(
    onClick = onBack,
    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "返回" },
) {
    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
}
