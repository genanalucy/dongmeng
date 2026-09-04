package com.verba.interpretation.ui

import android.app.Application
import com.verba.interpretation.cloud.AccountApi
import com.verba.interpretation.cloud.AccountIdentityProfile
import com.verba.interpretation.cloud.AccountOverview
import com.verba.interpretation.cloud.AuthTokens
import com.verba.interpretation.cloud.CloudApi
import com.verba.interpretation.cloud.CloudEntitlement
import com.verba.interpretation.cloud.CloudRole
import com.verba.interpretation.cloud.CloudUser
import com.verba.interpretation.cloud.InstallationIdStore
import com.verba.interpretation.cloud.LoginIdentifierStore
import com.verba.interpretation.cloud.RegistrationResponse
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelLatestLoginIdentifierTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun loginSuccessPersistsOnlyTheIdentifierForLaterPrefill() {
        val api = IdentifierAccountApi()
        val identifiers = RecordingLoginIdentifierStore()
        val viewModel = AccountViewModel(Application(), api, dispatcher, loginIdentifierStore = identifiers)

        viewModel.login(" alice_01@example.com ", "Passw0rd!")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("alice_01@example.com"), identifiers.written)
        assertEquals("alice_01@example.com", viewModel.latestLoginIdentifier.value)
        assertFalse(identifiers.written.any { it.contains("Passw0rd") })
    }

    @Test fun verifiedRegistrationPersistsTheChosenUsername() {
        val api = IdentifierAccountApi()
        val identifiers = RecordingLoginIdentifierStore()
        val viewModel = AccountViewModel(Application(), api, dispatcher, loginIdentifierStore = identifiers)

        viewModel.confirmRegistrationVerification("alice@example.com", "012345")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("alice_01"), identifiers.written)
        assertEquals("alice_01", viewModel.latestLoginIdentifier.value)
        assertFalse(identifiers.written.any { it.contains("012345") })
    }

    @Test fun logoutPreservesTheLatestLoginIdentifier() {
        val api = IdentifierAccountApi()
        val identifiers = RecordingLoginIdentifierStore()
        val viewModel = AccountViewModel(Application(), api, dispatcher, loginIdentifierStore = identifiers)

        viewModel.login("alice_01@example.com", "Passw0rd!")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.logout()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.state.value.signedIn)
        assertEquals("已退出登录。", viewModel.state.value.message)
        assertEquals("alice_01@example.com", identifiers.read())
        assertEquals("alice_01@example.com", viewModel.latestLoginIdentifier.value)
        assertEquals(emptyList<String>(), identifiers.cleared)
    }

    @Test fun expiredRefreshTokenClearsTokensSurfacesSafeStateAndKeepsIdentifier() {
        val transport = ExpiringHttp(listOf(
            401 to "{\"error\":\"unauthorized\"}",
            401 to "{\"error\":\"unauthorized\"}",
        ))
        val tokenStore = ExpiringTokenStore(AuthTokens("old", "previous"))
        val identifiers = RecordingLoginIdentifierStore()
        identifiers.write("alice_01@example.com")
        val api = CloudApi("https://cloud.example", tokenStore, ExpiringInstallationIdStore(), transport.client)

        val viewModel = AccountViewModel(Application(), api, dispatcher, loginIdentifierStore = identifiers)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, viewModel.state.value.signedIn)
        assertEquals(ProductNavigationMode.AUTHENTICATION, viewModel.state.value.navigationMode)
        assertEquals("登录已过期，请重新登录。", viewModel.state.value.message)
        assertNull(tokenStore.read())
        assertEquals("alice_01@example.com", identifiers.read())
        assertEquals("alice_01@example.com", viewModel.latestLoginIdentifier.value)
    }

    @Test fun signInRestoresPersistedIdentifierIntoThePrefillFlow() {
        val api = IdentifierAccountApi()
        val identifiers = RecordingLoginIdentifierStore()
        identifiers.write("alice_01@example.com")
        val viewModel = AccountViewModel(Application(), api, dispatcher, loginIdentifierStore = identifiers)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("alice_01@example.com", viewModel.latestLoginIdentifier.value)
    }
}

private class ExpiringTokenStore(private var tokens: AuthTokens?) : TokenStore {
    override fun read(): AuthTokens? = tokens
    override fun write(tokens: AuthTokens) { this.tokens = tokens }
    override fun clear() { tokens = null }
}

private class ExpiringInstallationIdStore : InstallationIdStore {
    override fun get(): String = "install-1"
}

private class ExpiringHttp(private val responses: List<Pair<Int, String>>) {
    private val requests = mutableListOf<Request>()
    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request()
        val (status, body) = responses[requests.lastIndex]
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test").body(body.toResponseBody()).build()
    }.build()
}

private class RecordingLoginIdentifierStore : LoginIdentifierStore {
    val written = mutableListOf<String>()
    val cleared = mutableListOf<String>()
    private var identifier: String? = null

    override fun read(): String? = identifier
    override fun write(identifier: String) {
        written += identifier
        this.identifier = identifier
    }
    override fun clear() {
        identifier?.let { cleared += it }
        identifier = null
    }
}

private class IdentifierAccountApi : AccountApi {
    override fun confirmRegistrationVerification(email: String, code: String): RegistrationResponse =
        RegistrationResponse(
            CloudUser("user-1", "alice_01", CloudRole.USER),
            CloudEntitlement("trial", "2026-09-05T00:00:00Z", "2026-09-02T00:00:00Z"),
            AuthTokens("access", "refresh"),
        )

    override fun login(identifier: String, password: String): AuthTokens = AuthTokens("access", "refresh")
    override fun logout() = Unit
    override fun currentUser(): CloudUser = CloudUser("user-1", "alice_01", CloudRole.USER)
    override fun currentEntitlement(): CloudEntitlement? = CloudEntitlement("trial", "2026-09-01")
    override fun redeem(code: String): CloudEntitlement = CloudEntitlement("trial", "2026-09-01")
    override fun hasCredentials(): Boolean = false
    override fun accountOverview(): AccountOverview = AccountOverview("alice_01", null, UsageSummary(0, 0, null))
    override fun accountIdentityProfile(): AccountIdentityProfile = AccountIdentityProfile("alice_01", "alice@example.test", null)
    override fun usage(limit: Int, offset: Int): UsagePage = UsagePage(emptyList(), 0)
    override fun updateIdentity(request: com.verba.interpretation.cloud.IdentityUpdateRequest) = Unit
}
