package com.verba.interpretation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.verba.interpretation.cloud.CloudApi
import com.verba.interpretation.cloud.CloudEndpointSettings
import com.verba.interpretation.cloud.CloudEntitlement
import com.verba.interpretation.cloud.CloudRole
import com.verba.interpretation.cloud.CloudUser
import com.verba.interpretation.cloud.KeystoreTokenStore
import com.verba.interpretation.cloud.SharedPreferencesInstallationIdStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AccountUiState(
    val loading: Boolean = false,
    val user: CloudUser? = null,
    val entitlement: CloudEntitlement? = null,
    val message: String? = null,
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

class AccountViewModel(application: Application) : AndroidViewModel(application) {
    private val api = CloudApi(CloudEndpointSettings(application), KeystoreTokenStore(application), SharedPreferencesInstallationIdStore(application))
    private val mutableState = MutableStateFlow(AccountUiState())
    val state: StateFlow<AccountUiState> = mutableState.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (!api.hasCredentials()) return
        runRequest { api.currentUser() to api.currentEntitlement() }
    }

    fun register(username: String, phone: String, password: String) = runRequest {
        api.register(username, phone, password)
        api.login(phone, password)
        api.currentUser() to api.currentEntitlement()
    }

    // Temporary bridge for the pre-Task5 form; it does not add an email authentication path.
    fun register(legacyIdentifierInput: String, password: String) = register("", legacyIdentifierInput, password)

    fun login(phone: String, password: String) = runRequest {
        api.login(phone, password)
        api.currentUser() to api.currentEntitlement()
    }

    fun setPreviewingUserExperience(enabled: Boolean) {
        if (!mutableState.value.isAdmin) return
        mutableState.value = mutableState.value.copy(previewingUserExperience = enabled)
    }

    fun logout() {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, message = null)
            withContext(Dispatchers.IO) { api.logout() }
            mutableState.value = AccountUiState(message = "已退出登录。")
        }
    }

    fun redeem(code: String) = runRequest {
        val entitlement = api.redeem(code)
        api.currentUser() to entitlement
    }

    fun clearMessage() {
        mutableState.value = mutableState.value.copy(message = null)
    }

    private fun runRequest(block: () -> Pair<CloudUser, CloudEntitlement?>) {
        viewModelScope.launch {
            mutableState.value = mutableState.value.copy(loading = true, message = null)
            try {
                val (user, entitlement) = withContext(Dispatchers.IO) { block() }
                mutableState.value = AccountUiState(
                    user = user,
                    entitlement = entitlement,
                    previewingUserExperience = mutableState.value.previewingUserExperience && user.role == CloudRole.ADMIN,
                )
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, message = error.message ?: "网络连接失败，请稍后重试。")
            }
        }
    }
}
