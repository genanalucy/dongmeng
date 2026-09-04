package com.verba.interpretation.protocol

import android.content.Context
import com.verba.interpretation.BuildConfig
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Persisted Agent endpoints. Cleartext is accepted only by debug builds. */
class EndpointSettings(
    context: Context,
    private val defaults: EndpointDefaults = EndpointDefaults(
        httpUrl = BuildConfig.AGENT_HTTP_URL,
        webSocketUrl = BuildConfig.TRANSLATION_WS_URL,
    ),
    private val policy: EndpointSecurityPolicy = EndpointSecurityPolicy(
        allowInsecure = BuildConfig.DEBUG,
        lockedEndpoints = if (BuildConfig.DEBUG) null else EndpointConfig(defaults.httpUrl, defaults.webSocketUrl),
    ),
    private val healthClient: OkHttpClient = OkHttpClient(),
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun current(): EndpointConfig {
        if (policy.isLocked) {
            // Release builds always use the built-in production endpoints; drop any stored overrides.
            clearStoredEndpoints()
            return EndpointConfig(defaults.httpUrl, defaults.webSocketUrl)
        }
        val storedWebSocketUrl = preferences.getString(WEBSOCKET_URL_KEY, defaults.webSocketUrl) ?: defaults.webSocketUrl
        val storedHttpUrl = preferences.getString(HTTP_URL_KEY, defaults.httpUrl) ?: defaults.httpUrl
        val resolved = resolveEndpointConfig(storedHttpUrl, storedWebSocketUrl, EndpointConfig(defaults.httpUrl, defaults.webSocketUrl), locked = false)
        if (resolved.httpUrl != storedHttpUrl) {
            preferences.edit().putString(HTTP_URL_KEY, resolved.httpUrl).apply()
        }
        if (resolved.webSocketUrl != storedWebSocketUrl) {
            preferences.edit().putString(WEBSOCKET_URL_KEY, resolved.webSocketUrl).apply()
        }
        return resolved
    }

    fun save(httpUrl: String, webSocketUrl: String): Result<EndpointConfig> {
        if (policy.isLocked) return Result.failure(IllegalStateException("Release 版本不允许修改服务地址。"))
        val validated = policy.validate(httpUrl, webSocketUrl).getOrElse { return Result.failure(it) }
        preferences.edit()
            .putString(HTTP_URL_KEY, validated.httpUrl)
            .putString(WEBSOCKET_URL_KEY, validated.webSocketUrl)
            .apply()
        return Result.success(validated)
    }

    fun restoreDefaults(): EndpointConfig {
        clearStoredEndpoints()
        return current()
    }

    fun validate(httpUrl: String, webSocketUrl: String): Result<EndpointConfig> = policy.validate(httpUrl, webSocketUrl)

    fun deriveWebSocketUrl(httpUrl: String): Result<String> = runCatching {
        val http = parseHttpUrl(httpUrl)
        require(http.scheme == "http" || http.scheme == "https") { "HTTP 地址仅支持 http 或 https。" }
        require(http.username.isEmpty() && http.password.isEmpty()) { "HTTP 地址不能包含用户信息。" }
        val scheme = if (http.scheme == "https") "wss" else "ws"
        val defaultPort = if (http.scheme == "https") 443 else 80
        val port = if (http.port == defaultPort) "" else ":${http.port}"
        "$scheme://${http.host}$port/ws/translate"
    }

    fun checkHealth(config: EndpointConfig = current()): Result<Unit> {
        val healthUrl = config.healthUrl()
        return try {
            healthClient.newCall(Request.Builder().url(healthUrl).get().build()).execute().use { response ->
                when {
                    !response.isSuccessful -> Result.failure(IllegalStateException("健康检查返回 HTTP ${response.code}。"))
                    !isHealthyResponse(response.body?.string()) -> Result.failure(IllegalStateException("健康检查响应无效。"))
                    else -> Result.success(Unit)
                }
            }
        } catch (error: Exception) {
            Result.failure(IllegalStateException("健康检查失败：${error.message ?: "无法连接服务。"}", error))
        }
    }

    private fun parseHttpUrl(value: String): HttpUrl = value.trim().toHttpUrlOrNull()
        ?: throw IllegalArgumentException("HTTP 地址不是有效 URL。")

    private fun clearStoredEndpoints() {
        if (preferences.contains(HTTP_URL_KEY) || preferences.contains(WEBSOCKET_URL_KEY)) {
            preferences.edit().remove(HTTP_URL_KEY).remove(WEBSOCKET_URL_KEY).apply()
        }
    }

    companion object {
        internal fun isHealthyResponse(body: String?): Boolean = try {
            val json = JSONObject(body.orEmpty())
            json.optString("status") == "ok" && json.optString("service") == "translator-agent"
        } catch (_: Exception) {
            false
        }

        /** Release builds ignore stored overrides entirely; debug keeps persisted user choices. */
        internal fun resolveEndpointConfig(
            storedHttpUrl: String?,
            storedWebSocketUrl: String?,
            defaults: EndpointConfig,
            locked: Boolean,
        ): EndpointConfig = if (locked) {
            defaults
        } else {
            EndpointConfig(
                httpUrl = migrateLegacyHttpUrl(storedHttpUrl ?: defaults.httpUrl, defaults.httpUrl),
                webSocketUrl = migrateLegacyWebSocketUrl(storedWebSocketUrl ?: defaults.webSocketUrl, defaults.webSocketUrl),
            )
        }

        internal fun migrateLegacyHttpUrl(storedUrl: String, defaultUrl: String): String =
            if (storedUrl == LEGACY_AGENT_HTTP_URL) defaultUrl else storedUrl

        internal fun migrateLegacyWebSocketUrl(storedUrl: String, defaultUrl: String): String =
            if (storedUrl in LEGACY_TRANSLATION_WS_URLS) defaultUrl else storedUrl

        private const val LEGACY_AGENT_HTTP_URL = "http://114.132.83.144:18765"
        private val LEGACY_TRANSLATION_WS_URLS = setOf(
            "ws://114.132.83.144:18765/v1/translation",
            "ws://114.132.83.144:18765/ws/translate",
        )
        private const val PREFERENCES_NAME = "endpoint_settings"
        private const val HTTP_URL_KEY = "http_url"
        private const val WEBSOCKET_URL_KEY = "websocket_url"
    }
}

