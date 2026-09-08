package com.verba.interpretation.ui.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.verba.interpretation.brand.BrandConfig
import com.verba.interpretation.cloud.AccountOverview
import com.verba.interpretation.cloud.CloudEntitlement
import com.verba.interpretation.cloud.UsageSummary
import com.verba.interpretation.ui.AccountUiState

enum class AccountAction { USAGE, HISTORY, SETTINGS, SECURITY, SERVICE_SETTINGS, HELP, LOGOUT }

data class AccountCallbacks(
    val onBack: () -> Unit,
    val onUsage: () -> Unit = {},
    val onHistory: () -> Unit,
    val onSettings: () -> Unit = {},
    val onSecurity: () -> Unit = {},
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
        AccountAction.SECURITY -> callbacks.onSecurity()
        AccountAction.SERVICE_SETTINGS -> callbacks.onServiceSettings()
        AccountAction.HELP -> callbacks.onHelp()
        AccountAction.LOGOUT -> callbacks.onLogout()
    }
}

/**
 * 「我的」首页。
 *
 * 结构：品牌符号+用户名+账户类型 → 权益大卡（整卡进入权益详情）→ 历史/权益/账户/安全四个纵向入口。
 * 不显示邮箱、不伪造头像；不展示累计用量、不常驻兑换输入、不提供退出登录（退出登录位于安全页）。
 * 服务设置入口不在此页展示，由账户二级页在 debug 构建下提供。
 *
 * [onRedeemCodeChange] 与 [onRedeem] 仅为旧调用兼容保留：兑换输入已移至独立兑换页
 * （见 [AccountRedemptionScreen]），首页通过 [onRedeemNavigate] 导航过去。
 * [showServiceSettings] 同为旧调用兼容保留，本页已不再渲染服务设置入口，取值被忽略。
 * [showBack] 仅在二级账户页显示返回箭头；底部“我的”根页不显示无效返回操作。
 */
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
    @Suppress("UNUSED_PARAMETER") showServiceSettings: Boolean = true,
    onRedeemCodeChange: (String) -> Unit = {},
    onRedeem: () -> Unit = {},
    onSecurity: () -> Unit = {},
    onRetry: () -> Unit = {},
    onRedeemNavigate: () -> Unit = {},
    showBack: Boolean = true,
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
        onSecurity = onSecurity,
        onServiceSettings = onServiceSettings,
        onLogout = onLogout,
    )

    Column(modifier.fillMaxSize()) {
        if (showBack) {
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
        } else {
            TopAppBar(
                title = { Text("我的", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            state.message?.takeIf { it.isNotBlank() }?.let { message ->
                item { AccountNoticeLine(message) }
            }
            item {
                AccountIdentityHeader(displayName, accountRoleLabel(state))
            }
            item {
                AccountEntitlementCard(
                    entitlement = overview.entitlement ?: state.entitlement,
                    loading = state.loading,
                    failed = !state.message.isNullOrBlank(),
                    onOpenDetails = { AccountActionDispatcher.dispatch(AccountAction.USAGE, callbacks) },
                    onRedeemNavigate = onRedeemNavigate,
                    onRetry = onRetry,
                )
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Column {
                        AccountEntryRow("历史记录", "查看本机保存的翻译记录", Icons.Outlined.History) {
                            AccountActionDispatcher.dispatch(AccountAction.HISTORY, callbacks)
                        }
                        Divider(color = MaterialTheme.colorScheme.outlineVariant)
                        AccountEntryRow("权益详情", "权益状态、使用与兑换", Icons.Outlined.WorkspacePremium) {
                            AccountActionDispatcher.dispatch(AccountAction.USAGE, callbacks)
                        }
                        Divider(color = MaterialTheme.colorScheme.outlineVariant)
                        AccountEntryRow(
                            title = "账户设置",
                            detail = "查看账户状态或删除账户",
                            icon = Icons.Outlined.ManageAccounts,
                        ) {
                            AccountActionDispatcher.dispatch(AccountAction.SETTINGS, callbacks)
                        }
                        Divider(color = MaterialTheme.colorScheme.outlineVariant)
                        AccountEntryRow("安全与登录", "管理登录状态与安全选项", Icons.Outlined.Security) {
                            AccountActionDispatcher.dispatch(AccountAction.SECURITY, callbacks)
                        }
                    }
                }
            }
        }
    }
}

/** 品牌符号 + 用户名 + 账户类型。不显示邮箱，不伪造头像。 */
@Composable
private fun AccountIdentityHeader(displayName: String, roleLabel: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
    ) {
        BrandConfig.Logo(Modifier.size(44.dp))
        Column(Modifier.padding(start = 14.dp)) {
            Text(
                displayName,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                roleLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 顶部账户级轻提示；权益卡内另行提供针对性错误与重试。 */
@Composable
internal fun AccountNoticeLine(message: String, modifier: Modifier = Modifier) {
    Text(
        message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "账户提示：$message"
                liveRegion = LiveRegionMode.Polite
            },
    )
}

/**
 * 权益大卡：首页整卡可点进入详情，详情页传 [onOpenDetails] = null 仅展示。
 * 状态优先级：加载失败（不冒充无权益，也不用陈旧权益冒充最新）> 加载骨架 > 已知权益 > 无权益灰卡。
 * 仅在展示有效（含状态未知）权益数据时启用整卡点击；首页在已知过期与无权益状态改为卡内
 * 兑换导航按钮，避免与整卡点击形成嵌套手势；详情页传 [showRedeemAction] = false
 * 关闭卡内按钮，由页面底部按钮承担唯一兑换主 CTA。
 */
@Composable
internal fun AccountEntitlementCard(
    entitlement: CloudEntitlement?,
    loading: Boolean,
    failed: Boolean,
    onOpenDetails: (() -> Unit)?,
    onRedeemNavigate: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    showRedeemAction: Boolean = true,
) {
    val baseModifier = modifier.fillMaxWidth().heightIn(min = 88.dp)
    val cardColor = if (entitlement == null && !loading && !failed) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val cardClickable = onOpenDetails != null && !failed && !loading && entitlement != null &&
        entitlementStateLabel(entitlement) != "已过期"
    val cardModifier = if (cardClickable) {
        baseModifier.clickable(
            onClickLabel = "查看权益详情",
            role = Role.Button,
        ) { onOpenDetails?.invoke() }
    } else {
        baseModifier
    }
    Surface(
        modifier = cardModifier,
        shape = MaterialTheme.shapes.large,
        color = cardColor,
    ) {
        AccountEntitlementCardContent(entitlement, loading, failed, onRedeemNavigate, onRetry, showRedeemAction)
    }
}

@Composable
private fun AccountEntitlementCardContent(
    entitlement: CloudEntitlement?,
    loading: Boolean,
    failed: Boolean,
    onRedeemNavigate: () -> Unit,
    onRetry: () -> Unit,
    showRedeemAction: Boolean,
) {
    when {
        // 失败最优先：权益状态未知时不得用陈旧 entitlement 冒充已知，也不得显示为无权益。
        failed -> AccountEntitlementError(onRetry)
        loading -> AccountEntitlementSkeleton()
        entitlement != null -> AccountEntitlementSummary(entitlement, onRedeemNavigate, showRedeemAction)
        else -> AccountEntitlementEmpty(onRedeemNavigate, showRedeemAction)
    }
}

/**
 * 已知权益：类型、有效状态、大号剩余天数、精确到期日。
 * 已过期时首页在卡内提供兑换导航按钮（与无权益灰卡一致）；详情页 [showRedeemAction] = false
 * 时不在卡内放按钮，由页面底部唯一按钮承担兑换入口。
 */
@Composable
private fun AccountEntitlementSummary(
    entitlement: CloudEntitlement,
    onRedeemNavigate: () -> Unit,
    showRedeemAction: Boolean,
) {
    val stateLabel = entitlementStateLabel(entitlement)
    val active = stateLabel == "有效"
    val expired = stateLabel == "已过期"
    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                entitlementKindLabel(entitlement.kind),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stateLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val remainingDays = if (active) entitlementRemainingDays(entitlement) else null
        if (remainingDays != null) {
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Text(
                    remainingDays.toString(),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    " 天剩余",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp, bottom = 5.dp),
                )
            }
        } else if (active) {
            Text(
                "不足 1 天",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        val expiry = parseAccountInstant(entitlement.expiresAt)
        val expiryLine = if (expiry != null) "到期 ${formatAccountTime(entitlement.expiresAt)}" else "到期时间未知"
        Text(
            expiryLine,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (expired && showRedeemAction) {
            Button(
                onClick = onRedeemNavigate,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "兑换权益码" },
                colors = AccountPrimaryCtaColors(),
            ) { Text("兑换权益码") }
        }
    }
}

/**
 * 无有效权益：中性灰卡。首页在卡内放主兑换按钮（导航至独立兑换页）；
 * 详情页 [showRedeemAction] = false 时不放，避免与页面底部按钮形成重复主 CTA。
 */
@Composable
private fun AccountEntitlementEmpty(onRedeemNavigate: () -> Unit, showRedeemAction: Boolean) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text(
            "暂无有效权益",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "兑换权益码后即可使用云端翻译。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (showRedeemAction) {
            Button(
                onClick = onRedeemNavigate,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "兑换权益码" },
                colors = AccountPrimaryCtaColors(),
            ) { Text("兑换权益码") }
        }
    }
}

