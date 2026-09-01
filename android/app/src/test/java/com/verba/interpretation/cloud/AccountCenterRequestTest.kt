package com.verba.interpretation.cloud

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AccountCenterRequestTest {
    @Test fun identityUpdateRequestContainsOnlyRequiredContractFields() {
        val payload = JSONObject(
            IdentityUpdateRequest(
                username = "alice_01",
                email = "alice@example.test",
                phone = "+8613800138000",
                currentPassword = "Aa123456",
            ).toJson(),
        )

        assertEquals(4, payload.length())
        assertEquals("alice_01", payload.getString("username"))
        assertEquals("alice@example.test", payload.getString("email"))
        assertEquals("+8613800138000", payload.getString("phone"))
        assertEquals("Aa123456", payload.getString("current_password"))
        assertFalse(payload.has("access_token"))
        assertFalse(payload.has("refresh_token"))
    }
}
