package com.verba.interpretation.ui

import android.app.Application
import com.verba.interpretation.cloud.AccountApi
import com.verba.interpretation.cloud.AccountIdentityProfile
import com.verba.interpretation.cloud.AccountOverview
import com.verba.interpretation.cloud.AuthTokens
import com.verba.interpretation.cloud.CloudEntitlement
import com.verba.interpretation.cloud.CloudRole
import com.verba.interpretation.cloud.CloudUsage
import com.verba.interpretation.cloud.CloudUser
import com.verba.interpretation.cloud.IdentityUpdateRequest
import com.verba.interpretation.cloud.UsagePage
import com.verba.interpretation.cloud.UsageSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelAccountCenterTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun loadIdentityProfilePublishesSafeProfileForSettings() {
        val api = AccountCenterApi(identityProfile = AccountIdentityProfile("alice_01", "alice@example.test", "138****8000"))
        val viewModel = AccountViewModel(Application(), api, dispatcher)

        viewModel.loadIdentityProfile()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AccountIdentityProfile("alice_01", "alice@example.test", "138****8000"), viewModel.state.value.identityProfile)
        assertEquals(null, viewModel.state.value.message)
    }

    @Test fun identityProfileFailurePublishesSafeError() {
        val viewModel = AccountViewModel(Application(), AccountCenterApi(identityFailure = true), dispatcher)

        viewModel.loadIdentityProfile()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, viewModel.state.value.identityProfile)
        assertEquals("账户状态暂时无法更新，请稍后重试。", viewModel.state.value.message)
    }

    @Test fun updateIdentityDispatchesOnlyValidNormalizedValuesAndRefreshesOverview() {
        val api = AccountCenterApi()
        val viewModel = AccountViewModel(Application(), api, dispatcher)

        viewModel.updateIdentity("Alice_01", "Alice@Example.Test", "13800138000", "Aa123456")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            IdentityUpdateRequest("alice_01", "alice@example.test", "+8613800138000", "Aa123456"),
            api.identityRequest,
        )
        assertEquals("alice_01", viewModel.state.value.overview?.username)
    }

    @Test fun invalidIdentityDoesNotDispatchNetworkRequest() {
        val api = AccountCenterApi()
        val viewModel = AccountViewModel(Application(), api, dispatcher)

        viewModel.updateIdentity("invalid name", "invalid", "masked", "")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, api.identityRequest)
        assertEquals("请检查账户设置后重试。", viewModel.state.value.message)
    }
}

private class AccountCenterApi(
    private val identityProfile: AccountIdentityProfile = AccountIdentityProfile("alice_01", "alice@example.test", null),
    private val identityFailure: Boolean = false,
) : AccountApi {
    var identityRequest: IdentityUpdateRequest? = null
    override fun register(username: String, email: String, phone: String, password: String) = Unit
    override fun login(identifier: String, password: String): AuthTokens = AuthTokens("access", "refresh")
    override fun logout() = Unit
    override fun currentUser(): CloudUser = CloudUser("user-1", "alice_01", CloudRole.USER)
    override fun currentEntitlement(): CloudEntitlement? = null
    override fun redeem(code: String): CloudEntitlement = CloudEntitlement("trial", "2026-09-01")
    override fun hasCredentials(): Boolean = false
    override fun accountIdentityProfile(): AccountIdentityProfile {
        check(!identityFailure) { "unavailable" }
        return identityProfile
    }
    override fun accountOverview(): AccountOverview = AccountOverview(
        username = "alice_01",
        entitlement = null,
        usage = UsageSummary(0, 0, null),
    )
    override fun usage(limit: Int, offset: Int): UsagePage = UsagePage(emptyList(), 0)
    override fun updateIdentity(request: IdentityUpdateRequest) { identityRequest = request }
}
