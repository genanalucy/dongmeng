package com.verba.interpretation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.verba.interpretation.cloud.CloudApi
import com.verba.interpretation.cloud.CloudEndpointSettings
import com.verba.interpretation.cloud.CloudEntitlement
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
) {
    val signedIn: Boolean get() = user != null
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

    fun register(email: String, password: String) = runRequest {
        api.register(email, password)
        api.login(email, password)
        api.currentUser() to api.currentEntitlement()
    }

    fun login(email: String, password: String) = runRequest {
        api.login(email, password)
        api.currentUser() to api.currentEntitlement()
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
                mutableState.value = AccountUiState(user = user, entitlement = entitlement)
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(loading = false, message = error.message ?: "网络连接失败，请稍后重试。")
            }
        }
    }
}
