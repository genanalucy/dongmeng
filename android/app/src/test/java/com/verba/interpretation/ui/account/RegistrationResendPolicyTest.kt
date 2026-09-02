package com.verba.interpretation.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegistrationResendPolicyTest {
    @Test fun resendBecomesEnabledAtCooldownExpiryAndInvokesItsRequest() {
        val deadlineMillis = 61_000L
        var requests = 0

        assertEquals(1, RegistrationResendPolicy.remainingSeconds(deadlineMillis, 60_001L))
        assertFalse(RegistrationResendPolicy.submitWhenReady(deadlineMillis, 60_001L) { requests++ })
        assertEquals(0, requests)

        assertEquals(0, RegistrationResendPolicy.remainingSeconds(deadlineMillis, 61_000L))
        assertTrue(RegistrationResendPolicy.submitWhenReady(deadlineMillis, 61_000L) { requests++ })
        assertEquals(1, requests)
    }
}
