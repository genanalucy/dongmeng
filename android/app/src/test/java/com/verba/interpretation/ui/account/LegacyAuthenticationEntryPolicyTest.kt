package com.verba.interpretation.ui.account

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyAuthenticationEntryPolicyTest {
    @Test fun oldAuthenticationEntryReturnsFixedNoticeWithoutNetworkCallback() {
        val notice = LegacyAuthenticationEntryPolicy.reject()

        assertEquals("请使用手机号注册/登录", notice)
    }
}
