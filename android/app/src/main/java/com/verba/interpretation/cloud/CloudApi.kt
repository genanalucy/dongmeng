package com.verba.interpretation.cloud

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

private val JSON = "application/json; charset=utf-8".toMediaType()

enum class CloudRole { USER, ADMIN }

data class CloudUser(val id: String, val username: String, val role: CloudRole)

data class PhoneRegistrationRequest(val username: String, val phone: String, val password: String) {
    fun toJson(): String = JSONObject()
        .put("username", username)
        .put("phone", phone)
        .put("password", password)
        .toString()
}

data class PhoneLoginRequest(val phone: String, val password: String) {
    fun toJson(): String = JSONObject()
        .put("phone", phone)
        .put("password", password)
        .toString()
}
data class CloudEntitlement(val kind: String, val expiresAt: String)
data class TranslationSessionGrant(val sessionId: String, val userId: String, val installId: String, val token: String)
data class TranslationSession(val sessionId: String, val installId: String, val expiresAt: String)

class CloudApiException(message: String, val statusCode: Int? = null) : IOException(message)

/** Synchronous transport; invoke from Dispatchers.IO. Token values are deliberately never logged. */
interface CloudTranslationSessionService {
    fun createTranslationSession(): TranslationSessionGrant
    fun createUsageRecord(payload: UsageRecordPayload)
    fun endTranslationSession(sessionId: String)
}

