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
import com.verba.interpretation.cloud.CloudRole
import com.verba.interpretation.cloud.CloudUsage
import com.verba.interpretation.cloud.CloudUser
import com.verba.interpretation.cloud.IdentityUpdateRequest
import com.verba.interpretation.cloud.KeystoreTokenStore
import com.verba.interpretation.cloud.SharedPreferencesInstallationIdStore
import com.verba.interpretation.cloud.UsagePage
import com.verba.interpretation.ui.account.AccountIdentityFormPolicy
import com.verba.interpretation.ui.account.RegistrationResendPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

sealed interface RegistrationUiState {
    data object Details : RegistrationUiState
    data class Challenge(val username: String, val email: String, val maskedEmail: String, val resendAvailableAtMillis: Long) : RegistrationUiState
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
) : AndroidViewModel(application) {
    companion object {
        private const val UsagePageSize = 20
        private const val SafeRequestError = "账户状态暂时无法更新，请稍后重试。"

        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(AccountViewModel::class.java)) { "Unsupported ViewModel class" }
                return AccountViewModel(application) as T
            }
        }
    }

    private val mutableState = MutableStateFlow(AccountUiState())
    private val registrationRequestInFlight = AtomicBoolean(false)
    val state: StateFlow<AccountUiState> = mutableState.asStateFlow()

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
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, message = SafeRequestError)
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
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, message = SafeRequestError)
            }
        }
    }

    fun requestRegistrationVerification(username: String, email: String, password: String) {
        if (!registrationRequestInFlight.compareAndSet(false, true)) return
        mutableState.value = mutableState.value.copy(loading = true, message = null)
        viewModelScope.launch {
            try {
                // This ViewModel never writes the password to state, SavedStateHandle, or storage.
                val retryAfterSeconds = withContext(ioDispatcher) {
                    api.requestRegistrationVerification(username, email, password)
                }
                mutableState.value = mutableState.value.copy(
                    loading = false,
                    registration = RegistrationUiState.Challenge(username, email, email.maskedEmail(), clockMillis() + retryAfterSeconds * 1_000L),
                )
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, message = SafeRequestError)
            } finally {
                registrationRequestInFlight.set(false)
            }
        }
    }

    fun resendRegistrationVerification(username: String, email: String, password: String) {
        val challenge = mutableState.value.registration as? RegistrationUiState.Challenge ?: return
        if (challenge.username != username || challenge.email != email || RegistrationResendPolicy.remainingSeconds(challenge.resendAvailableAtMillis, clockMillis()) != 0) return
        requestRegistrationVerification(username, email, password)
    }

    fun returnToRegistrationDetails() {
        mutableState.value = mutableState.value.copy(registration = RegistrationUiState.Details, message = null)
    }

    fun confirmRegistrationVerification(email: String, code: String) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, message = null)
            try {
                // This ViewModel never writes the code to state, SavedStateHandle, or storage.
                val registration = withContext(ioDispatcher) {
                    api.confirmRegistrationVerification(email, code)
                }
                withContext(ioDispatcher) { api.storeTokens(registration.tokens) }
                mutableState.value = AccountUiState(
                    user = registration.user,
                    entitlement = registration.trialEntitlement,
                    registration = RegistrationUiState.Details,
                    previewingUserExperience = mutableState.value.previewingUserExperience && registration.user.role == CloudRole.ADMIN,
                )
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, message = SafeRequestError)
            }
        }
    }


    fun login(identifier: String, password: String) = runRequest {
        api.login(identifier, password)
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
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, message = SafeRequestError)
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
                withContext(ioDispatcher) { api.logout() }
                mutableState.value = AccountUiState(message = "已退出登录。")
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, message = SafeRequestError)
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
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, message = SafeRequestError)
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
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, message = SafeRequestError)
            }
        }
    }

}

private fun String.maskedEmail(): String {
    val at = indexOf('@')
    if (at <= 0) return "***"
    val local = substring(0, at)
    return "${local.first()}***${local.last()}${substring(at)}"
}
