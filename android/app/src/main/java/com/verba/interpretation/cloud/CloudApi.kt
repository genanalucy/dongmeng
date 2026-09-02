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

data class CloudUser(val id: String, val username: String, val role: CloudRole, val email: String = "")
data class CloudEntitlement(
    val kind: String,
    val expiresAt: String,
    val startsAt: String = "",
    val active: Boolean = true,
    val remainingSeconds: Long = 0,
)
data class UsageSummary(val totalSeconds: Long, val sessionCount: Long, val lastUsedAt: String?)
data class AccountOverview(val username: String, val entitlement: CloudEntitlement?, val usage: UsageSummary)
data class AccountIdentityProfile(val username: String, val email: String, val maskedPhone: String?)
data class CloudUsage(val startedAt: String, val endedAt: String?, val durationSeconds: Long, val sourceLanguage: String?, val targetLanguage: String?)
data class UsagePage(val items: List<CloudUsage>, val total: Int)

data class IdentityUpdateRequest(
    val username: String,
    val email: String,
    val phone: String,
    val currentPassword: String,
) {
    fun toJson(): String = JSONObject()
        .put("username", username)
        .put("email", email)
        .put("phone", phone)
        .put("current_password", currentPassword)
        .toString()
}

@Deprecated("Legacy DTO retained until Task 7 removes its test")
data class RegistrationRequest(val username: String, val email: String, val phone: String, val password: String) {
    fun toJson(): String = JSONObject()
        .put("username", username)
        .put("email", email)
        .put("phone", phone)
        .put("password", password)
        .toString()
}

data class RegistrationVerificationRequest(val username: String, val email: String, val password: String) {
    fun toJson(): String = JSONObject()
        .put("username", username)
        .put("email", email)
        .put("password", password)
        .toString()
}

data class RegistrationVerificationConfirmation(val email: String, val code: String) {
    fun toJson(): String = JSONObject()
        .put("email", email)
        .put("code", code)
        .toString()
}

data class RegistrationResponse(val user: CloudUser, val trialEntitlement: CloudEntitlement, val tokens: AuthTokens)

data class LoginRequest(val identifier: String, val password: String) {
    fun toJson(): String = JSONObject()
        .put("identifier", identifier)
        .put("password", password)
        .toString()
}
data class TranslationSessionGrant(val sessionId: String, val userId: String, val installId: String, val token: String)
data class TranslationSession(val sessionId: String, val installId: String, val expiresAt: String)

class CloudApiException(message: String, val statusCode: Int? = null) : IOException(message)

/** Synchronous transport; invoke from Dispatchers.IO. Token values are deliberately never logged. */
interface CloudTranslationSessionService {
    fun createTranslationSession(): TranslationSessionGrant
    fun createUsageRecord(payload: UsageRecordPayload)
    fun endTranslationSession(sessionId: String)
}

interface AccountApi {
    fun requestRegistrationVerification(username: String, email: String, password: String): Int =
        throw UnsupportedOperationException("邮箱验证码注册未实现")
    fun confirmRegistrationVerification(email: String, code: String): RegistrationResponse =
        throw UnsupportedOperationException("邮箱验证码注册未实现")

    @Deprecated("Task 7 removes the phone registration UI")
    fun register(username: String, email: String, phone: String, password: String) = Unit

    fun login(identifier: String, password: String): AuthTokens
    fun logout()
    fun currentUser(): CloudUser
    fun currentEntitlement(): CloudEntitlement?
    fun redeem(code: String): CloudEntitlement
    fun hasCredentials(): Boolean
    fun accountOverview(): AccountOverview
    fun accountIdentityProfile(): AccountIdentityProfile
    fun usage(limit: Int, offset: Int): UsagePage
    fun updateIdentity(request: IdentityUpdateRequest)
    fun storeTokens(tokens: AuthTokens) = Unit
}

