package com.verba.interpretation.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAuthenticationSubmissionPolicyTest {
    @Test fun loginDispatchesCanonicalizedEmailIdentifierAndPassword() {
        var received: Pair<String, String>? = null

        val dispatched = PhoneAuthenticationSubmissionPolicy.submitLogin(
            identifier = " Alice@Example.COM ",
            password = "legacy-password",
            onLogin = { identifier, password -> received = identifier to password },
        )

        assertTrue(dispatched)
        assertEquals("alice@example.com" to "legacy-password", received)
    }

    @Test fun invalidLoginDoesNotDispatch() {
        var calls = 0

        val dispatched = PhoneAuthenticationSubmissionPolicy.submitLogin("invalid", "",) { _, _ -> calls++ }

        assertFalse(dispatched)
        assertEquals(0, calls)
    }

    @Test fun registrationDispatchesAllCanonicalizedIdentitiesAfterValidation() {
        var received: List<String>? = null

        val dispatched = PhoneAuthenticationSubmissionPolicy.submitRegistration(
            username = " Alice_01 ",
            email = " Alice@Example.COM ",
            phone = "13800138000",
            password = "Passw0rd",
            confirmation = "Passw0rd",
            onRegister = { username, email, phone, password -> received = listOf(username, email, phone, password) },
        )

        assertTrue(dispatched)
        assertEquals(listOf("alice_01", "alice@example.com", "+8613800138000", "Passw0rd"), received)
    }

    @Test fun overByteLimitRegistrationDoesNotDispatch() {
        var calls = 0
        val password = "A1a" + "中".repeat(85)

        val dispatched = PhoneAuthenticationSubmissionPolicy.submitRegistration(
            username = "alice_01",
            email = "alice@example.com",
            phone = "13800138000",
            password = password,
            confirmation = password,
        ) { _, _, _, _ -> calls++ }

        assertFalse(dispatched)
        assertEquals(0, calls)
    }

    @Test fun supplementaryUnicodeOverByteLimitDoesNotDispatch() {
        var calls = 0
        val password = "A1a!" + "\uD83D\uDE00".repeat(63) + "x"

        assertEquals(257, password.toByteArray(Charsets.UTF_8).size)
        val dispatched = PhoneAuthenticationSubmissionPolicy.submitRegistration("alice_01", "alice@example.com", "13800138000", password, password) { _, _, _, _ -> calls++ }

        assertFalse(dispatched)
        assertEquals(0, calls)
    }

    @Test fun invalidRegistrationDoesNotDispatch() {
        var calls = 0

        val dispatched = PhoneAuthenticationSubmissionPolicy.submitRegistration(
            username = "ab",
            email = "alice@example.com",
            phone = "13800138000",
            password = "Passw0rd",
            confirmation = "Passw0rd",
        ) { _, _, _, _ -> calls++ }

        assertFalse(dispatched)
        assertEquals(0, calls)
    }
}
