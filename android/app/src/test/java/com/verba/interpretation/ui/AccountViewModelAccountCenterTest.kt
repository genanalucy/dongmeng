package com.verba.interpretation.ui

import android.app.Application
import com.verba.interpretation.cloud.AccountApi
import com.verba.interpretation.cloud.AccountIdentityProfile
import com.verba.interpretation.cloud.AccountOverview
import com.verba.interpretation.cloud.AuthTokens
import com.verba.interpretation.cloud.CloudEntitlement
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelAccountCenterTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

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
    override fun fetchRegistrationCaptcha() = SlideCaptchaChallenge("captcha", 300, 6, SlideCaptchaImage("a", "image/jpeg", 300, 220), SlideCaptchaTile(SlideCaptchaImage("b", "image/png", 20, 20), 0, 0))
    override fun register(username: String, email: String, password: String, captchaId: String, captchaX: Int) = error("unused")
    override fun deleteAccount(username: String) { deletedUsername = username }
    override fun login(identifier: String, password: String) = AuthTokens("access", "refresh")
    override fun logout() = Unit
    override fun currentUser() = CloudUser("user-1", "alice_01", CloudRole.USER)
    override fun currentEntitlement(): CloudEntitlement? = null
    override fun redeem(code: String) = CloudEntitlement("trial", "2026-09-01")
    override fun hasCredentials() = false
    override fun accountIdentityProfile() = AccountIdentityProfile("alice_01", "alice@example.test", null)
    override fun accountOverview() = AccountOverview("alice_01", null, UsageSummary(0, 0, null))
    override fun usage(limit: Int, offset: Int) = UsagePage(emptyList(), 0)
    override fun updateIdentity(request: IdentityUpdateRequest) = Unit
}
