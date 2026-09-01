package com.verba.interpretation.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationFormPolicyTest {
    @Test fun validFormNormalizesTrimmedServerCompatibleEmail() {
        val result = RegistrationFormPolicy.validate(
            email = "  User@Example.COM ",
            password = "Passw0rd",
            confirmation = "Passw0rd",
        )

        assertTrue(result.isValid)
        assertEquals("user@example.com", result.normalizedEmail)
        assertNull(result.emailError)
        assertNull(result.passwordError)
        assertNull(result.confirmationError)
    }

    @Test fun rejectsTooShortPassword() {
        val result = RegistrationFormPolicy.validate("user@example.com", "Pas1", "Pas1")

        assertEquals("密码至少需要 8 个字符。", result.passwordError)
        assertFalse(result.isValid)
    }

    @Test fun rejectsPasswordWithoutUppercaseAsciiLetter() {
        val result = RegistrationFormPolicy.validate("user@example.com", "password1", "password1")

        assertEquals("密码需包含大写英文字母。", result.passwordError)
    }

    @Test fun rejectsPasswordWithoutLowercaseAsciiLetter() {
        val result = RegistrationFormPolicy.validate("user@example.com", "PASSWORD1", "PASSWORD1")

        assertEquals("密码需包含小写英文字母。", result.passwordError)
    }

    @Test fun rejectsPasswordWithoutAsciiNumber() {
        val result = RegistrationFormPolicy.validate("user@example.com", "PasswordA", "PasswordA")

        assertEquals("密码需包含数字。", result.passwordError)
    }

    @Test fun rejectsMismatchedConfirmation() {
        val result = RegistrationFormPolicy.validate("user@example.com", "Passw0rd", "Passw0rD")

        assertEquals("两次输入的密码不一致。", result.confirmationError)
        assertFalse(result.isValid)
    }

    @Test fun rejectsInvalidEmailWithoutEchoingIt() {
        val result = RegistrationFormPolicy.validate("display <user@example.com>", "Passw0rd", "Passw0rd")

        assertEquals("请输入有效的邮箱地址。", result.emailError)
        assertFalse(result.renderedErrors.any { it.contains("display") || it.contains("user@example.com") })
    }

    @Test fun acceptsServerCompatibleMailboxWithoutDotInDomain() {
        val result = RegistrationFormPolicy.validate("user@localhost", "Passw0rd", "Passw0rd")

        assertTrue(result.isValid)
        assertEquals("user@localhost", result.normalizedEmail)
    }

    @Test fun rejectsEmailLongerThanServerLimit() {
        val email = "a".repeat(251) + "@abc"
        val result = RegistrationFormPolicy.validate(email, "Passw0rd", "Passw0rd")

        assertEquals("请输入有效的邮箱地址。", result.emailError)
    }

    @Test fun registrationModeDispatchesOnlyTheSelectedTransition() {
        val calls = mutableListOf<String>()
        val callbacks = RegistrationModeCallbacks(
            onLogin = { calls += "login" },
            onRegister = { calls += "register" },
        )

        RegistrationModeDispatcher.dispatch(AuthenticationMode.REGISTER, callbacks)
        RegistrationModeDispatcher.dispatch(AuthenticationMode.LOGIN, callbacks)

        assertEquals(listOf("register", "login"), calls)
    }
}
