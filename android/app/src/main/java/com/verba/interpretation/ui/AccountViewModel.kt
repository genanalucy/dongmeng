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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

data class AccountUiState(
    val loading: Boolean = false,
    val user: CloudUser? = null,
    val entitlement: CloudEntitlement? = null,
    val overview: AccountOverview? = null,
    val identityProfile: AccountIdentityProfile? = null,
    val usage: UsagePage? = null,
    val message: String? = null,
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

    init { refresh() }

    fun refresh() {
        if (!api.hasCredentials()) return
        runRequest { api.currentUser() to api.currentEntitlement() }
    }

    fun refreshOverview() = runOverviewRequest { api.accountOverview() }

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
            } catch (error: Exception) {
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
            } catch (error: Exception) {
                handleRequestFailure(error)
            }
        }
    }

    fun redeem(code: String) = runRequest {
        val entitlement = api.redeem(code)
        api.currentUser() to entitlement
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

    /** 会话过期时清除登录态并提示重新登录；其余失败维持原状态并给出安全提示。 */
    private fun handleRequestFailure(error: Exception) {
        if (error is CloudApiException && error.sessionExpired) {
            mutableState.value = AccountUiState(message = SessionExpiredMessage)
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
