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

    @Test fun registrationNormalizesUsernameAndAcceptsLengthBoundaries() {
        val minimum = PhoneAuthenticationFormPolicy.register(" Ab_ ", "13800138000", "Passw0rd", "Passw0rd")
        val maximum = PhoneAuthenticationFormPolicy.register("A".repeat(32), "13800138000", "Passw0rd", "Passw0rd")

        assertTrue(minimum.isValid)
        assertEquals("ab_", minimum.normalizedUsername)
        assertTrue(maximum.isValid)
        assertEquals("a".repeat(32), maximum.normalizedUsername)
    }

    @Test fun registrationRejectsUsernameOutsideBoundariesOrWithNonAsciiCharacters() {
        listOf("ab", "a".repeat(33), "alice-name").forEach { username ->
            val result = PhoneAuthenticationFormPolicy.register(username, "13800138000", "Passw0rd", "Passw0rd")

            assertEquals("用户名需要 3 至 32 个字符，仅支持字母、数字和下划线。", result.usernameError)
            assertFalse(result.isValid)
        }
    }

    @Test fun registrationRejectsEachWeakPasswordRuleWithoutEchoingCredentials() {
        val cases = listOf(
            "Pas1" to "密码至少需要 8 个字符。",
            "password1" to "密码需包含大写英文字母。",
            "PASSWORD1" to "密码需包含小写英文字母。",
            "PasswordA" to "密码需包含数字。",
        )

        cases.forEach { (submittedPassword, expectedError) ->
            val result = PhoneAuthenticationFormPolicy.register("alice_01", "13800138000", submittedPassword, "different")

            assertEquals(expectedError, result.passwordError)
            assertFalse(result.renderedErrors.any { it.contains(submittedPassword) || it.contains("different") })
        }
    }

    @Test fun registrationAccepts256Utf8BytePasswordAndRejects257BytesWithoutEchoingIt() {
        val withinLimit = "A" + "a".repeat(253) + "1" + "b"
        val overLimit = withinLimit + "b"

        assertTrue(PhoneAuthenticationFormPolicy.register("alice_01", "13800138000", withinLimit, withinLimit).isValid)
        val result = PhoneAuthenticationFormPolicy.register("alice_01", "13800138000", overLimit, overLimit)

        assertEquals("密码不能超过 256 个字节。", result.passwordError)
        assertFalse(result.isValid)
        assertFalse(result.renderedErrors.any { it.contains(overLimit) })
    }

    @Test fun registrationRejectsMultiBytePasswordOverUtf8ByteLimit() {
        val password = "A1a" + "中".repeat(85)

        val result = PhoneAuthenticationFormPolicy.register("alice_01", "13800138000", password, password)

        assertEquals("密码不能超过 256 个字节。", result.passwordError)
        assertFalse(result.isValid)
        assertFalse(result.renderedErrors.any { it.contains(password) })
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

    @Test fun loginAllowsNonEmptyPasswordWithoutRegistrationStrengthValidation() {
        val result = PhoneAuthenticationFormPolicy.login("13800138000", "legacy")

        assertTrue(result.isValid)
        assertNull(result.passwordError)
    }
}
