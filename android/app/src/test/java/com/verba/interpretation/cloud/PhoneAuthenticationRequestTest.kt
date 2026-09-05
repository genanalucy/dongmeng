package com.verba.interpretation.cloud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AuthenticationRequestTest {
    @Test fun loginRequestContainsOnlyIdentifierAndPassword() {
        val payload = JSONObject(LoginRequest("alice@example.com", "Passw0rd").toJson())

        assertEquals(2, payload.length())
        assertEquals("alice@example.com", payload.getString("identifier"))
        assertEquals("Passw0rd", payload.getString("password"))
        assertFalse(payload.has("phone"))
    }
}
