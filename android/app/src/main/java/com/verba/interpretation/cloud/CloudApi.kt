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

data class HistoryPushOperation(val operationId: String, val kind: String, val sessionId: String, val turnId: String? = null, val payloadBase64: String? = null)
data class HistoryPushResponse(val cursors: List<Long>)
data class CloudHistorySession(val id: String, val createdAtMillis: Long, val deletedAtMillis: Long?, val title: String?, val titleUpdatedAtMillis: Long?)
data class CloudHistoryTurn(val id: String, val sessionId: String, val createdAtMillis: Long, val deletedAtMillis: Long?, val payloadBase64: String?)
data class CloudHistoryChange(val cursor: Long, val session: CloudHistorySession, val turn: CloudHistoryTurn?)
data class HistoryPullResponse(val changes: List<CloudHistoryChange>, val nextCursor: Long, val hasMore: Boolean)

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

data class RegistrationResponse(val user: CloudUser, val trialEntitlement: CloudEntitlement, val tokens: AuthTokens)

/** One rendered image of a slide captcha challenge (base64 bytes plus pixel metadata). */
data class SlideCaptchaImage(
    val imageBase64: String,
    val imageType: String,
    val width: Int,
    val height: Int,
)

/** The draggable puzzle piece with its origin inside the challenge image. */
data class SlideCaptchaTile(
    val image: SlideCaptchaImage,
    val startX: Int,
    val startY: Int,
)

/**
 * GET /api/v1/auth/captcha contract from Cloud a16cb0b. Only the challenge
 * geometry and rendered assets travel here; the hidden target x never does.
 */
data class SlideCaptchaChallenge(
    val captchaId: String,
    val expiresInSeconds: Int,
    val tolerancePx: Int,
    val challenge: SlideCaptchaImage,
    val tile: SlideCaptchaTile,
)

data class LoginRequest(val identifier: String, val password: String) {
    fun toJson(): String = JSONObject()
        .put("identifier", identifier)
        .put("password", password)
        .toString()
}
data class TranslationSessionGrant(val sessionId: String, val userId: String, val installId: String, val token: String)
data class TranslationSession(val sessionId: String, val installId: String, val expiresAt: String)

class CloudApiException(message: String, val statusCode: Int? = null, val sessionExpired: Boolean = false) : IOException(message)

/** Synchronous transport; invoke from Dispatchers.IO. Token values are deliberately never logged. */
interface CloudTranslationSessionService {
    fun createTranslationSession(): TranslationSessionGrant
    fun createUsageRecord(payload: UsageRecordPayload)
    fun endTranslationSession(sessionId: String)
}

interface HistoryApi {
    fun pushHistory(operations: List<HistoryPushOperation>): HistoryPushResponse
    fun pullHistory(cursor: Long): HistoryPullResponse
    fun deleteHistorySession(sessionId: String, operationId: String): Long
    fun patchHistoryTitle(sessionId: String, operationId: String, title: String): Long
}