/** 加载失败：明确「暂不可确认」，不冒充无权益。 */
@Composable
private fun AccountEntitlementError(onRetry: () -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
        Text(
            "权益状态暂不可确认",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "网络或服务暂时不可用，请稍后重试。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "重试加载权益" },
        ) { Text("重试") }
    }
}

/** 局部加载骨架：不阻断页面其余内容。 */
@Composable
private fun AccountEntitlementSkeleton() {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 18.dp)
            .semantics { contentDescription = "正在加载权益信息" },
    ) {
        AccountSkeletonLine(Modifier.width(64.dp), MaterialTheme.typography.labelLarge.lineHeight.value.dp)
        AccountSkeletonLine(Modifier.width(168.dp), MaterialTheme.typography.headlineMedium.lineHeight.value.dp)
        AccountSkeletonLine(Modifier.width(196.dp), MaterialTheme.typography.bodyMedium.lineHeight.value.dp)
    }
}

@Composable
private fun AccountSkeletonLine(modifier: Modifier, height: Dp) {
    Surface(
        modifier = modifier.height(height),
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {}
}

/**
 * 主兑换按钮配色：浅色主题为黑色按钮（onSurface 容器 / surface 文字），
 * 深色主题为高对比反色（primary / onPrimary），禁止黑底黑字。
 */
@Composable
internal fun AccountPrimaryCtaColors() = ButtonDefaults.buttonColors(
    containerColor = if (MaterialTheme.colorScheme.background.luminance() >= 0.5f) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.primary
    },
    contentColor = if (MaterialTheme.colorScheme.background.luminance() >= 0.5f) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.onPrimary
    },
)

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

