package com.verba.interpretation.protocol

import com.verba.interpretation.cloud.TranslationSessionGrant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAgentHandshakeTest {
    @Test fun cloudGrantUsesRequiredSubprotocolsAndBoundStartPayload() {
        val grant = TranslationSessionGrant("session-1", "user-1", "install-1", "secret-token")
        val json = JSONObject(CloudAgentHandshake.startMessage(grant, "zh", "en").toJson())

        assertEquals("translation.v1, translation.jwt.secret-token", CloudAgentHandshake.subprotocols(grant))
        assertEquals("session-1", json.getString("sessionId"))
        assertEquals("user-1", json.getString("userId"))
        assertEquals("install-1", json.getString("installId"))
        assertTrue(json.getString("sessionId") != "install-1")
    }
}
