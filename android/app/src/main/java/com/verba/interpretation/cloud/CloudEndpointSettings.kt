package com.verba.interpretation.cloud

import android.content.Context
import com.verba.interpretation.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Cloud REST base URL. HTTP is a Debug-only testing option. */
class CloudEndpointSettings(
    context: Context,
    private val policy: CloudEndpointSecurityPolicy = CloudEndpointSecurityPolicy(
        allowInsecure = BuildConfig.DEBUG,
        lockedUrl = if (BuildConfig.DEBUG) null else BuildConfig.CLOUD_API_URL,
    ),
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun current(): String {
        if (policy.isLocked) {
            // Release builds always use the built-in production Cloud API; drop any stored overrides.
            clearStoredUrl()
            return BuildConfig.CLOUD_API_URL
        }
        val stored = preferences.getString(CLOUD_API_URL, null)
        // Migrate only historical built-in defaults. Other saved URLs remain explicit user choices.
        if (stored in obsoleteCloudApiDefaults()) {
            preferences.edit().remove(CLOUD_API_URL).apply()
            return BuildConfig.CLOUD_API_URL
        }
        return stored ?: BuildConfig.CLOUD_API_URL
    }

    fun save(url: String): Result<String> {
        if (policy.isLocked) return Result.failure(IllegalStateException("Release 版本不允许修改 Cloud API 地址。"))
        return policy.validate(url).onSuccess { validated ->
            preferences.edit().putString(CLOUD_API_URL, validated).apply()
        }
    }

    fun restoreDefaults(): String {
        clearStoredUrl()
        return current()
    }

    private fun clearStoredUrl() {
        if (preferences.contains(CLOUD_API_URL)) {
            preferences.edit().remove(CLOUD_API_URL).apply()
        }
    }

    private companion object {
        const val PREFERENCES = "cloud_endpoint_settings"
        const val CLOUD_API_URL = "cloud_api_url"
    }
}

internal fun obsoleteCloudApiDefaults(): Set<String> = buildSet {
    if (BuildConfig.DEBUG) add("http://127.0.0.1:8080")
    add("http://114.132.83.144:8080")
}

/** Release builds ignore stored overrides entirely; debug keeps persisted user choices. */
internal fun resolveCloudApiUrl(stored: String?, defaultUrl: String, locked: Boolean): String =
    if (locked) defaultUrl else stored ?: defaultUrl

class CloudEndpointSecurityPolicy(
    private val allowInsecure: Boolean,
    lockedUrl: String? = null,
) {
    /** Release builds lock the Cloud API to the BuildConfig production URL. */
    val isLocked: Boolean = lockedUrl != null

    private val locked: String? = lockedUrl?.let(::canonicalize)

    fun validate(value: String): Result<String> = runCatching {
        val url = value.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Cloud API 地址不是有效 URL。")
        val allowed = if (allowInsecure) setOf("http", "https") else setOf("https")
        require(url.scheme in allowed) { "Cloud API 地址仅支持 ${allowed.joinToString("/")}。" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "Cloud API 地址不能包含用户信息。" }
        val canonical = url.toString().removeSuffix("/")
        locked?.let { require(canonical == it) { "Release 版本仅允许内置生产 Cloud API 地址。" } }
        canonical
    }

    private fun canonicalize(value: String): String {
        val url = value.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Cloud API 地址不是有效 URL。")
        return url.toString().removeSuffix("/")
    }
}
