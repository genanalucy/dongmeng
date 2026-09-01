package com.verba.interpretation.cloud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAuthenticationRequestTest {
    @Test fun registrationRequestContainsOnlyPhoneAuthenticationContractFields() {
        val payload = JSONObject(PhoneRegistrationRequest("alice_01", "+8613800138000", "Passw0rd").toJson())

        assertEquals(3, payload.length())
        assertTrue(payload.has("username"))
        assertTrue(payload.has("phone"))
        assertFalse(payload.has("email"))
        assertTrue(payload.has("password"))
    }

    @Test fun loginRequestContainsOnlyPhoneAndPassword() {
        val payload = JSONObject(PhoneLoginRequest("+8613800138000", "Passw0rd").toJson())

        assertEquals(2, payload.length())
        assertTrue(payload.has("phone"))
        assertFalse(payload.has("email"))
        assertTrue(payload.has("password"))
    }
}
