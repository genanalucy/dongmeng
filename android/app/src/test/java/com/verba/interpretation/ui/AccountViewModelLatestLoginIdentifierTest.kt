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
import com.verba.interpretation.cloud.LoginIdentifierStore
import com.verba.interpretation.cloud.RegistrationResponse
import com.verba.interpretation.cloud.SlideCaptchaChallenge
import com.verba.interpretation.cloud.SlideCaptchaImage
import com.verba.interpretation.cloud.SlideCaptchaTile
import com.verba.interpretation.cloud.UsagePage
import com.verba.interpretation.cloud.UsageSummary
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
class AccountViewModelLatestLoginIdentifierTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun loginSuccessPersistsOnlyNormalizedIdentifier() {
        val identifiers = RecordingLoginIdentifierStore()
        val viewModel = AccountViewModel(Application(), IdentifierAccountApi(), dispatcher, loginIdentifierStore = identifiers)
        viewModel.login(" alice_01@example.com ", "Passw0rd!")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("alice_01@example.com"), identifiers.written)
        assertFalse(identifiers.written.any { it.contains("Passw0rd") })
    }

    @Test fun captchaRegistrationPersistsUsernameNotPassword() {
        val identifiers = RecordingLoginIdentifierStore()
        val viewModel = AccountViewModel(Application(), IdentifierAccountApi(), dispatcher, loginIdentifierStore = identifiers)
        viewModel.requestRegistrationCaptcha("alice_01", "alice@example.com", "Passw0rd!")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.confirmRegistrationCaptcha("alice_01", "alice@example.com", "Passw0rd!", 0)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("alice_01"), identifiers.written)
        assertFalse(identifiers.written.any { it.contains("Passw0rd") })
    }
}
private class RecordingLoginIdentifierStore : LoginIdentifierStore { val written = mutableListOf<String>(); private var identifier: String? = null; override fun read() = identifier; override fun write(identifier: String) { written += identifier; this.identifier = identifier }; override fun clear() { identifier = null } }
private class IdentifierAccountApi : AccountApi {
    override fun fetchRegistrationCaptcha() = SlideCaptchaChallenge("captcha", 300, 6, SlideCaptchaImage("a", "image/jpeg", 300, 220), SlideCaptchaTile(SlideCaptchaImage("b", "image/png", 20, 20), 0, 0))
    override fun register(username: String, email: String, password: String, captchaId: String, captchaX: Int) = RegistrationResponse(CloudUser("user-1", "alice_01", CloudRole.USER), CloudEntitlement("trial", "2026-09-05"), AuthTokens("access", "refresh"))
    override fun deleteAccount(username: String) = Unit
    override fun login(identifier: String, password: String) = AuthTokens("access", "refresh")
    override fun logout() = Unit
    override fun currentUser() = CloudUser("user-1", "alice_01", CloudRole.USER)
    override fun currentEntitlement(): CloudEntitlement? = CloudEntitlement("trial", "2026-09-01")
    override fun redeem(code: String) = CloudEntitlement("trial", "2026-09-01")
    override fun hasCredentials() = false
    override fun accountOverview() = AccountOverview("alice_01", null, UsageSummary(0, 0, null))
    override fun accountIdentityProfile() = AccountIdentityProfile("alice_01", "alice@example.test", null)
    override fun usage(limit: Int, offset: Int) = UsagePage(emptyList(), 0)
    override fun updateIdentity(request: IdentityUpdateRequest) = Unit
}