class CloudApi(
    private val endpointSettings: CloudEndpointSettings,
    private val tokenStore: TokenStore,
    private val installationIdStore: InstallationIdStore,
    private val client: OkHttpClient = OkHttpClient(),
) : CloudTranslationSessionService {
    fun register(username: String, phone: String, password: String) {
        publicPost("auth/register", JSONObject(PhoneRegistrationRequest(username, phone, password).toJson()), expected = 201)
    }

    fun login(phone: String, password: String): AuthTokens {
        val response = publicPost("auth/login", JSONObject(PhoneLoginRequest(phone, password).toJson()))
        return parseTokens(response).also(tokenStore::write)
    }

    fun logout() {
        val tokens = tokenStore.read() ?: return
        try {
            authorizedPost("auth/logout", JSONObject().put("refresh_token", tokens.refreshToken))
        } finally {
            tokenStore.clear()
        }
    }

    fun currentUser(): CloudUser {
        val json = authorized("users/me")
        return CloudUser(
            id = json.requiredString("id"),
            username = json.requiredString("username"),
            role = CloudRole.entries.firstOrNull { it.name.equals(json.requiredString("role"), ignoreCase = true) }
                ?: throw CloudApiException("服务返回了不支持的账户角色。"),
        )
    }

    fun currentEntitlement(): CloudEntitlement? = try {
        val json = authorized("entitlements/current")
        CloudEntitlement(kind = json.requiredString("kind"), expiresAt = json.requiredString("expires_at"))
    } catch (error: CloudApiException) {
        if (error.statusCode == 403 || error.statusCode == 404) null else throw error
    }

    fun redeem(code: String): CloudEntitlement {
        val json = authorizedPost("redemptions", JSONObject().put("code", code), expected = 201)
        return CloudEntitlement(kind = json.requiredString("kind"), expiresAt = json.requiredString("expires_at"))
    }

    override fun createTranslationSession(): TranslationSessionGrant {
        val json = authorizedPost("translation-sessions", JSONObject().put("install_id", installationIdStore.get()), expected = 201)
        return TranslationSessionGrant(
            sessionId = json.requiredString("session_id"),
            userId = json.requiredString("user_id"),
            installId = json.requiredString("install_id"),
            token = json.requiredString("token"),
        )
    }

    fun translationSessions(): List<TranslationSession> {
        val sessions = authorized("translation-sessions").optJSONArray("translation_sessions") ?: return emptyList()
        return List(sessions.length()) { index ->
            val session = sessions.getJSONObject(index)
            TranslationSession(
                sessionId = session.requiredString("id"),
                installId = session.requiredString("install_id"),
                expiresAt = session.requiredString("expires_at"),
            )
        }
    }

    /** The server contract accepts only session_id, audio_seconds and characters. */
    override fun createUsageRecord(payload: UsageRecordPayload) {
        authorizedPostNoContent(
            "usage-records",
            JSONObject()
                .put("session_id", payload.sessionId)
                .put("audio_seconds", payload.audioSeconds)
                .put("characters", payload.characters),
            expected = 201,
        )
    }

    override fun endTranslationSession(sessionId: String) {
        authorizedPost("translation-sessions/$sessionId/end", JSONObject())
    }

    fun hasCredentials(): Boolean = tokenStore.read() != null

    private fun publicPost(path: String, body: JSONObject, expected: Int = 200): JSONObject = execute(path, body, null, expected)

    private fun authorized(path: String): JSONObject = authorizedRequest(path, null, "GET", 200)

    private fun authorizedPost(path: String, body: JSONObject, expected: Int = 200): JSONObject = authorizedRequest(path, body, "POST", expected)

    private fun authorizedPostNoContent(path: String, body: JSONObject, expected: Int) {
        authorizedRequestNoContent(path, body, expected)
    }

    private fun authorizedRequest(path: String, body: JSONObject?, method: String, expected: Int): JSONObject {
        val tokens = tokenStore.read() ?: throw CloudApiException("请先登录账户。", 401)
        try {
            return execute(path, body, tokens.accessToken, expected, method)
        } catch (error: CloudApiException) {
            if (error.statusCode != 401) throw error
        }
        val refreshed = refresh(tokens.refreshToken)
        tokenStore.write(refreshed)
        return execute(path, body, refreshed.accessToken, expected, method)
    }

    private fun authorizedRequestNoContent(path: String, body: JSONObject, expected: Int) {
        val tokens = tokenStore.read() ?: throw CloudApiException("请先登录账户。", 401)
        try {
            executeNoContent(path, body, tokens.accessToken, expected)
            return
        } catch (error: CloudApiException) {
            if (error.statusCode != 401) throw error
        }
        val refreshed = refresh(tokens.refreshToken)
        tokenStore.write(refreshed)
        executeNoContent(path, body, refreshed.accessToken, expected)
    }

    private fun refresh(refreshToken: String): AuthTokens = parseTokens(
        publicPost("auth/refresh", JSONObject().put("refresh_token", refreshToken)),
    )

    private fun execute(path: String, body: JSONObject?, accessToken: String?, expected: Int, method: String = "POST"): JSONObject {
        val url = endpointSettings.current().toHttpUrl().newBuilder().addPathSegments("api/v1/$path").build()
        val request = Request.Builder().url(url).apply {
            if (accessToken != null) header("Authorization", "Bearer $accessToken")
            when (method) {
                "GET" -> get()
                else -> method(method, (body ?: JSONObject()).toString().toRequestBody(JSON))
            }
        }.build()
        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (response.code != expected) throw CloudApiException(errorMessage(payload, response.code), response.code)
            return try { JSONObject(payload) } catch (_: Exception) { throw CloudApiException("服务返回了无效响应。", response.code) }
        }
    }

    private fun executeNoContent(path: String, body: JSONObject, accessToken: String, expected: Int) {
        val url = endpointSettings.current().toHttpUrl().newBuilder().addPathSegments("api/v1/$path").build()
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $accessToken")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (response.code != expected) throw CloudApiException(errorMessage(payload, response.code), response.code)
        }
    }

    private fun parseTokens(json: JSONObject): AuthTokens = AuthTokens(json.requiredString("access_token"), json.requiredString("refresh_token"))

    private fun errorMessage(payload: String, status: Int): String = try {
        when (JSONObject(payload).optString("error")) {
            "no_entitlement" -> "当前账户没有可用权益，请兑换或等待试用生效。"
            "unauthorized" -> "登录状态已过期，请重新登录。"
            "invalid_credentials" -> "手机号或密码错误。"
            "conflict" -> "当前已有进行中的翻译会话，请先结束它。"
            else -> "服务请求失败（HTTP $status）。"
        }
    } catch (_: Exception) {
        "网络或服务不可用（HTTP $status）。"
    }
}

private fun JSONObject.requiredString(name: String): String = optString(name).trim().takeIf(String::isNotEmpty)
    ?: throw CloudApiException("服务响应缺少 $name。")
