package com.verba.interpretation.ui

import android.app.Application
import com.verba.interpretation.cloud.AccountApi
import com.verba.interpretation.cloud.AccountIdentityProfile
import com.verba.interpretation.cloud.AccountOverview
import com.verba.interpretation.cloud.AuthTokens
import com.verba.interpretation.cloud.CloudEntitlement
import com.verba.interpretation.cloud.CloudApiException
import com.verba.interpretation.cloud.CloudRole
import com.verba.interpretation.cloud.CloudUser
import com.verba.interpretation.cloud.IdentityUpdateRequest
import com.verba.interpretation.cloud.InstallationIdStore
import com.verba.interpretation.cloud.SlideCaptchaChallenge
import com.verba.interpretation.cloud.SlideCaptchaImage
import com.verba.interpretation.cloud.SlideCaptchaTile
import com.verba.interpretation.cloud.UsagePage
import com.verba.interpretation.cloud.UsageSummary
import com.verba.interpretation.ui.account.AccountDeletionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelAccountCenterTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun redeemNormalizesCodeRefreshesOverviewAndClearsInput() {
        val api = AccountCenterApi()
        val viewModel = AccountViewModel(Application(), api, dispatcher)
        viewModel.updateRedeemCode("  aaaaaa-bbbbbb-cccccc-dddddd  ")

        viewModel.redeem()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("AAAAAA-BBBBBB-CCCCCC-DDDDDD", api.redeemedCode)
        assertEquals(1, api.overviewRequests)
        assertEquals("subscription", viewModel.state.value.entitlement?.kind)
        assertEquals("alice_01", viewModel.state.value.overview?.username)
        assertEquals("", viewModel.state.value.redeem.code)
        assertTrue(viewModel.state.value.redeem is RedeemUiState.Success)
    }

    @Test fun redeemOverviewRefreshDoesNotOverwriteCodeEnteredAfterRedemption() {
        val api = AccountCenterApi()
        val viewModel = AccountViewModel(Application(), api, dispatcher)
        api.onOverviewRequested = { viewModel.updateRedeemCode("EEEEEE-FFFFFF-GGGGGG-HHHHHH") }
        viewModel.updateRedeemCode("AAAAAA-BBBBBB-CCCCCC-DDDDDD")

        viewModel.redeem()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("EEEEEE-FFFFFF-GGGGGG-HHHHHH", viewModel.state.value.redeem.code)
        assertEquals("subscription", viewModel.state.value.entitlement?.kind)
        assertEquals("alice_01", viewModel.state.value.overview?.username)
    }

    @Test fun redeemKeepsSuccessAndEntitlementWhenOverviewRefreshFails() {
        val api = AccountCenterApi().apply { overviewFailure = java.io.IOException("offline") }
        val viewModel = AccountViewModel(Application(), api, dispatcher)
        viewModel.updateRedeemCode("AAAAAA-BBBBBB-CCCCCC-DDDDDD")

        viewModel.redeem()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, api.overviewRequests)
        assertEquals("subscription", viewModel.state.value.entitlement?.kind)
        assertEquals(null, viewModel.state.value.overview)
        assertEquals("", viewModel.state.value.redeem.code)
        assertEquals(
            "兑换成功，权益详情稍后刷新。",
            (viewModel.state.value.redeem as RedeemUiState.Success).message,
        )
    }

    @Test fun redeemRejectsInvalidFormatWithoutRequest() {
        val api = AccountCenterApi()
        val viewModel = AccountViewModel(Application(), api, dispatcher)
        viewModel.updateRedeemCode("not-a-code")

        viewModel.redeem()

        assertEquals(null, api.redeemedCode)
        assertEquals("兑换码格式不正确，请检查后重试。", (viewModel.state.value.redeem as RedeemUiState.Error).message)
    }

    @Test fun redeemMapsServerAndNetworkFailuresWithoutLeakingCauseDetails() {
        assertRedeemFailure(CloudApiException("invalid", 400), "兑换码无效，请检查后重试。")
        assertRedeemFailure(CloudApiException("unauthorized", 401), "登录已过期，请重新登录。")
        assertRedeemFailure(CloudApiException("conflict", 409), "兑换码暂时无法兑换，请联系支持人员或稍后重试。")
        assertRedeemFailure(java.io.IOException("offline"), "网络或服务不可用，请检查连接后重试。")
    }

    private fun assertRedeemFailure(failure: Exception, expectedMessage: String) {
        val api = AccountCenterApi().apply { redeemFailure = failure }
        val viewModel = AccountViewModel(Application(), api, dispatcher)
        viewModel.updateRedeemCode("AAAAAA-BBBBBB-CCCCCC-DDDDDD")

        viewModel.redeem()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(expectedMessage, (viewModel.state.value.redeem as RedeemUiState.Error).message)
    }

    @Test fun selfDeletionRequiresExactDisplayedUsernameAndClearsLocalState() {
        val api = AccountCenterApi()
        val install = RecordingInstallationIdStore()
        val viewModel = AccountViewModel(Application(), api, dispatcher, installationIdStore = install)
        viewModel.login("alice_01", "Passw0rd")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.deleteAccount("wrong")
        assertEquals(null, api.deletedUsername)
        assertEquals(AccountDeletionPolicy.MismatchMessage, viewModel.state.value.message)
        viewModel.deleteAccount("alice_01")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("alice_01", api.deletedUsername)
        assertFalse(viewModel.state.value.signedIn)
        assertEquals(ProductNavigationMode.AUTHENTICATION, viewModel.state.value.navigationMode)
        assertEquals(1, install.clears)
    }
}
private class RecordingInstallationIdStore : InstallationIdStore { var clears = 0; override fun get() = "install"; override fun clear() { clears++ } }
private class AccountCenterApi : AccountApi {
    var deletedUsername: String? = null
    var redeemedCode: String? = null
    var redeemFailure: Exception? = null
    var overviewFailure: Exception? = null
    var onOverviewRequested: (() -> Unit)? = null
    var overviewRequests = 0
    override fun fetchRegistrationCaptcha() = SlideCaptchaChallenge("captcha", 300, 6, SlideCaptchaImage("a", "image/jpeg", 300, 220), SlideCaptchaTile(SlideCaptchaImage("b", "image/png", 20, 20), 0, 0))
    override fun register(username: String, email: String, password: String, captchaId: String, captchaX: Int) = error("unused")
    override fun deleteAccount(username: String) { deletedUsername = username }
    override fun login(identifier: String, password: String) = AuthTokens("access", "refresh")
    override fun logout() = Unit
    override fun currentUser() = CloudUser("user-1", "alice_01", CloudRole.USER)
    override fun currentEntitlement(): CloudEntitlement? = null
    override fun redeem(code: String): CloudEntitlement {
        redeemedCode = code
        redeemFailure?.let { throw it }
        return CloudEntitlement("subscription", "2026-09-01")
    }
    override fun hasCredentials() = false
    override fun accountIdentityProfile() = AccountIdentityProfile("alice_01", "alice@example.test", null)
    override fun accountOverview(): AccountOverview {
        overviewRequests++
        onOverviewRequested?.invoke()
        overviewFailure?.let { throw it }
        return AccountOverview("alice_01", CloudEntitlement("subscription", "2026-09-01"), UsageSummary(0, 0, null))
    }
    override fun usage(limit: Int, offset: Int) = UsagePage(emptyList(), 0)
    override fun updateIdentity(request: IdentityUpdateRequest) = Unit
}
