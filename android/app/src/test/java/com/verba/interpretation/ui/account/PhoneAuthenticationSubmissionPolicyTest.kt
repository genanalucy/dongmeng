package com.verba.interpretation.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticationSubmissionPolicyTest {
    @Test fun loginDispatchesCanonicalizedEmailIdentifierAndPassword() {
        var received: Pair<String, String>? = null

        val dispatched = AuthenticationSubmissionPolicy.submitLogin(" Alice@Example.COM ", "legacy-password") { identifier, password ->
            received = identifier to password
        }

        assertTrue(dispatched)
        assertEquals("alice@example.com" to "legacy-password", received)
    }

    @Test fun registrationDispatchesEmailOnlyDetailsAfterValidation() {
        var received: List<String>? = null

        val dispatched = AuthenticationSubmissionPolicy.submitRegistration(
            username = " Alice_01 ",
            email = " Alice@Example.COM ",
            password = "Passw0rd",
            confirmation = "Passw0rd",
        ) { username, email, password -> received = listOf(username, email, password) }

        assertTrue(dispatched)
        assertEquals(listOf("alice_01", "alice@example.com", "Passw0rd"), received)
    }

    @Test fun invalidRegistrationDoesNotDispatch() {
        var calls = 0

        val dispatched = AuthenticationSubmissionPolicy.submitRegistration("ab", "alice@example.com", "Passw0rd", "Passw0rd") { _, _, _ -> calls++ }

        assertFalse(dispatched)
        assertEquals(0, calls)
    }

    @Test fun verificationDispatchesOnlySixAsciiDigits() {
        var received: Pair<String, String>? = null

        assertTrue(AuthenticationSubmissionPolicy.submitVerification("alice@example.com", "012345") { email, code -> received = email to code })
        assertEquals("alice@example.com" to "012345", received)
        assertFalse(AuthenticationSubmissionPolicy.submitVerification("alice@example.com", "12a456") { _, _ -> error("must not dispatch") })
    }
}