internal fun entitlementKindLabel(kind: String): String = when (kind.trim().lowercase()) {
    "trial" -> "试用"
    "subscription", "subscribed", "paid" -> "订阅"
    else -> "权益"
}

internal fun entitlementStateLabel(entitlement: CloudEntitlement): String = when {
    !entitlement.active -> "已过期"
    isExpired(entitlement.expiresAt) -> "已过期"
    entitlement.expiresAt.isBlank() || parseAccountInstant(entitlement.expiresAt) == null -> "状态未知"
    else -> "有效"
}

/**
 * 有效权益的剩余整天数；不足一天返回 null（由界面显示「不足 1 天」，不得显示为过期）。
 * 服务器秒数缺失时按到期时刻推算。
 */
internal fun entitlementRemainingDays(entitlement: CloudEntitlement): Long? {
    val seconds = if (entitlement.remainingSeconds >= 0) {
        entitlement.remainingSeconds
    } else {
        val instant = parseAccountInstant(entitlement.expiresAt) ?: return null
        java.time.Duration.between(java.time.Instant.now(), instant).seconds
    }
    return if (seconds >= 86_400) seconds / 86_400 else null
}

internal fun isExpired(value: String): Boolean = parseAccountInstant(value)?.let { it.isBefore(java.time.Instant.now()) } ?: false

@Composable
private fun AccountEntryRow(
    title: String,
    detail: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(detail) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "$title。$detail" },
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
