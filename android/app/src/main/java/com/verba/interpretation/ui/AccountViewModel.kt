package com.verba.interpretation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.verba.interpretation.cloud.AccountApi
import com.verba.interpretation.cloud.AccountIdentityProfile
import com.verba.interpretation.cloud.AccountOverview
import com.verba.interpretation.cloud.CloudApi
import com.verba.interpretation.cloud.CloudEndpointSettings
import com.verba.interpretation.cloud.CloudEntitlement
import com.verba.interpretation.cloud.CloudApiException
import com.verba.interpretation.cloud.CloudRole
import com.verba.interpretation.cloud.CloudUsage
import com.verba.interpretation.cloud.CloudUser
import com.verba.interpretation.cloud.IdentityUpdateRequest
import com.verba.interpretation.cloud.InstallationIdStore
import com.verba.interpretation.cloud.KeystoreTokenStore
import com.verba.interpretation.cloud.LoginIdentifierStore
import com.verba.interpretation.cloud.SharedPreferencesInstallationIdStore
import com.verba.interpretation.cloud.SharedPreferencesLoginIdentifierStore
import com.verba.interpretation.cloud.UsagePage
import com.verba.interpretation.history.LocalHistoryRepository
import com.verba.interpretation.ui.account.AccountDeletionPolicy
import com.verba.interpretation.ui.account.AccountIdentityFormPolicy
import com.verba.interpretation.ui.account.AuthenticationFormPolicy
import com.verba.interpretation.ui.account.LatestLoginIdentifierPolicy
import com.verba.interpretation.cloud.SlideCaptchaChallenge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

sealed interface RegistrationUiState {
    data object Details : RegistrationUiState

    /**
     * 活跃注册流程中的滑块拼图挑战。仅承载验证码素材与几何信息；
     * 用户名/邮箱/密码始终只留在表单与调用参数中，不进入状态或存储。
     */
    data class SlideCaptcha(
        val captchaId: String,
        val tolerancePx: Int,
        val challengeWidth: Int,
        val challengeHeight: Int,
        val challengeImageBase64: String,
        val tileImageBase64: String,
        val tileWidth: Int,
        val tileHeight: Int,
        val tileStartX: Int,
        val tileStartY: Int,
        val expiresAtMillis: Long,
    ) : RegistrationUiState {
        companion object {
            fun from(challenge: SlideCaptchaChallenge, nowMillis: Long): SlideCaptcha = SlideCaptcha(
                captchaId = challenge.captchaId,
                tolerancePx = challenge.tolerancePx,
                challengeWidth = challenge.challenge.width,
                challengeHeight = challenge.challenge.height,
                challengeImageBase64 = challenge.challenge.imageBase64,
                tileImageBase64 = challenge.tile.image.imageBase64,
                tileWidth = challenge.tile.image.width,
                tileHeight = challenge.tile.image.height,
                tileStartX = challenge.tile.startX,
                tileStartY = challenge.tile.startY,
                expiresAtMillis = nowMillis + challenge.expiresInSeconds * 1_000L,
            )
        }
    }
}

sealed interface RedeemUiState {
    val code: String

    data class Ready(override val code: String = "") : RedeemUiState
    data class Submitting(override val code: String) : RedeemUiState
    data class Success(val message: String = "兑换成功，权益已更新。") : RedeemUiState {
        override val code: String = ""
    }
    data class Error(override val code: String, val message: String) : RedeemUiState
}

internal object RedemptionCodePolicy {
    private val format = Regex("^[A-Z2-7]{6}(?:-[A-Z2-7]{6}){3}$")

    fun normalize(code: String): String = code.trim().uppercase(Locale.ROOT)
    fun isValid(code: String): Boolean = format.matches(code)
}

private const val RedeemOverviewRefreshPendingMessage = "兑换成功，权益详情稍后刷新。"

private fun RedeemUiState.withOverviewRefreshFailureMessage(): RedeemUiState = when (this) {
    is RedeemUiState.Success -> RedeemUiState.Success(RedeemOverviewRefreshPendingMessage)
    else -> this
}

/** 安全页展示的单条关联设备摘要；设备标识一律缩略，不向界面暴露完整标识。 */
data class SecurityDeviceSummary(
    val installIdMasked: String,
    val lastSeenAt: String,
    val isCurrentDevice: Boolean,
)

