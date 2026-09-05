package com.verba.interpretation.ui

import android.app.Application
import com.verba.interpretation.cloud.AccountApi
import com.verba.interpretation.cloud.AccountIdentityProfile
import com.verba.interpretation.cloud.AccountOverview
import com.verba.interpretation.cloud.AuthTokens
import com.verba.interpretation.cloud.CloudApi
import com.verba.interpretation.cloud.CloudApiException
import com.verba.interpretation.cloud.CloudEntitlement
import com.verba.interpretation.cloud.CloudRole
import com.verba.interpretation.cloud.CloudUsage
import com.verba.interpretation.cloud.CloudUser
import com.verba.interpretation.cloud.IdentityUpdateRequest
import com.verba.interpretation.cloud.InstallationIdStore
import com.verba.interpretation.cloud.RegistrationResponse
import com.verba.interpretation.cloud.SlideCaptchaChallenge
import com.verba.interpretation.cloud.SlideCaptchaImage
import com.verba.interpretation.cloud.SlideCaptchaTile
import com.verba.interpretation.cloud.TokenStore
import com.verba.interpretation.cloud.UsagePage
import com.verba.interpretation.cloud.UsageSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelPhoneAuthenticationTest {
    private val dispatcher = StandardTestDispatcher()
    private var nowMillis = 1_000L

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun refreshRestoresLegacyUserIntoSignedInState() {
        val transport = RefreshingHttp(listOf(401 to "{\"error\":\"unauthorized\"}", 200 to "{\"access_token\":\"next\",\"refresh_token\":\"rotated\"}", 200 to "{\"id\":\"user-1\",\"role\":\"user\"}", 404 to "{\"error\":\"not_found\"}"))
        val viewModel = AccountViewModel(Application(), CloudApi("https://cloud.example", RefreshTokenStore(AuthTokens("old", "previous")), RefreshInstallationIdStore(), transport.client), dispatcher)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.state.value.signedIn)
        assertEquals("旧版用户", viewModel.state.value.user?.username)
    }

    @Test fun requestCaptchaTransitionsToSlidePuzzleWithoutCredentialsInState() {
        val api = RecordingAccountApi()
        val viewModel = AccountViewModel(Application(), api, dispatcher, { nowMillis })
        viewModel.requestRegistrationCaptcha("alice_01", "alice@example.com", "Passw0rd")
        dispatcher.scheduler.advanceUntilIdle()
        val captcha = viewModel.state.value.registration as RegistrationUiState.SlideCaptcha
        assertEquals("captcha-1", captcha.captchaId)
        assertEquals(61_000L, captcha.expiresAtMillis)
        assertEquals(listOf("fetchCaptcha"), api.calls)
        assertTrue(viewModel.state.value.toString().contains("Passw0rd").not())
    }

    @Test fun failedCaptchaRefreshesChallengeAndDoesNotRegisterUser() {
        val api = RecordingAccountApi(registerError = CloudApiException("failed", 400))
        val viewModel = AccountViewModel(Application(), api, dispatcher, { nowMillis })
        viewModel.requestRegistrationCaptcha("alice_01", "alice@example.com", "Passw0rd")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.confirmRegistrationCaptcha("alice_01", "alice@example.com", "Passw0rd", 42)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("fetchCaptcha", "register:captcha-1:42", "fetchCaptcha"), api.calls)
        assertEquals(null, viewModel.state.value.user)
        assertTrue(viewModel.state.value.registration is RegistrationUiState.SlideCaptcha)
    }

    @Test fun successfulCaptchaRegistrationStoresTokensAndSignsIn() {
        val api = RecordingAccountApi()
        val viewModel = AccountViewModel(Application(), api, dispatcher, { nowMillis })
        viewModel.requestRegistrationCaptcha("alice_01", "alice@example.com", "Passw0rd")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.confirmRegistrationCaptcha("alice_01", "alice@example.com", "Passw0rd", 42)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf("fetchCaptcha", "register:captcha-1:42", "storeTokens"), api.calls)
        assertEquals("alice_01", viewModel.state.value.user?.username)
        assertEquals(RegistrationUiState.Details, viewModel.state.value.registration)
    }
}

private class RefreshTokenStore(private var tokens: AuthTokens?) : TokenStore { override fun read() = tokens; override fun write(tokens: AuthTokens) { this.tokens = tokens }; override fun clear() { tokens = null } }
private class RefreshInstallationIdStore : InstallationIdStore { override fun get() = "install-1"; override fun clear() = Unit }
private class RefreshingHttp(private val responses: List<Pair<Int, String>>) {
    private var index = 0
    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain -> val (status, body) = responses[index++]; Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test").body(body.toResponseBody()).build() }.build()
}
private class RecordingAccountApi(private val registerError: Exception? = null) : AccountApi {
    val calls = mutableListOf<String>()
    override fun fetchRegistrationCaptcha(): SlideCaptchaChallenge { calls += "fetchCaptcha"; return SlideCaptchaChallenge("captcha-1", 60, 6, SlideCaptchaImage("background", "image/jpeg", 300, 220), SlideCaptchaTile(SlideCaptchaImage("tile", "image/png", 40, 40), 0, 20)) }
    override fun register(username: String, email: String, password: String, captchaId: String, captchaX: Int): RegistrationResponse { calls += "register:$captchaId:$captchaX"; registerError?.let { throw it }; return RegistrationResponse(CloudUser("user-1", "alice_01", CloudRole.USER), CloudEntitlement("trial", "2026-09-05T00:00:00Z"), AuthTokens("access", "refresh")) }
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
    override fun storeTokens(tokens: AuthTokens) { calls += "storeTokens" }
}
