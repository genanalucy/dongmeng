package com.verba.interpretation.ui.account

/** Temporary Task4 guard: the email form cannot dispatch phone-authentication requests. */
object LegacyAuthenticationEntryPolicy {
    const val Notice = "请使用手机号注册/登录"

    fun reject(): String = Notice
}
