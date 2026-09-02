package com.verba.interpretation.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticationFormPolicyTest {
    @Test fun registrationNormalizesUsernameAndEmailWithoutPhone() {
        val result = AuthenticationFormPolicy.register(
            username = "  Alice_01 ",
            email = " Alice@Example.COM ",
            password = "Passw0rd",
            confirmation = "Passw0rd",
        )

        assertTrue(result.isValid)
        assertEquals("alice_01", result.normalizedUsername)
        assertEquals("alice@example.com", result.normalizedEmail)
        assertNull(result.usernameError)
        assertNull(result.emailError)
    }

    @Test fun registrationRejectsInvalidFieldsWithoutEchoingCredentials() {
        val result = AuthenticationFormPolicy.register("ab", "invalid-email", "password1", "different")

        assertFalse(result.isValid)
        assertEquals("用户名需要 3 至 32 个字符，仅支持字母、数字和下划线，且不能全为数字。", result.usernameError)
        assertEquals("请输入有效的邮箱地址。", result.emailError)
        assertEquals("密码需包含大写英文字母。", result.passwordError)
        assertEquals("两次输入的密码不一致。", result.confirmationError)
        assertFalse(result.renderedErrors.any { it.contains("invalid-email") || it.contains("password1") || it.contains("different") })
    }

    @Test fun registrationHonorsUtf8PasswordByteLimit() {
        val withinLimit = "A" + "a".repeat(253) + "1" + "b"
        val overLimit = withinLimit + "b"

        assertTrue(AuthenticationFormPolicy.register("alice_01", "alice@example.com", withinLimit, withinLimit).isValid)
        val rejected = AuthenticationFormPolicy.register("alice_01", "alice@example.com", overLimit, overLimit)

        assertEquals("密码不能超过 256 个字节。", rejected.passwordError)
        assertFalse(rejected.isValid)
    }

    @Test fun confirmationAcceptsOnlySixAsciiDigitsWithoutEchoingCode() {
        assertTrue(RegistrationFormPolicy.validateVerificationCode("012345").isValid)
        listOf("12345", "1234567", "１２３４５６", "12a456").forEach { code ->
            val result = RegistrationFormPolicy.validateVerificationCode(code)
            assertFalse(result.isValid)
            assertEquals("请输入 6 位数字验证码。", result.codeError)
            assertFalse(result.renderedErrors.any { it.contains(code) })
        }
    }

    @Test fun loginPreservesPhoneEmailAndUsernameIdentifierCompatibility() {
        val phone = AuthenticationFormPolicy.login("+8613800138000", "legacy")
        val email = AuthenticationFormPolicy.login(" Alice@Example.COM ", "legacy")
        val username = AuthenticationFormPolicy.login(" Alice_01 ", "legacy")

        assertTrue(phone.isValid)
        assertEquals("+8613800138000", phone.normalizedIdentifier)
        assertEquals("alice@example.com", email.normalizedIdentifier)
        assertEquals("alice_01", username.normalizedIdentifier)
    }

    @Test fun loginRequiresRecognizableIdentifierAndPassword() {
        val result = AuthenticationFormPolicy.login("", "")

        assertEquals("请输入有效的邮箱、手机号或用户名。", result.identifierError)
        assertEquals("请输入密码。", result.passwordError)
        assertFalse(result.isValid)
    }
}
