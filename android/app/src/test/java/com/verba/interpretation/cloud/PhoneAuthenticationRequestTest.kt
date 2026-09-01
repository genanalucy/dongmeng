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
        assertEquals("alice_01", payload.getString("username"))
        assertEquals("+8613800138000", payload.getString("phone"))
        assertEquals("Passw0rd", payload.getString("password"))
        assertFalse(payload.has("email"))
    }

    @Test fun loginRequestContainsOnlyPhoneAndPassword() {
        val payload = JSONObject(PhoneLoginRequest("+8613800138000", "Passw0rd").toJson())

        assertEquals(2, payload.length())
        assertEquals("+8613800138000", payload.getString("phone"))
        assertEquals("Passw0rd", payload.getString("password"))
        assertFalse(payload.has("email"))
    }
}
