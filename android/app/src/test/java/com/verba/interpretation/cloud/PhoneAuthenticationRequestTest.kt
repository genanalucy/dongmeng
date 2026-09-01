package com.verba.interpretation.cloud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAuthenticationRequestTest {
    @Test fun registrationRequestContainsAllMultiIdentityContractFields() {
        val payload = JSONObject(RegistrationRequest("alice_01", "alice@example.com", "+8613800138000", "Passw0rd").toJson())

        assertEquals(4, payload.length())
        assertEquals("alice_01", payload.getString("username"))
        assertEquals("alice@example.com", payload.getString("email"))
        assertEquals("+8613800138000", payload.getString("phone"))
        assertEquals("Passw0rd", payload.getString("password"))
    }

    @Test fun loginRequestContainsOnlyIdentifierAndPassword() {
        val payload = JSONObject(LoginRequest("alice@example.com", "Passw0rd").toJson())

        assertEquals(2, payload.length())
        assertEquals("alice@example.com", payload.getString("identifier"))
        assertEquals("Passw0rd", payload.getString("password"))
        assertFalse(payload.has("phone"))
    }
}
