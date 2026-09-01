package com.verba.interpretation.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAuthenticationSubmissionPolicyTest {
    @Test fun loginDispatchesOnlyNormalizedPhoneAndPassword() {
        var received: Pair<String, String>? = null

        val dispatched = PhoneAuthenticationSubmissionPolicy.submitLogin(
            phone = "13800138000",
            password = "legacy-password",
            onLogin = { phone, password -> received = phone to password },
        )

        assertTrue(dispatched)
        assertEquals("+8613800138000" to "legacy-password", received)
    }

    @Test fun invalidLoginDoesNotDispatch() {
        var calls = 0

        val dispatched = PhoneAuthenticationSubmissionPolicy.submitLogin("invalid", "",) { _, _ -> calls++ }

        assertFalse(dispatched)
        assertEquals(0, calls)
    }

    @Test fun registrationDispatchesUsernamePhoneAndPasswordAfterValidation() {
        var received: Triple<String, String, String>? = null

        val dispatched = PhoneAuthenticationSubmissionPolicy.submitRegistration(
            username = " Alice_01 ",
            phone = "13800138000",
            password = "Passw0rd",
            confirmation = "Passw0rd",
            onRegister = { username, phone, password -> received = Triple(username, phone, password) },
        )

        assertTrue(dispatched)
        assertEquals(Triple("alice_01", "+8613800138000", "Passw0rd"), received)
    }

    @Test fun overByteLimitRegistrationDoesNotDispatch() {
        var calls = 0
        val password = "A1a" + "中".repeat(85)

        val dispatched = PhoneAuthenticationSubmissionPolicy.submitRegistration(
            username = "alice_01",
            phone = "13800138000",
            password = password,
            confirmation = password,
        ) { _, _, _ -> calls++ }

        assertFalse(dispatched)
        assertEquals(0, calls)
    }

    @Test fun invalidRegistrationDoesNotDispatch() {
        var calls = 0

        val dispatched = PhoneAuthenticationSubmissionPolicy.submitRegistration(
            username = "ab",
            phone = "13800138000",
            password = "Passw0rd",
            confirmation = "Passw0rd",
        ) { _, _, _ -> calls++ }

        assertFalse(dispatched)
        assertEquals(0, calls)
    }
}
