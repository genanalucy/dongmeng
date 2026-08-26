package com.verba.interpretation.cloud

import android.content.Context
import com.verba.interpretation.BuildConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Cloud REST base URL. HTTP is a Debug-only testing option. */
class CloudEndpointSettings(context: Context, private val policy: CloudEndpointSecurityPolicy = CloudEndpointSecurityPolicy(BuildConfig.DEBUG)) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun current(): String = preferences.getString(CLOUD_API_URL, BuildConfig.CLOUD_API_URL) ?: BuildConfig.CLOUD_API_URL

    fun save(url: String): Result<String> = policy.validate(url).onSuccess { validated ->
        preferences.edit().putString(CLOUD_API_URL, validated).apply()
    }

    fun restoreDefaults(): String {
        preferences.edit().remove(CLOUD_API_URL).apply()
        return current()
    }

    private companion object {
        const val PREFERENCES = "cloud_endpoint_settings"
        const val CLOUD_API_URL = "cloud_api_url"
    }
}

class CloudEndpointSecurityPolicy(private val allowInsecure: Boolean) {
    fun validate(value: String): Result<String> = runCatching {
        val url = value.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Cloud API 地址不是有效 URL。")
        val allowed = if (allowInsecure) setOf("http", "https") else setOf("https")
        require(url.scheme in allowed) { "Cloud API 地址仅支持 ${allowed.joinToString("/")}。" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "Cloud API 地址不能包含用户信息。" }
        url.toString().removeSuffix("/")
    }
}
