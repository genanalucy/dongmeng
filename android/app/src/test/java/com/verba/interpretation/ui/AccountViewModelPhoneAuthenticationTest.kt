package com.verba.interpretation.ui

import android.app.Application
import com.verba.interpretation.cloud.AccountApi
import com.verba.interpretation.cloud.AuthTokens
import com.verba.interpretation.cloud.CloudEntitlement
import com.verba.interpretation.cloud.CloudRole
import com.verba.interpretation.cloud.CloudUser
import com.verba.interpretation.cloud.CloudApi
import com.verba.interpretation.cloud.InstallationIdStore
import com.verba.interpretation.cloud.TokenStore
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
class AccountViewModelPhoneAuthenticationTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun refreshRestoresLegacyUserIntoSignedInState() {
        val transport = RefreshingHttp(listOf(
            401 to "{\"error\":\"unauthorized\"}",
            200 to "{\"access_token\":\"next\",\"refresh_token\":\"rotated\"}",
            200 to "{\"id\":\"user-1\",\"role\":\"user\"}",
            404 to "{\"error\":\"not_found\"}",
        ))
        val api = CloudApi("https://cloud.example", RefreshTokenStore(AuthTokens("old", "previous")), RefreshInstallationIdStore(), transport.client)

        val viewModel = AccountViewModel(Application(), api, dispatcher)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, viewModel.state.value.signedIn)
        assertEquals("旧版用户", viewModel.state.value.user?.username)
        assertEquals(listOf("/api/v1/users/me", "/api/v1/auth/refresh", "/api/v1/users/me", "/api/v1/entitlements/current"), transport.paths())
    }

    @Test fun registerKeepsAutomaticLoginAndFetchSequence() {
        val api = RecordingAccountApi()
        val viewModel = AccountViewModel(Application(), api, dispatcher)

        viewModel.register("alice_01", "alice@example.com", "+8613800138000", "Passw0rd")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf("register:alice_01:alice@example.com:+8613800138000", "login:+8613800138000", "currentUser", "currentEntitlement"),
            api.calls,
        )
        assertEquals("alice_01", viewModel.state.value.user?.username)
    }
}

private class RefreshTokenStore(private var tokens: AuthTokens?) : TokenStore {
    override fun read(): AuthTokens? = tokens
    override fun write(tokens: AuthTokens) { this.tokens = tokens }
    override fun clear() { tokens = null }
}

private class RefreshInstallationIdStore : InstallationIdStore {
    override fun get(): String = "install-1"
}

private class RefreshingHttp(private val responses: List<Pair<Int, String>>) {
    private val requests = mutableListOf<Request>()
    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request()
        val (status, body) = responses[requests.lastIndex]
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test").body(body.toResponseBody()).build()
    }.build()

    fun paths(): List<String> = requests.map { it.url.encodedPath }
}

private class RecordingAccountApi : AccountApi {
    val calls = mutableListOf<String>()
    override fun register(username: String, email: String, phone: String, password: String) { calls += "register:$username:$email:$phone" }
    override fun login(identifier: String, password: String): AuthTokens {
        calls += "login:$identifier"
        return AuthTokens("access", "refresh")
    }
    override fun logout() = Unit
    override fun currentUser(): CloudUser {
        calls += "currentUser"
        return CloudUser("user-1", "alice_01", CloudRole.USER)
    }
    override fun currentEntitlement(): CloudEntitlement? {
        calls += "currentEntitlement"
        return CloudEntitlement("trial", "2026-09-01")
    }
    override fun redeem(code: String): CloudEntitlement = error("not used")
    override fun hasCredentials(): Boolean = false
    override fun accountOverview() = com.verba.interpretation.cloud.AccountOverview("alice_01", null, com.verba.interpretation.cloud.UsageSummary(0, 0, null))
    override fun accountIdentityProfile() = com.verba.interpretation.cloud.AccountIdentityProfile("alice_01", "alice@example.test", null)
    override fun usage(limit: Int, offset: Int) = com.verba.interpretation.cloud.UsagePage(emptyList(), 0)
    override fun updateIdentity(request: com.verba.interpretation.cloud.IdentityUpdateRequest) = Unit
}