data class EndpointDefaults(val httpUrl: String, val webSocketUrl: String)

data class EndpointConfig(val httpUrl: String, val webSocketUrl: String) {
    fun healthUrl(): HttpUrl = httpUrl.toHttpUrlOrNull()!!.newBuilder()
        .addPathSegments("api/health")
        .build()
}

class EndpointSecurityPolicy(
    private val allowInsecure: Boolean,
    lockedEndpoints: EndpointConfig? = null,
) {
    /** Release builds lock endpoints to the BuildConfig production values. */
    val isLocked: Boolean = lockedEndpoints != null

    private val locked: EndpointConfig? = lockedEndpoints?.let(::canonicalize)

    fun validate(httpUrl: String, webSocketUrl: String): Result<EndpointConfig> = runCatching {
        val httpSchemes = if (allowInsecure) setOf("https", "http") else setOf("https")
        val webSocketSchemes = if (allowInsecure) setOf("wss", "ws") else setOf("wss")
        val http = parse(httpUrl, httpSchemes, "HTTP")
        val webSocket = parse(webSocketUrl, webSocketSchemes, "WebSocket")
        val webSocketScheme = webSocketUrl.trim().substringBefore(":").lowercase()
        val canonicalWebSocket = when (webSocketScheme) {
            "ws" -> webSocket.toString().replaceFirst("http:", "ws:")
            "wss" -> webSocket.toString().replaceFirst("https:", "wss:")
            else -> webSocket.toString()
        }
        val candidate = EndpointConfig(http.toString().removeSuffix("/"), canonicalWebSocket)
        locked?.let { lock ->
            require(candidate == lock) { "Release 版本仅允许内置生产服务地址。" }
        }
        candidate
    }

    private fun canonicalize(config: EndpointConfig): EndpointConfig {
        val http = parse(config.httpUrl, if (allowInsecure) setOf("https", "http") else setOf("https"), "HTTP")
        val webSocket = parse(config.webSocketUrl, if (allowInsecure) setOf("wss", "ws") else setOf("wss"), "WebSocket")
        val webSocketScheme = config.webSocketUrl.trim().substringBefore(":").lowercase()
        val canonicalWebSocket = when (webSocketScheme) {
            "ws" -> webSocket.toString().replaceFirst("http:", "ws:")
            "wss" -> webSocket.toString().replaceFirst("https:", "wss:")
            else -> webSocket.toString()
        }
        return EndpointConfig(http.toString().removeSuffix("/"), canonicalWebSocket)
    }

    private fun parse(value: String, allowedSchemes: Set<String>, label: String): HttpUrl {
        val normalized = value.trim()
        val scheme = normalized.substringBefore(":").lowercase()
        require(scheme in allowedSchemes) {
            "$label 地址仅支持 ${allowedSchemes.joinToString("/")}。"
        }
        val httpCompatible = when (scheme) {
            "ws" -> "http:${normalized.removePrefix("ws:")}"
            "wss" -> "https:${normalized.removePrefix("wss:")}"
            else -> normalized
        }
        val url = httpCompatible.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("$label 地址不是有效 URL。")
        require(url.username.isEmpty() && url.password.isEmpty()) { "$label 地址不能包含用户信息。" }
        return url
    }
}
