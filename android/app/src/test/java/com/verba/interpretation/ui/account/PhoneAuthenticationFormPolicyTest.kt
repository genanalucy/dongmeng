package com.verba.interpretation.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAuthenticationFormPolicyTest {
    @Test fun registrationNormalizesMainlandPhoneToCanonicalChinaFormat() {
        val result = PhoneAuthenticationFormPolicy.register(
            username = "  Alice_01 ",
            phone = " 13800138000 ",
            password = "Passw0rd",
            confirmation = "Passw0rd",
        )

        assertTrue(result.isValid)
        assertEquals("alice_01", result.normalizedUsername)
        assertEquals("+8613800138000", result.normalizedPhone)
        assertNull(result.usernameError)
        assertNull(result.phoneError)
    }

    @Test fun registrationRejectsNonMainlandPhoneWithoutEchoingIt() {
        val submittedPhone = "12800138000"
        val result = PhoneAuthenticationFormPolicy.register("alice_01", submittedPhone, "Passw0rd", "Passw0rd")

        assertEquals("请输入有效的中国大陆手机号。", result.phoneError)
        assertFalse(result.isValid)
        assertFalse(result.renderedErrors.any { it.contains(submittedPhone) })
    }

    @Test fun registrationRejectsInvalidUsername() {
        val result = PhoneAuthenticationFormPolicy.register("张三", "13800138000", "Passw0rd", "Passw0rd")

        assertEquals("用户名需要 3 至 32 个字符，仅支持字母、数字和下划线。", result.usernameError)
        assertFalse(result.isValid)
    }

    @Test fun registrationRejectsWeakPasswordWithoutEchoingIt() {
        val submittedPassword = "password1"
        val result = PhoneAuthenticationFormPolicy.register("alice_01", "13800138000", submittedPassword, submittedPassword)

        assertEquals("密码需包含大写英文字母。", result.passwordError)
        assertFalse(result.isValid)
        assertFalse(result.renderedErrors.any { it.contains(submittedPassword) })
    }

    @Test fun registrationRejectsMismatchedConfirmation() {
        val result = PhoneAuthenticationFormPolicy.register("alice_01", "13800138000", "Passw0rd", "Passw0rD")

        assertEquals("两次输入的密码不一致。", result.confirmationError)
        assertFalse(result.isValid)
    }

    @Test fun loginNormalizesAlreadyCanonicalPhone() {
        val result = PhoneAuthenticationFormPolicy.login("+8613800138000", "Passw0rd")

        assertTrue(result.isValid)
        assertEquals("+8613800138000", result.normalizedPhone)
        assertNull(result.phoneError)
        assertNull(result.passwordError)
    }

    @Test fun loginRequiresPhoneAndPassword() {
        val result = PhoneAuthenticationFormPolicy.login("", "")

        assertEquals("请输入有效的中国大陆手机号。", result.phoneError)
        assertEquals("请输入密码。", result.passwordError)
        assertFalse(result.isValid)
    }
}
