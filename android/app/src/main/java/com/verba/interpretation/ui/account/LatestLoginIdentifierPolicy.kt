package com.verba.interpretation.ui.account

/**
 * 最近登录标识的持久化规则：只保存可用于再次登录的标识（用户名/邮箱/手机号），
 * 绝不保存密码；空值不会覆盖已保存的标识。
 */
object LatestLoginIdentifierPolicy {
    private const val MaxIdentifierLength = 254
    private const val LegacyFallbackUsername = "旧版用户"

    fun loginIdentifier(identifier: String): String? = identifier
        .trim()
        .takeIf { it.isNotEmpty() && it.length <= MaxIdentifierLength }

    fun registrationIdentifier(username: String): String? = username
        .trim()
        .takeIf { it.isNotEmpty() && it != LegacyFallbackUsername }
}
