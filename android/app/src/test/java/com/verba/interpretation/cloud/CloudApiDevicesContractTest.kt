package com.verba.interpretation.cloud

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GET /api/v1/users/me/devices 契约（与 cloud-api 实际实现对齐）：
 * - handler `api.devices` 经 writeJSON 返回 `{"devices":[<domain.Device>]}`；
 * - domain.Device 字段为 id / install_id / last_seen_at / created_at（RFC3339 时间）；
 * - store.ListDevices 初始化空切片，无设备时序列化为 `{"devices":[]}` 而非 null；
 * - 服务端按 last_seen_at DESC 排序，客户端必须原样保持顺序。
 */
class CloudApiDevicesContractTest {
    @Test fun appUpdateUsesPublicEndpointAndRejectsUnsafeMetadata() {
        val fake = DevicesTestFakeHttp(200, """{"available":true,"package_name":"com.verba.interpretation","version_code":2,"version_name":"1.1","apk_url":"https://downloads.example/app.apk","apk_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","release_notes":"修复","force_update":false}""")
        val api = CloudApi("https://cloud.example", DevicesTestMemoryTokenStore(AuthTokens("access", "refresh")), DevicesTestFixedInstallationIdStore(), fake.client)

        val update = api.appUpdate()

        assertEquals("GET", fake.singleRequest().method)
        assertEquals("/api/v1/app-update", fake.singleRequest().url.encodedPath)
        assertEquals(null, fake.singleRequest().header("Authorization"))
        assertEquals(2, update!!.versionCode)
        assertEquals("https://downloads.example/app.apk", update.apkUrl)
    }

    @Test fun appUpdateRejectsNonHttpsDownload() {
        val fake = DevicesTestFakeHttp(200, """{"available":true,"package_name":"com.verba.interpretation","version_code":2,"version_name":"1.1","apk_url":"http://downloads.example/app.apk","apk_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}""")
        val api = CloudApi("https://cloud.example", DevicesTestMemoryTokenStore(), DevicesTestFixedInstallationIdStore(), fake.client)

        assertThrows(CloudApiException::class.java) { api.appUpdate() }
    }

    @Test fun devicesRequestsAuthorizedEndpointAndParsesAllFieldsInServerOrder() {
        val fake = DevicesTestFakeHttp(
            200,
            """{"devices":[""" +
                """{"id":"0f0e0d0c-0b0a-4909-8807-060504030201","install_id":"11111111-2222-3333-4444-555555555555","last_seen_at":"2026-09-02T08:30:00Z","created_at":"2026-08-01T00:00:00Z"},""" +
                """{"id":"10203040-5060-4708-9010-a0b0c0d0e0f0","install_id":"99999999-8888-7777-6666-555555555555","last_seen_at":"2026-09-01T00:00:00Z","created_at":"2026-08-02T00:00:00Z"}]}""",
        )
        val api = CloudApi("https://cloud.example", DevicesTestMemoryTokenStore(AuthTokens("access", "refresh")), DevicesTestFixedInstallationIdStore(), fake.client)

        val devices = api.devices()

        val request = fake.singleRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/v1/users/me/devices", request.url.encodedPath)
        assertEquals("Bearer access", request.header("Authorization"))
        assertEquals(2, devices.size)
        // 服务端按 last_seen_at DESC 返回，客户端保持该顺序。
        assertEquals("0f0e0d0c-0b0a-4909-8807-060504030201", devices[0].id)
        assertEquals("11111111-2222-3333-4444-555555555555", devices[0].installId)
        assertEquals("2026-09-02T08:30:00Z", devices[0].lastSeenAt)
        assertEquals("2026-08-01T00:00:00Z", devices[0].createdAt)
        assertEquals("99999999-8888-7777-6666-555555555555", devices[1].installId)
        assertEquals("2026-09-01T00:00:00Z", devices[1].lastSeenAt)
    }

    @Test fun devicesParsesFractionalRfc3339Timestamps() {
        // Go time.Time 序列化可含纳秒精度（RFC3339Nano），必须原样保留字符串。
        val fake = DevicesTestFakeHttp(
            200,
            """{"devices":[{"id":"0f0e0d0c-0b0a-4909-8807-060504030201","install_id":"11111111-2222-3333-4444-555555555555","last_seen_at":"2026-09-02T08:30:00.123456789Z","created_at":"2026-08-01T00:00:00.5Z"}]}""",
        )
        val api = CloudApi("https://cloud.example", DevicesTestMemoryTokenStore(AuthTokens("access", "refresh")), DevicesTestFixedInstallationIdStore(), fake.client)

        val devices = api.devices()

        assertEquals("2026-09-02T08:30:00.123456789Z", devices.single().lastSeenAt)
        assertEquals("2026-08-01T00:00:00.5Z", devices.single().createdAt)
    }

    @Test fun devicesParsesServerConfirmedEmptyList() {
        val fake = DevicesTestFakeHttp(200, """{"devices":[]}""")
        val api = CloudApi("https://cloud.example", DevicesTestMemoryTokenStore(AuthTokens("access", "refresh")), DevicesTestFixedInstallationIdStore(), fake.client)

        assertTrue(api.devices().isEmpty())
    }

    @Test fun devicesRejectsResponseWithoutDevicesArray() {
        val fake = DevicesTestFakeHttp(200, "{}")
        val api = CloudApi("https://cloud.example", DevicesTestMemoryTokenStore(AuthTokens("access", "refresh")), DevicesTestFixedInstallationIdStore(), fake.client)

        val error = assertThrows(CloudApiException::class.java) { api.devices() }

        assertTrue(error.message!!.contains("devices"))
    }

    @Test fun devicesRejectsRecordMissingRequiredField() {
        val fake = DevicesTestFakeHttp(
            200,
            """{"devices":[{"id":"0f0e0d0c-0b0a-4909-8807-060504030201","install_id":"11111111-2222-3333-4444-555555555555","created_at":"2026-08-01T00:00:00Z"}]}""",
        )
        val api = CloudApi("https://cloud.example", DevicesTestMemoryTokenStore(AuthTokens("access", "refresh")), DevicesTestFixedInstallationIdStore(), fake.client)

        val error = assertThrows(CloudApiException::class.java) { api.devices() }

        assertTrue(error.message!!.contains("last_seen_at"))
    }
}

private class DevicesTestFakeHttp(private val status: Int, private val payload: String) {
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

private class DevicesTestMemoryTokenStore(initial: AuthTokens? = null) : TokenStore {
    private var tokens = initial
    override fun read(): AuthTokens? = tokens
    override fun write(tokens: AuthTokens) { this.tokens = tokens }
    override fun clear() { tokens = null }
}

private class DevicesTestFixedInstallationIdStore : InstallationIdStore {
    override fun get(): String = "install-1"
    override fun clear() = Unit
}