/**
 * 安全页状态。与 [AccountUiState] 相互独立：devices 为 null 表示尚未成功加载；
 * 加载失败或会话过期都会回到 null（message 非空使失败可被识别），不把陈旧列表当作最新。
 */
data class AccountSecurityUiState(
    val loading: Boolean = false,
    val message: String? = null,
    val devices: List<SecurityDeviceSummary>? = null,
)

/** 设备/会话标识缩略策略：仅当中长标识才保留短前缀供人眼区分；短标识整体掩码，不泄露原文。 */
internal object DeviceIdentityMaskPolicy {
    fun mask(identifier: String): String {
        val trimmed = identifier.trim()
        if (trimmed.isEmpty()) return UnknownLabel
        // 长度不足两倍前缀时，展示前缀几乎等于泄露全文，直接整体掩码。
        if (trimmed.length <= PrefixLength * 2) return MaskSuffix
        return trimmed.take(PrefixLength) + MaskSuffix
    }

    private const val PrefixLength = 8
    private const val MaskSuffix = "…"
    private const val UnknownLabel = "未知"
}

data class AccountUiState(
    val loading: Boolean = false,
    val user: CloudUser? = null,
    val entitlement: CloudEntitlement? = null,
    val overview: AccountOverview? = null,
    val identityProfile: AccountIdentityProfile? = null,
    val usage: UsagePage? = null,
    val message: String? = null,
    val redeem: RedeemUiState = RedeemUiState.Ready(),
    val registration: RegistrationUiState = RegistrationUiState.Details,
    val previewingUserExperience: Boolean = false,
) {
    val signedIn: Boolean get() = user != null
    val isAdmin: Boolean get() = user?.role == CloudRole.ADMIN
    val navigationMode: ProductNavigationMode
        get() = when {
            !signedIn -> ProductNavigationMode.AUTHENTICATION
            isAdmin && !previewingUserExperience -> ProductNavigationMode.ADMIN_TEST
            else -> ProductNavigationMode.USER
        }
}

