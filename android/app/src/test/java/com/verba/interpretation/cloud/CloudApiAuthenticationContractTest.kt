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
import org.junit.Assert.assertThrows
import org.junit.Test

class CloudApiAuthenticationContractTest {
    @Test fun registerPostsExactPhoneContractAndAcceptsCreated() {
        val fake = FakeHttp(201, "{}")
        val api = CloudApi("https://cloud.example", MemoryTokenStore(), FixedInstallationIdStore(), fake.client)

        api.register("alice_01", "+8613800138000", "Passw0rd")

        val request = fake.singleRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/auth/register", request.url.encodedPath)
        val body = JSONObject(request.body!!.jsonBody())
        assertEquals(3, body.length())
        assertEquals("alice_01", body.getString("username"))
        assertEquals("+8613800138000", body.getString("phone"))
        assertEquals("Passw0rd", body.getString("password"))
    }

    @Test fun loginPostsExactPhoneContractAndStoresTokens() {
        val fake = FakeHttp(200, "{\"access_token\":\"access\",\"refresh_token\":\"refresh\"}")
        val store = MemoryTokenStore()
        val api = CloudApi("https://cloud.example", store, FixedInstallationIdStore(), fake.client)

        api.login("+8613800138000", "Passw0rd")

        val request = fake.singleRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/auth/login", request.url.encodedPath)
        val body = JSONObject(request.body!!.jsonBody())
        assertEquals(2, body.length())
        assertEquals("+8613800138000", body.getString("phone"))
        assertEquals("Passw0rd", body.getString("password"))
        assertEquals(AuthTokens("access", "refresh"), store.read())
    }

    @Test fun currentUserIgnoresEmailAndPhone() {
        val fake = FakeHttp(200, "{\"id\":\"user-1\",\"username\":\"alice_01\",\"role\":\"user\",\"email\":\"private@example.com\",\"phone\":\"+8613800138000\"}")
        val api = CloudApi("https://cloud.example", MemoryTokenStore(AuthTokens("access", "refresh")), FixedInstallationIdStore(), fake.client)

        assertEquals(CloudUser("user-1", "alice_01", CloudRole.USER), api.currentUser())
    }

    @Test fun currentUserRejectsMissingUsernameWithoutEchoingEmail() {
        val fake = FakeHttp(200, "{\"id\":\"user-1\",\"role\":\"user\",\"email\":\"private@example.com\"}")
        val api = CloudApi("https://cloud.example", MemoryTokenStore(AuthTokens("access", "refresh")), FixedInstallationIdStore(), fake.client)

        val error = assertThrows(CloudApiException::class.java) { api.currentUser() }

        assertEquals("服务响应缺少 username。", error.message)
        assertFalse(error.message.orEmpty().contains("private@example.com"))
    }

    @Test fun registrationConflictUsesAvailabilityMessage() {
        val fake = FakeHttp(409, "{\"error\":\"conflict\"}")
        val api = CloudApi("https://cloud.example", MemoryTokenStore(), FixedInstallationIdStore(), fake.client)

        val error = assertThrows(CloudApiException::class.java) { api.register("alice_01", "+8613800138000", "Passw0rd") }

        assertEquals("该手机号或用户名暂不可用，请更换后重试。", error.message)
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
}