class CloudApi private constructor(
    private val endpointProvider: () -> String,
    private val tokenStore: TokenStore,
    private val installationIdStore: InstallationIdStore,
    private val client: OkHttpClient,
) : CloudTranslationSessionService, AccountApi {
    constructor(
        endpointSettings: CloudEndpointSettings,
        tokenStore: TokenStore,
        installationIdStore: InstallationIdStore,
        client: OkHttpClient = OkHttpClient(),
    ) : this(endpointSettings::current, tokenStore, installationIdStore, client)

    constructor(
        endpoint: String,
        tokenStore: TokenStore,
        installationIdStore: InstallationIdStore,
        client: OkHttpClient = OkHttpClient(),
    ) : this({ endpoint }, tokenStore, installationIdStore, client)

    override fun requestRegistrationVerification(username: String, email: String, password: String): Int = try {
        publicPost(
            "auth/registration-verifications",
            JSONObject(RegistrationVerificationRequest(username, email, password).toJson()),
            expected = 202,
        ).requiredRetryAfterSeconds()
    } catch (error: CloudApiException) {
        if (error.statusCode == 409) throw CloudApiException("该用户名或邮箱暂不可用，请更换后重试。", 409)
        throw error
    }

    override fun confirmRegistrationVerification(email: String, code: String): RegistrationResponse = try {
        val response = publicPost(
            "auth/registration-verifications/confirm",
            JSONObject(RegistrationVerificationConfirmation(email, code).toJson()),
            expected = 201,
        )
        RegistrationResponse(
            user = parsePublicUser(response.requiredObject("user")),
            trialEntitlement = parseEntitlement(response.requiredObject("trial_entitlement")),
            tokens = parseTokens(response),
        )
    } catch (error: CloudApiException) {
        if (error.statusCode == 409) throw CloudApiException("该用户名或邮箱暂不可用，请更换后重试。", 409)
        throw error
    }

    override fun login(identifier: String, password: String): AuthTokens {
        val response = try {
            publicPost("auth/login", JSONObject(LoginRequest(identifier, password).toJson()))
        } catch (error: CloudApiException) {
            if (error.statusCode == 401) throw CloudApiException("账号或密码错误。", 401)
            throw error
        }
        return parseTokens(response).also(tokenStore::write)
    }

    override fun logout() {
        val tokens = tokenStore.read() ?: return
        try {
            authorizedPost("auth/logout", JSONObject().put("refresh_token", tokens.refreshToken))
        } finally {
            tokenStore.clear()
        }
    }

    override fun currentUser(): CloudUser {
        val json = authorized("users/me")
        return CloudUser(
            id = json.requiredString("id"),
            username = json.optString("username").trim().ifEmpty { "旧版用户" },
            role = CloudRole.entries.firstOrNull { it.name.equals(json.requiredString("role"), ignoreCase = true) }
                ?: throw CloudApiException("服务返回了不支持的账户角色。"),
        )
    }

    override fun currentEntitlement(): CloudEntitlement? = try {
        parseEntitlement(authorized("entitlements/current"))
    } catch (error: CloudApiException) {
        if (error.statusCode == 403 || error.statusCode == 404) null else throw error
    }

    override fun accountOverview(): AccountOverview {
        val json = authorized("account/overview")
        return AccountOverview(
            username = json.requiredString("username"),
            entitlement = json.optJSONObject("entitlement")?.let(::parseEntitlement),
            usage = json.optJSONObject("usage")?.let(::parseUsageSummary)
                ?: throw CloudApiException("服务响应缺少 usage。"),
        )
    }

    override fun accountIdentityProfile(): AccountIdentityProfile {
        val json = authorized("account/identity")
        return AccountIdentityProfile(
            username = json.requiredString("username"),
            email = json.requiredString("email"),
            maskedPhone = json.optString("masked_phone").trim().ifEmpty { null },
        )
    }

    override fun usage(limit: Int, offset: Int): UsagePage {
        require(limit in 1..50) { "limit 必须在 1 到 50 之间。" }
        require(offset >= 0) { "offset 不能为负数。" }
        val json = authorized("account/usage", query = mapOf("limit" to limit.toString(), "offset" to offset.toString()))
        val values = json.optJSONArray("usage") ?: json.optJSONArray("items") ?: return UsagePage(emptyList(), json.optInt("total", 0))
        return UsagePage(
            items = List(values.length()) { index -> parseUsage(values.getJSONObject(index)) },
            total = json.optInt("total", values.length()),
        )
    }

    override fun updateIdentity(request: IdentityUpdateRequest) {
        authorizedRequest("account/identity", JSONObject(request.toJson()), "PATCH", 200)
    }

    override fun redeem(code: String): CloudEntitlement {
        val json = authorizedPost("redemptions", JSONObject().put("code", code), expected = 201)
        return parseEntitlement(json)
    }

    override fun createTranslationSession(): TranslationSessionGrant {
        val json = authorizedPost("translation-sessions", JSONObject().put("install_id", installationIdStore.get()), expected = 201)
        return TranslationSessionGrant(json.requiredString("session_id"), json.requiredString("user_id"), json.requiredString("install_id"), json.requiredString("token"))
    }

    fun translationSessions(): List<TranslationSession> {
        val sessions = authorized("translation-sessions").optJSONArray("translation_sessions") ?: return emptyList()
        return List(sessions.length()) { index ->
            val session = sessions.getJSONObject(index)
            TranslationSession(session.requiredString("id"), session.requiredString("install_id"), session.requiredString("expires_at"))
        }
    }

    override fun createUsageRecord(payload: UsageRecordPayload) {
        authorizedPostNoContent("usage-records", JSONObject().put("session_id", payload.sessionId).put("audio_seconds", payload.audioSeconds).put("characters", payload.characters), expected = 201)
    }

    override fun endTranslationSession(sessionId: String) { authorizedPost("translation-sessions/$sessionId/end", JSONObject()) }
    override fun hasCredentials(): Boolean = tokenStore.read() != null
    override fun storeTokens(tokens: AuthTokens) = tokenStore.write(tokens)

    private fun parsePublicUser(json: JSONObject): CloudUser = CloudUser(
        id = json.requiredString("id"),
        username = json.optString("username").trim().ifEmpty { "旧版用户" },
        role = CloudRole.entries.firstOrNull { it.name.equals(json.requiredString("role"), ignoreCase = true) }
            ?: throw CloudApiException("服务返回了不支持的账户角色。"),
    )

    private fun parseEntitlement(json: JSONObject): CloudEntitlement = CloudEntitlement(
        kind = json.requiredString("kind"),
        expiresAt = json.requiredString("expires_at"),
        startsAt = json.optString("starts_at"),
        active = json.optBoolean("active", true),
        remainingSeconds = json.optLong("remaining_seconds", 0).coerceAtLeast(0),
    )

    private fun parseUsageSummary(json: JSONObject): UsageSummary = UsageSummary(
        totalSeconds = json.optLong("total_seconds", 0).coerceAtLeast(0),
        sessionCount = json.optLong("session_count", 0).coerceAtLeast(0),
        lastUsedAt = json.optString("last_used_at").trim().ifEmpty { null },
    )

    private fun parseUsage(json: JSONObject): CloudUsage = CloudUsage(
        startedAt = json.requiredString("started_at"),
        endedAt = json.optString("ended_at").trim().ifEmpty { null },
        durationSeconds = json.optLong("duration_seconds", 0).coerceAtLeast(0),
        sourceLanguage = json.optString("source_language").trim().ifEmpty { null },
        targetLanguage = json.optString("target_language").trim().ifEmpty { null },
    )

    private fun publicPost(path: String, body: JSONObject, expected: Int = 200): JSONObject = execute(path, body, null, expected)
    private fun authorized(path: String, query: Map<String, String> = emptyMap()): JSONObject = authorizedRequest(path, null, "GET", 200, query)
    private fun authorizedPost(path: String, body: JSONObject, expected: Int = 200): JSONObject = authorizedRequest(path, body, "POST", expected)
    private fun authorizedPostNoContent(path: String, body: JSONObject, expected: Int) { authorizedRequestNoContent(path, body, expected) }

    private fun authorizedRequest(path: String, body: JSONObject?, method: String, expected: Int, query: Map<String, String> = emptyMap()): JSONObject {
        val tokens = tokenStore.read() ?: throw CloudApiException("请先登录账户。", 401)
        try {
            return execute(path, body, tokens.accessToken, expected, method, query)
        } catch (error: CloudApiException) {
            if (error.statusCode != 401) throw error
        }
        val refreshed = refresh(tokens.refreshToken)
        tokenStore.write(refreshed)
        return execute(path, body, refreshed.accessToken, expected, method, query)
    }

    private fun authorizedRequestNoContent(path: String, body: JSONObject, expected: Int) {
        val tokens = tokenStore.read() ?: throw CloudApiException("请先登录账户。", 401)
        try { executeNoContent(path, body, tokens.accessToken, expected); return } catch (error: CloudApiException) { if (error.statusCode != 401) throw error }
        val refreshed = refresh(tokens.refreshToken)
        tokenStore.write(refreshed)
        executeNoContent(path, body, refreshed.accessToken, expected)
    }

    private fun refresh(refreshToken: String): AuthTokens = parseTokens(publicPost("auth/refresh", JSONObject().put("refresh_token", refreshToken)))

    private fun execute(path: String, body: JSONObject?, accessToken: String?, expected: Int, method: String = "POST", query: Map<String, String> = emptyMap()): JSONObject {
        val url = endpointProvider().toHttpUrl().newBuilder().addPathSegments("api/v1/$path").apply { query.forEach(::addQueryParameter) }.build()
        val request = Request.Builder().url(url).apply {
            if (accessToken != null) header("Authorization", "Bearer $accessToken")
            if (method == "GET") get() else method(method, (body ?: JSONObject()).toString().toRequestBody(JSON))
        }.build()
        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (response.code != expected) throw CloudApiException(errorMessage(payload, response.code), response.code)
            return try { JSONObject(payload) } catch (_: Exception) { throw CloudApiException("服务返回了无效响应。", response.code) }
        }
    }

    private fun executeNoContent(path: String, body: JSONObject, accessToken: String, expected: Int) {
        val url = endpointProvider().toHttpUrl().newBuilder().addPathSegments("api/v1/$path").build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken").post(body.toString().toRequestBody(JSON)).build()
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
            "invalid_credentials" -> "账号或密码错误。"
            "invalid_request" -> "提交的信息无效，请检查后重试。"
            "conflict" -> "当前已有进行中的翻译会话，请先结束它。"
            else -> "服务请求失败（HTTP $status）。"
        }
    } catch (_: Exception) { "网络或服务不可用（HTTP $status）。" }
}

private fun JSONObject.requiredString(name: String): String = optString(name).trim().takeIf(String::isNotEmpty)
    ?: throw CloudApiException("服务响应缺少 $name。")

private fun JSONObject.requiredObject(name: String): JSONObject = optJSONObject(name)
    ?: throw CloudApiException("服务响应缺少 $name。")

private fun JSONObject.requiredRetryAfterSeconds(): Int = optInt("retry_after_seconds", -1).takeIf { it >= 0 }
    ?: throw CloudApiException("服务响应缺少 retry_after_seconds。")