class AccountViewModel(
    application: Application,
    private val api: AccountApi = CloudApi(CloudEndpointSettings(application), KeystoreTokenStore(application), SharedPreferencesInstallationIdStore(application)),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val registrationRequestGate: RegistrationRequestGate = AtomicRegistrationRequestGate(),
    private val loginIdentifierStore: LoginIdentifierStore = SharedPreferencesLoginIdentifierStore(application),
    private val installationIdStore: InstallationIdStore = SharedPreferencesInstallationIdStore(application),
    localHistory: LocalHistoryRepository? = null,
) : AndroidViewModel(application) {
    private val localHistory by lazy(LazyThreadSafetyMode.NONE) {
        localHistory ?: LocalHistoryRepository.create(application)
    }
    companion object {
        private const val UsagePageSize = 20
        private const val SafeRequestError = "账户状态暂时无法更新，请稍后重试。"
        private const val SessionExpiredMessage = "登录已过期，请重新登录。"
        private const val CaptchaConsumedMessage = "拼图位置未通过校验，已为你获取新的拼图。"

        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(AccountViewModel::class.java)) { "Unsupported ViewModel class" }
                return AccountViewModel(application) as T
            }
        }
    }

    private val mutableState = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = mutableState.asStateFlow()
    private val mutableLatestLoginIdentifier = MutableStateFlow(loginIdentifierStore.read().orEmpty())

    /** 最近登录标识：仅用于登录表单预填，退出登录后保留。 */
    val latestLoginIdentifier: StateFlow<String> = mutableLatestLoginIdentifier.asStateFlow()

    private val mutableSecurityState = MutableStateFlow(AccountSecurityUiState())

    /** 安全页独立状态流，不进入共享账户状态机。 */
    val securityState: StateFlow<AccountSecurityUiState> = mutableSecurityState.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (!api.hasCredentials()) return
        runRequest { api.currentUser() to api.currentEntitlement() }
    }

    fun refreshOverview() = runOverviewRequest { api.accountOverview() }

    /**
     * 权益页加载与重试：同一加载周期内先取概览再取用量明细。
     * 任一步失败都会清空本次目标字段（overview/usage 回到 null，message 非空），
     * 界面据此识别失败并重试；不把上一次的旧数据当作最新展示。
     */
    fun loadEntitlementDetails() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, message = null)
            try {
                val overview = withContext(ioDispatcher) { api.accountOverview() }
                mutableState.value = mutableState.value.copy(overview = overview)
                val usage = withContext(ioDispatcher) { api.usage(UsagePageSize, 0) }
                mutableState.value = mutableState.value.copy(loading = false, usage = usage)
            } catch (error: Exception) {
                // 失败即回到未加载态：清除旧值，避免陈旧数据被当作最新（sessionExpired 由 handleRequestFailure 重置整态）。
                mutableState.value = mutableState.value.copy(overview = null, usage = null)
                handleRequestFailure(error)
            }
        }
    }

    /**
     * 安全页数据：GET /users/me/devices 关联设备列表。
     * 后端在签发翻译会话时按 install_id 登记或刷新设备记录，因此列表是
     * 「在本账户下使用过云翻译的设备」，不是完整登录会话列表；后端没有单设备远程下线 API。
     * 加载失败或会话过期都清空旧列表，不把陈旧数据当作最新。
     */
    fun loadSecurity() {
        viewModelScope.launch {
            mutableSecurityState.value = mutableSecurityState.value.copy(loading = true, message = null)
            try {
                val (currentInstallId, devices) = withContext(ioDispatcher) {
                    installationIdStore.get() to api.devices()
                }
                mutableSecurityState.value = AccountSecurityUiState(
                    loading = false,
                    devices = devices.map { device ->
                        SecurityDeviceSummary(
                            installIdMasked = DeviceIdentityMaskPolicy.mask(device.installId),
                            lastSeenAt = device.lastSeenAt,
                            isCurrentDevice = device.installId == currentInstallId,
                        )
                    },
                )
            } catch (error: Exception) {
                if (error is CloudApiException && error.sessionExpired) {
                    // 会话过期：整体复位，避免上一账号的设备列表残留到下一次登录。
                    mutableState.value = AccountUiState(message = SessionExpiredMessage)
                    mutableSecurityState.value = AccountSecurityUiState()
                    return@launch
                }
                // 普通失败同样清空旧列表：失败后不展示陈旧设备当作最新。
                mutableSecurityState.value = AccountSecurityUiState(
                    loading = false,
                    message = "安全信息暂时无法更新，请稍后重试。",
                )
            }
        }
    }

    fun loadIdentityProfile() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, message = null)
            try {
                val identityProfile = withContext(ioDispatcher) { api.accountIdentityProfile() }
                mutableState.value = mutableState.value.copy(loading = false, identityProfile = identityProfile)
            } catch (error: Exception) {
                handleRequestFailure(error)
            }
        }
    }

    fun loadUsage(limit: Int = UsagePageSize, offset: Int = 0) {
        if (limit !in 1..50 || offset < 0) return
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, message = null)
            try {
                val usage = withContext(ioDispatcher) { api.usage(limit, offset) }
                mutableState.value = mutableState.value.copy(loading = false, usage = usage)
            } catch (error: Exception) {
                handleRequestFailure(error)
            }
        }
    }

    fun requestRegistrationCaptcha(username: String, email: String, password: String) {
        val validation = AuthenticationFormPolicy.register(username, email, password, password)
        if (!validation.isValid) return
        loadRegistrationCaptcha()
    }

    fun refreshRegistrationCaptcha() { loadRegistrationCaptcha() }

    fun returnToRegistrationDetails() {
        if (mutableState.value.registration !is RegistrationUiState.SlideCaptcha) return
        mutableState.value = mutableState.value.copy(registration = RegistrationUiState.Details, message = null)
    }

    private fun loadRegistrationCaptcha() {
        if (!registrationRequestGate.tryAcquire()) return
        mutableState.value = mutableState.value.copy(loading = true, message = null)
        viewModelScope.launch {
            try {
                val challenge = withContext(ioDispatcher) { api.fetchRegistrationCaptcha() }
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    registration = RegistrationUiState.SlideCaptcha.from(challenge, clockMillis()),
                )
            } catch (error: Exception) {
                handleRequestFailure(error)
            } finally {
                registrationRequestGate.release()
            }
        }
    }

    fun confirmRegistrationCaptcha(username: String, email: String, password: String, captchaX: Int) {
        val captcha = mutableState.value.registration as? RegistrationUiState.SlideCaptcha ?: return
        if (!registrationRequestGate.tryAcquire()) return
        mutableState.value = mutableState.value.copy(loading = true, message = null)
        viewModelScope.launch {
            var captchaConsumed = false
            try {
                // 密码仅在本调用内流转，绝不写入 state、SavedStateHandle 或存储。
                val registration = withContext(ioDispatcher) {
                    api.register(username, email, password, captcha.captchaId, captchaX)
                }
                withContext(ioDispatcher) { api.storeTokens(registration.tokens) }
                LatestLoginIdentifierPolicy.registrationIdentifier(registration.user.username)?.let(::rememberLoginIdentifier)
                mutableState.value = AccountUiState(
                    user = registration.user,
                    entitlement = registration.trialEntitlement,
                    registration = RegistrationUiState.Details,
                    previewingUserExperience = mutableState.value.previewingUserExperience && registration.user.role == CloudRole.ADMIN,
                )
            } catch (error: Exception) {
                if (error is CloudApiException && error.sessionExpired) {
                    mutableState.value = AccountUiState(message = SessionExpiredMessage)
                } else if (error is CloudApiException && error.statusCode == 400) {
                    // captcha_failed：服务端已消费该验证码，必须换新拼图重试。
                    captchaConsumed = true
                    mutableState.value = mutableState.value.copy(loading = false, message = CaptchaConsumedMessage)
                } else {
                    handleRequestFailure(error)
                }
            } finally {
                registrationRequestGate.release()
            }
            if (captchaConsumed) loadRegistrationCaptcha()
        }
    }


    fun login(identifier: String, password: String) = runRequest {
        api.login(identifier, password)
        LatestLoginIdentifierPolicy.loginIdentifier(identifier)?.let(::rememberLoginIdentifier)
        api.currentUser() to api.currentEntitlement()
    }

    fun updateIdentity(username: String, email: String, phone: String, currentPassword: String) {
        val validation = AccountIdentityFormPolicy.validate(username, email, phone, currentPassword)
        if (!validation.isValid) {
            mutableState.value = mutableState.value.copy(message = "请检查账户设置后重试。")
            return
        }
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, message = null)
            try {
                withContext(ioDispatcher) {
                    api.updateIdentity(IdentityUpdateRequest(validation.username, validation.email, validation.phone, currentPassword))
                    api.accountOverview()
                }.also { overview ->
                    mutableState.value = mutableState.value.copy(loading = false, overview = overview, message = "账户设置已更新。")
                }
            } catch (error: Exception) {
                handleRequestFailure(error)
            }
        }
    }

    fun setPreviewingUserExperience(enabled: Boolean) {
        if (!mutableState.value.isAdmin) return
        mutableState.value = mutableState.value.copy(previewingUserExperience = enabled)
    }

    fun logout() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, message = null)
            try {
                val userId = mutableState.value.user?.id
                withContext(ioDispatcher) {
                    if (userId != null) localHistory.discardUser(userId)
                    api.logout()
                }
                mutableState.value = AccountUiState(message = "已退出登录。")
                // 安全退出：清空关联设备列表，避免残留到下一个账户。
                mutableSecurityState.value = AccountSecurityUiState()
            } catch (error: Exception) {
                if (error is CloudApiException && error.sessionExpired) {
                    // 令牌已被服务端拒绝且清除：设备列表同样不得残留。
                    mutableSecurityState.value = AccountSecurityUiState()
                }
                handleRequestFailure(error)
            }
        }
    }

    /**
     * 自助删除：确认串必须与当前展示的用户名精确一致才会发起请求；
     * 成功（204）后 CloudApi 已清除令牌，这里再清除登录标识与安装标识，
     * 并把整个账户状态重置为未登录（导航随之回到认证入口）。
     */
    fun deleteAccount(confirmation: String) {
        val state = mutableState.value
        val displayedUsername = state.identityProfile?.username ?: state.user?.username
        if (displayedUsername == null || !AccountDeletionPolicy.confirmationMatches(displayedUsername, confirmation)) {
            mutableState.value = state.copy(message = AccountDeletionPolicy.MismatchMessage)
            return
        }
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, message = null)
            try {
                withContext(ioDispatcher) {
                    val userId = state.user?.id
                    api.deleteAccount(AccountDeletionPolicy.normalizedConfirmation(confirmation))
                    if (userId != null) runCatching { localHistory.discardUser(userId) }
                    loginIdentifierStore.clear()
                    installationIdStore.clear()
                }
                mutableLatestLoginIdentifier.value = ""
                mutableState.value = AccountUiState(message = "账户已删除，本机登录信息已清除。")
                // 账户已不复存在：关联设备列表同步复位，不残留。
                mutableSecurityState.value = AccountSecurityUiState()
            } catch (error: Exception) {
                handleRequestFailure(error)
            }
        }
    }

    fun updateRedeemCode(code: String) {
        val redeem = mutableState.value.redeem
        if (redeem is RedeemUiState.Submitting) return
        mutableState.value = mutableState.value.copy(redeem = RedeemUiState.Ready(code))
    }

    fun redeem() {
        val state = mutableState.value
        if (state.loading || state.redeem is RedeemUiState.Submitting) return
        val code = RedemptionCodePolicy.normalize(state.redeem.code)
        if (code.isEmpty()) {
            mutableState.value = state.copy(redeem = RedeemUiState.Error(state.redeem.code, "请输入兑换码。"))
            return
        }
        if (!RedemptionCodePolicy.isValid(code)) {
            mutableState.value = state.copy(redeem = RedeemUiState.Error(state.redeem.code, "兑换码格式不正确，请检查后重试。"))
            return
        }
        mutableState.value = state.copy(loading = true, message = null, redeem = RedeemUiState.Submitting(code))
        viewModelScope.launch {
            val entitlement = try {
                withContext(ioDispatcher) { api.redeem(code) }
            } catch (error: Exception) {
                handleRedeemFailure(error)
                return@launch
            }

            // 兑换一旦成功即不可回退为失败：先落地接口返回的权益并清空输入，
            // 再独立刷新详情。刷新期间用户可开始输入下一枚兑换码，故后续更新不得重置 redeem。
            mutableState.value = mutableState.value.copy(
                entitlement = entitlement,
                redeem = RedeemUiState.Success(),
            )
            try {
                val overview = withContext(ioDispatcher) { api.accountOverview() }
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    entitlement = overview.entitlement ?: mutableState.value.entitlement,
                    overview = overview,
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (error is CloudApiException && error.sessionExpired) {
                    mutableState.value = AccountUiState(message = SessionExpiredMessage)
                    mutableSecurityState.value = AccountSecurityUiState()
                    return@launch
                }
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    redeem = mutableState.value.redeem.withOverviewRefreshFailureMessage(),
                )
            }
        }
    }

    fun clearMessage() { mutableState.value = mutableState.value.copy(message = null) }

    private fun runOverviewRequest(block: () -> AccountOverview) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, message = null)
            try {
                val overview = withContext(ioDispatcher) { block() }
                mutableState.value = mutableState.value.copy(loading = false, overview = overview)
            } catch (error: Exception) {
                handleRequestFailure(error)
            }
        }
    }

    private fun runRequest(block: () -> Pair<CloudUser, CloudEntitlement?>) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, message = null)
            try {
                val (user, entitlement) = withContext(ioDispatcher) { block() }
                mutableState.value = AccountUiState(
                    user = user,
                    entitlement = entitlement,
                    previewingUserExperience = mutableState.value.previewingUserExperience && user.role == CloudRole.ADMIN,
                )
            } catch (error: Exception) {
                handleRequestFailure(error)
            }
        }
    }

    private fun handleRedeemFailure(error: Exception) {
        val state = mutableState.value
        val message = when ((error as? CloudApiException)?.statusCode) {
            401 -> SessionExpiredMessage
            400 -> "兑换码无效，请检查后重试。"
            409 -> "兑换码暂时无法兑换，请联系支持人员或稍后重试。"
            else -> "网络或服务不可用，请检查连接后重试。"
        }
        if (error is CloudApiException && error.statusCode == 401 && error.sessionExpired) {
            mutableState.value = AccountUiState(message = SessionExpiredMessage)
            mutableSecurityState.value = AccountSecurityUiState()
            return
        }
        val code = state.redeem.code
        mutableState.value = state.copy(loading = false, redeem = RedeemUiState.Error(code, message))
    }

    /** 会话过期时清除登录态并提示重新登录；其余失败维持原状态并给出安全提示。 */
    private fun handleRequestFailure(error: Exception) {
        if (error is CloudApiException && error.sessionExpired) {
            mutableState.value = AccountUiState(message = SessionExpiredMessage)
            mutableSecurityState.value = AccountSecurityUiState()
            return
        }
        mutableState.value = mutableState.value.copy(loading = false, message = SafeRequestError)
    }

    /** 仅记住登录标识用于预填，不保存密码；最近一次成功登录覆盖旧值。 */
    private fun rememberLoginIdentifier(identifier: String) {
        loginIdentifierStore.write(identifier)
        mutableLatestLoginIdentifier.value = identifier
    }

}
