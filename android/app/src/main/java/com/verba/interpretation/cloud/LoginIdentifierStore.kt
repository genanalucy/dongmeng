package com.verba.interpretation.cloud

import android.content.Context

/**
 * 最近登录标识（用户名/邮箱/手机号）的本机存储。
 *
 * 与 [TokenStore] 不同，这里只保存非敏感的登录标识，不保存密码或令牌；
 * 退出登录后它仍然保留，用于登录表单预填。
 */
interface LoginIdentifierStore {
    fun read(): String?
    fun write(identifier: String)
    fun clear()
}

class SharedPreferencesLoginIdentifierStore(context: Context) : LoginIdentifierStore {
    private val preferences = runCatching {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }.getOrNull()

    override fun read(): String? = preferences?.getString(IDENTIFIER, null)?.takeIf(String::isNotBlank)

    override fun write(identifier: String) {
        val trimmed = identifier.trim()
        if (trimmed.isEmpty()) return
        preferences?.edit()?.putString(IDENTIFIER, trimmed)?.apply()
    }

    override fun clear() {
        preferences?.edit()?.remove(IDENTIFIER)?.apply()
    }

    private companion object {
        const val PREFERENCES = "login_identity"
        const val IDENTIFIER = "latest_login_identifier"
    }
}
