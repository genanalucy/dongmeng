package com.verba.interpretation.cloud

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudApiAuthenticationContractTest {
    @Test fun fetchRegistrationCaptchaReadsNativeSlidePuzzleContract() {
        val fake = FakeHttp(200, """{"captcha_id":"captcha-1","expires_in":300,"tolerance_px":6,"challenge":{"image_base64":"YmFja2dyb3VuZA==","image_type":"image/jpeg","width":300,"height":220},"tile":{"image_base64":"dGlsZQ==","image_type":"image/png","width":40,"height":40,"start_x":0,"start_y":20}}""")
        val api = CloudApi("https://cloud.example", MemoryTokenStore(), FixedInstallationIdStore(), fake.client)

        val captcha = api.fetchRegistrationCaptcha()

        assertEquals("captcha-1", captcha.captchaId)
        assertEquals(300, captcha.challenge.width)
        assertEquals(20, captcha.tile.startY)
        assertEquals("GET", fake.singleRequest().method)
        assertEquals("/api/v1/auth/captcha", fake.singleRequest().url.encodedPath)
    }

    @Test fun registerPostsCaptchaCoordinateContract() {
        val fake = FakeHttp(201, """{"user":{"id":"user-1","username":"alice_01","role":"user"},"trial_entitlement":{"kind":"trial","expires_at":"2026-09-05T00:00:00Z"},"access_token":"access","refresh_token":"refresh"}""")
        val api = CloudApi("https://cloud.example", MemoryTokenStore(), FixedInstallationIdStore(), fake.client)

        api.register("alice_01", "alice@example.com", "Passw0rd", "captcha-1", 42)

        val request = fake.singleRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/auth/register", request.url.encodedPath)
        val body = JSONObject(request.body!!.jsonBody())
        assertEquals(5, body.length())
        assertEquals(42, body.getInt("captcha_x"))
        assertFalse(body.has("captcha_answer"))
    }

    @Test fun deleteAccountUsesExactUsernameAndClearsTokensOnNoContent() {
        val fake = FakeHttp(204, "")
        val store = MemoryTokenStore(AuthTokens("access", "refresh"))
        val api = CloudApi("https://cloud.example", store, FixedInstallationIdStore(), fake.client)

        api.deleteAccount("alice_01")

        val request = fake.singleRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/v1/account", request.url.encodedPath)
        assertEquals("alice_01", JSONObject(request.body!!.jsonBody()).getString("username"))
        assertNull(store.read())
    }

    @Test fun loginPostsIdentifierContractAndStoresTokens() {
        val fake = FakeHttp(200, "{\"access_token\":\"access\",\"refresh_token\":\"refresh\"}")
        val store = MemoryTokenStore()
        val api = CloudApi("https://cloud.example", store, FixedInstallationIdStore(), fake.client)

        api.login("alice@example.com", "Passw0rd")

        val request = fake.singleRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/auth/login", request.url.encodedPath)
        val body = JSONObject(request.body!!.jsonBody())
        assertEquals(2, body.length())
        assertEquals("alice@example.com", body.getString("identifier"))
        assertEquals("Passw0rd", body.getString("password"))
        assertEquals(AuthTokens("access", "refresh"), store.read())
    }

    @Test fun identityProfileGetsSafeIdentityContractWithoutFullPhone() {
        val fake = FakeHttp(200, "{\"username\":\"alice_01\",\"email\":\"alice@example.test\",\"masked_phone\":\"138****8000\"}")
        val api = CloudApi("https://cloud.example", MemoryTokenStore(AuthTokens("access", "refresh")), FixedInstallationIdStore(), fake.client)

        assertEquals(AccountIdentityProfile("alice_01", "alice@example.test", "138****8000"), api.accountIdentityProfile())

        val request = fake.singleRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/account/identity", request.url.encodedPath)
        assertEquals("Bearer access", request.header("Authorization"))
    }

    @Test fun identityProfileAllowsLegacyResponseWithoutMaskedPhone() {
        val fake = FakeHttp(200, "{\"username\":\"alice_01\",\"email\":\"alice@example.test\"}")
        val api = CloudApi("https://cloud.example", MemoryTokenStore(AuthTokens("access", "refresh")), FixedInstallationIdStore(), fake.client)

        assertEquals(AccountIdentityProfile("alice_01", "alice@example.test", null), api.accountIdentityProfile())
    }

    @Test fun currentUserIgnoresEmailAndPhone() {
        val fake = FakeHttp(200, "{\"id\":\"user-1\",\"username\":\"alice_01\",\"role\":\"user\",\"email\":\"private@example.com\",\"phone\":\"+8613800138000\"}")
        val api = CloudApi("https://cloud.example", MemoryTokenStore(AuthTokens("access", "refresh")), FixedInstallationIdStore(), fake.client)

        assertEquals(CloudUser("user-1", "alice_01", CloudRole.USER), api.currentUser())
    }

    @Test fun currentUserUsesFixedNonEmailNameWhenLegacyUsernameIsMissing() {
        val fake = FakeHttp(200, "{\"id\":\"user-1\",\"role\":\"user\",\"email\":\"private@example.com\"}")
        val api = CloudApi("https://cloud.example", MemoryTokenStore(AuthTokens("access", "refresh")), FixedInstallationIdStore(), fake.client)

        val user = api.currentUser()

        assertEquals(CloudUser("user-1", "旧版用户", CloudRole.USER), user)
        assertFalse(user.username.contains("private@example.com"))
    }

    @Test fun loginMapsUnauthorizedToCredentialMessageOnlyForLogin() {
        val fake = FakeHttp(401, "{\"error\":\"unauthorized\"}")
        val api = CloudApi("https://cloud.example", MemoryTokenStore(), FixedInstallationIdStore(), fake.client)

        val error = assertThrows(CloudApiException::class.java) { api.login("alice@example.com", "wrong") }

        assertEquals("账号或密码错误。", error.message)
        assertEquals(401, error.statusCode)
    }

    @Test fun currentUserRefreshesThenUsesLegacyFallback() {
        val fake = ChainedHttp(listOf(
            401 to "{\"error\":\"unauthorized\"}",
            200 to "{\"access_token\":\"next\",\"refresh_token\":\"rotated\"}",
            200 to "{\"id\":\"user-1\",\"role\":\"user\"}",
        ))
        val store = MemoryTokenStore(AuthTokens("old", "previous"))
        val api = CloudApi("https://cloud.example", store, FixedInstallationIdStore(), fake.client)

        val user = api.currentUser()

        assertEquals(CloudUser("user-1", "旧版用户", CloudRole.USER), user)
        assertTrue(store.read() != AuthTokens("old", "previous"))
        assertEquals(listOf("/api/v1/users/me", "/api/v1/auth/refresh", "/api/v1/users/me"), fake.paths())
    }

    @Test fun authenticatedRefreshFailureKeepsExpiryMessage() {
        val fake = ChainedHttp(listOf(
            401 to "{\"error\":\"unauthorized\"}",
            401 to "{\"error\":\"unauthorized\"}",
        ))
        val api = CloudApi("https://cloud.example", MemoryTokenStore(AuthTokens("old", "previous")), FixedInstallationIdStore(), fake.client)

        val error = assertThrows(CloudApiException::class.java) { api.currentUser() }

        assertEquals("登录状态已过期，请重新登录。", error.message)
        assertTrue(error.message != "账号或密码错误。")
    }

    @Test fun authenticatedRefreshFailureClearsTokensAndMarksSessionExpired() {
        val fake = ChainedHttp(listOf(
            401 to "{\"error\":\"unauthorized\"}",
            401 to "{\"error\":\"unauthorized\"}",
        ))
        val store = MemoryTokenStore(AuthTokens("old", "previous"))
        val api = CloudApi("https://cloud.example", store, FixedInstallationIdStore(), fake.client)

        val error = assertThrows(CloudApiException::class.java) { api.currentUser() }

        assertEquals(401, error.statusCode)
        assertTrue(error.sessionExpired)
        assertNull(store.read())
    }

    @Test fun nonUnauthorizedRefreshFailureKeepsTokens() {
        val fake = ChainedHttp(listOf(
            401 to "{\"error\":\"unauthorized\"}",
            500 to "{\"error\":\"internal\"}",
        ))
        val store = MemoryTokenStore(AuthTokens("old", "previous"))
        val api = CloudApi("https://cloud.example", store, FixedInstallationIdStore(), fake.client)

        val error = assertThrows(CloudApiException::class.java) { api.currentUser() }

        assertEquals(false, error.sessionExpired)
        assertEquals(AuthTokens("old", "previous"), store.read())
    }

    @Test fun registrationConflictUsesAvailabilityMessage() {
        val fake = FakeHttp(409, "{\"error\":\"conflict\"}")
        val api = CloudApi("https://cloud.example", MemoryTokenStore(), FixedInstallationIdStore(), fake.client)

        val error = assertThrows(CloudApiException::class.java) { api.register("alice_01", "alice@example.com", "Passw0rd", "captcha-1", 42) }

        assertEquals("该用户名或邮箱暂不可用，请更换后重试。", error.message)
    }

    @Test fun translationSessionConflictKeepsSessionMessage() {
        val fake = FakeHttp(409, "{\"error\":\"conflict\"}")
        val api = CloudApi("https://cloud.example", MemoryTokenStore(AuthTokens("access", "refresh")), FixedInstallationIdStore(), fake.client)

        val error = assertThrows(CloudApiException::class.java) { api.createTranslationSession() }

        assertEquals("当前已有进行中的翻译会话，请先结束它。", error.message)
    }
}

private fun okhttp3.RequestBody.jsonBody(): String = Buffer().use { buffer ->
    writeTo(buffer)
    buffer.readUtf8()
}

private class ChainedHttp(private val responses: List<Pair<Int, String>>) {
    private val requests = mutableListOf<Request>()
    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request()
        val (status, payload) = responses[requests.lastIndex]
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("test").body(payload.toResponseBody()).build()
    }.build()

    fun paths(): List<String> = requests.map { it.url.encodedPath }
}

private class FakeHttp(private val status: Int, private val payload: String) {
    private val requests = mutableListOf<Request>()
    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request()
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(status)
            .message("test")
            .body(payload.toResponseBody())
            .build()
    }.build()

    fun singleRequest(): Request = requests.single()
}

private class MemoryTokenStore(initial: AuthTokens? = null) : TokenStore {
    private var tokens = initial
    override fun read(): AuthTokens? = tokens
    override fun write(tokens: AuthTokens) { this.tokens = tokens }
    override fun clear() { tokens = null }
}

private class FixedInstallationIdStore : InstallationIdStore {
    override fun get(): String = "install-1"
    override fun clear() = Unit
}