interface AccountApi {
    fun fetchRegistrationCaptcha(): SlideCaptchaChallenge
    fun register(username: String, email: String, password: String, captchaId: String, captchaX: Int): RegistrationResponse
    fun deleteAccount(username: String)

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
) : CloudTranslationSessionService, AccountApi, HistoryApi {
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

    /** 拼图验证码：严格解析，任何字段缺失或几何越界都视为服务响应无效。 */
    override fun fetchRegistrationCaptcha(): SlideCaptchaChallenge {
        val json = publicGet("auth/captcha")
        val challengeImage = parseCaptchaImage(json.requiredObject("challenge"), expectWidthRange = 1..4096)
        val tileJson = json.requiredObject("tile")
        val tileImage = parseCaptchaImage(tileJson, expectWidthRange = 1..challengeImage.width)
        val startX = tileJson.requiredInt("start_x")
        val startY = tileJson.requiredInt("start_y")
        val expiresInSeconds = json.requiredInt("expires_in")
        val tolerancePx = json.requiredInt("tolerance_px")
        val captchaId = json.requiredString("captcha_id")
        if (expiresInSeconds <= 0 || tolerancePx < 0) throw CloudApiException("服务返回了无效的验证码有效期。")
        if (tileImage.height !in 1..challengeImage.height) throw CloudApiException("服务返回了无效的拼图尺寸。")
        if (startX !in 0..(challengeImage.width - tileImage.width) || startY !in 0..(challengeImage.height - tileImage.height)) {
            throw CloudApiException("服务返回了无效的拼图位置。")
        }
        return SlideCaptchaChallenge(
            captchaId = captchaId,
            expiresInSeconds = expiresInSeconds,
            tolerancePx = tolerancePx,
            challenge = challengeImage,
            tile = SlideCaptchaTile(image = tileImage, startX = startX, startY = startY),
        )
    }

    override fun register(username: String, email: String, password: String, captchaId: String, captchaX: Int): RegistrationResponse = try {
        val response = publicPost(
            "auth/register",
            JSONObject()
                .put("username", username)
                .put("email", email)
                .put("password", password)
                .put("captcha_id", captchaId)
                .put("captcha_x", captchaX),
            expected = 201,
        )
        RegistrationResponse(
            user = parsePublicUser(response.requiredObject("user")),
            trialEntitlement = parseEntitlement(response.requiredObject("trial_entitlement")),
            tokens = parseTokens(response),
        )
    } catch (error: CloudApiException) {
        when (error.statusCode) {
            // captcha_failed 意味着验证码已被服务端消费，调用方必须获取新拼图。
            400 -> throw CloudApiException("拼图位置未通过校验，请完成新的拼图。", 400)
            409 -> throw CloudApiException("该用户名或邮箱暂不可用，请更换后重试。", 409)
            else -> throw error
        }
    }

    /** DELETE /api/v1/account：204 成功后本机令牌立即失效清除。 */
    override fun deleteAccount(username: String) {
        try {
            authorizedRequestNoContent("account", JSONObject().put("username", username), expected = 204, method = "DELETE")
        } catch (error: CloudApiException) {
            when (error.statusCode) {
                400 -> throw CloudApiException("提交的用户名无效，请检查后重试。", 400)
                403 -> throw CloudApiException("管理员账户不支持自助删除。", 403)
                409 -> throw CloudApiException("输入的用户名与当前账户不一致，请核对后重试。", 409)
                else -> throw error
            }
        }
        tokenStore.clear()
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

    override fun pushHistory(operations: List<HistoryPushOperation>): HistoryPushResponse {
        require(operations.size in 1..100)
        val values = org.json.JSONArray()
        operations.forEach { value ->
            values.put(JSONObject().put("operation_id", value.operationId).put("kind", value.kind).put("session_id", value.sessionId).apply {
                value.turnId?.let { put("turn_id", it) }
                value.payloadBase64?.let { put("payload", it) }
            })
        }
        val json = authorizedPost("history/sync/push", JSONObject().put("operations", values))
        val cursors = json.optJSONArray("cursors") ?: throw CloudApiException("服务响应缺少 cursors。")
        return HistoryPushResponse(List(cursors.length()) { cursors.getLong(it) })
    }

    override fun pullHistory(cursor: Long): HistoryPullResponse {
        require(cursor >= 0)
        val json = authorized("history/sync/pull", mapOf("cursor" to cursor.toString()))
        val changes = json.optJSONArray("changes") ?: throw CloudApiException("服务响应缺少 changes。")
        return HistoryPullResponse(
            changes = List(changes.length()) { index -> parseHistoryChange(changes.getJSONObject(index)) },
            nextCursor = json.getLong("next_cursor"),
            hasMore = json.getBoolean("has_more"),
        )
    }

    override fun deleteHistorySession(sessionId: String, operationId: String): Long {
        val json = authorizedRequest("history/sessions/$sessionId", null, "DELETE", 200, emptyMap(), mapOf("Idempotency-Key" to operationId))
        return json.getLong("cursor")
    }

    override fun patchHistoryTitle(sessionId: String, operationId: String, title: String): Long {
        val json = authorizedRequest("history/sessions/$sessionId/title", JSONObject().put("operation_id", operationId).put("title", title), "PATCH", 200)
        return json.getLong("cursor")
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

    private fun parseHistoryChange(json: JSONObject): CloudHistoryChange {
        val session = json.getJSONObject("session")
        fun millis(value: String): Long = java.time.Instant.parse(value).toEpochMilli()
        val remoteSession = CloudHistorySession(
            id = session.requiredString("id"),
            createdAtMillis = millis(session.requiredString("created_at")),
            deletedAtMillis = session.optString("deleted_at").takeIf { it.isNotBlank() }?.let(::millis),
            title = session.optString("title").takeIf { it.isNotBlank() },
            titleUpdatedAtMillis = session.optString("title_updated_at").takeIf { it.isNotBlank() }?.let(::millis),
        )
        val turn = json.optJSONObject("turn")?.let { raw -> CloudHistoryTurn(
            id = raw.requiredString("id"), sessionId = raw.requiredString("session_id"),
            createdAtMillis = millis(raw.requiredString("created_at")),
            deletedAtMillis = raw.optString("deleted_at").takeIf { it.isNotBlank() }?.let(::millis),
            payloadBase64 = raw.optString("payload").takeIf { it.isNotBlank() },
        ) }
        return CloudHistoryChange(json.getLong("cursor"), remoteSession, turn)
    }

    private fun parseUsage(json: JSONObject): CloudUsage = CloudUsage(
        startedAt = json.requiredString("started_at"),
        endedAt = json.optString("ended_at").trim().ifEmpty { null },
        durationSeconds = json.optLong("duration_seconds", 0).coerceAtLeast(0),
        sourceLanguage = json.optString("source_language").trim().ifEmpty { null },
        targetLanguage = json.optString("target_language").trim().ifEmpty { null },
    )

    private fun publicPost(path: String, body: JSONObject, expected: Int = 200): JSONObject = execute(path, body, null, expected)
    private fun publicGet(path: String): JSONObject = execute(path, null, null, 200, "GET")
    private fun authorized(path: String, query: Map<String, String> = emptyMap()): JSONObject = authorizedRequest(path, null, "GET", 200, query)
    private fun authorizedPost(path: String, body: JSONObject, expected: Int = 200): JSONObject = authorizedRequest(path, body, "POST", expected)
    private fun authorizedPostNoContent(path: String, body: JSONObject, expected: Int) { authorizedRequestNoContent(path, body, expected) }

    private fun authorizedRequest(path: String, body: JSONObject?, method: String, expected: Int, query: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()): JSONObject {
        val tokens = tokenStore.read() ?: throw CloudApiException("请先登录账户。", 401)
        try {
            return execute(path, body, tokens.accessToken, expected, method, query, headers)
        } catch (error: CloudApiException) {
            if (error.statusCode != 401) throw error
        }
        val refreshed = refreshAfterExpiry(tokens)
        tokenStore.write(refreshed)
        return execute(path, body, refreshed.accessToken, expected, method, query, headers)
    }

    private fun authorizedRequestNoContent(path: String, body: JSONObject, expected: Int, method: String = "POST") {
        val tokens = tokenStore.read() ?: throw CloudApiException("请先登录账户。", 401)
        try { executeNoContent(path, body, tokens.accessToken, expected, method); return } catch (error: CloudApiException) { if (error.statusCode != 401) throw error }
        val refreshed = refreshAfterExpiry(tokens)
        tokenStore.write(refreshed)
        executeNoContent(path, body, refreshed.accessToken, expected, method)
    }

    /** Refresh token 失效时立即清除本机令牌，并标记会话过期，调用方据此回到安全的重新登录状态。 */
    private fun refreshAfterExpiry(tokens: AuthTokens): AuthTokens = try {
        refresh(tokens.refreshToken)
    } catch (error: CloudApiException) {
        if (error.statusCode == 401) {
            tokenStore.clear()
            throw CloudApiException(error.message ?: "登录状态已过期，请重新登录。", 401, sessionExpired = true)
        }
        throw error
    }

    private fun refresh(refreshToken: String): AuthTokens = parseTokens(publicPost("auth/refresh", JSONObject().put("refresh_token", refreshToken)))

    private fun execute(path: String, body: JSONObject?, accessToken: String?, expected: Int, method: String = "POST", query: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()): JSONObject {
        val url = endpointProvider().toHttpUrl().newBuilder().addPathSegments("api/v1/$path").apply { query.forEach(::addQueryParameter) }.build()
        val request = Request.Builder().url(url).apply {
            if (accessToken != null) header("Authorization", "Bearer $accessToken")
            headers.forEach(::header)
            if (method == "GET") get() else method(method, (body ?: JSONObject()).toString().toRequestBody(JSON))
        }.build()
        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (response.code != expected) throw CloudApiException(errorMessage(payload, response.code), response.code)
            return try { JSONObject(payload) } catch (_: Exception) { throw CloudApiException("服务返回了无效响应。", response.code) }
        }
    }

    private fun executeNoContent(path: String, body: JSONObject, accessToken: String, expected: Int, method: String = "POST") {
        val url = endpointProvider().toHttpUrl().newBuilder().addPathSegments("api/v1/$path").build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $accessToken")
            .method(method, body.toString().toRequestBody(JSON)).build()
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
            "rate_limited" -> "操作过于频繁，请稍后再试。"
            "conflict" -> "当前已有进行中的翻译会话，请先结束它。"
            else -> "服务请求失败（HTTP $status）。"
        }
    } catch (_: Exception) { "网络或服务不可用（HTTP $status）。" }
}

private fun JSONObject.requiredString(name: String): String = optString(name).trim().takeIf(String::isNotEmpty)
    ?: throw CloudApiException("服务响应缺少 $name。")

private fun JSONObject.requiredObject(name: String): JSONObject = optJSONObject(name)
    ?: throw CloudApiException("服务响应缺少 $name。")

private fun JSONObject.requiredInt(name: String): Int = if (has(name) && get(name) is Int) getInt(name) else throw CloudApiException("服务响应缺少 $name。")

private fun parseCaptchaImage(json: JSONObject, expectWidthRange: IntRange): SlideCaptchaImage {
    val image = SlideCaptchaImage(
        imageBase64 = json.requiredString("image_base64"),
        imageType = json.requiredString("image_type"),
        width = json.requiredInt("width"),
        height = json.requiredInt("height"),
    )
    if (image.width !in expectWidthRange || image.height !in 1..4096) throw CloudApiException("服务返回了无效的验证码尺寸。")
    return image
}
