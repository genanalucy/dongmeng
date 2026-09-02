package com.verba.interpretation.protocol

import com.verba.interpretation.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointSettingsTest {
    private val debugPolicy = EndpointSecurityPolicy(allowInsecure = true)
    private val releasePolicy = EndpointSecurityPolicy(allowInsecure = false)

    @Test fun debugAcceptsHttpAndWsEndpoints() {
        val result = debugPolicy.validate("http://127.0.0.1:18765", "ws://127.0.0.1:18765/ws/translate")

        assertTrue(result.isSuccess)
        assertEquals("http://127.0.0.1:18765", result.getOrThrow().httpUrl)
    }

    @Test fun debugBuildUsesEc2HttpsTranslationEndpoints() {
        assertEquals("https://47-129-170-16.sslip.io", BuildConfig.CLOUD_API_URL)
        assertEquals("wss://47-129-170-16.sslip.io/ws/translate", BuildConfig.TRANSLATION_WS_URL)
        assertEquals("https://47-129-170-16.sslip.io", BuildConfig.TRANSLATION_ORIGIN)
    }

    @Test fun releaseRejectsCleartextEndpoints() {
        val result = releasePolicy.validate("http://agent.example.com", "ws://agent.example.com/ws/translate")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("https") == true)
    }

    @Test fun rejectsMalformedAndCredentialBearingEndpoints() {
        assertFalse(debugPolicy.validate("not a url", "ws://agent.example.com/ws").isSuccess)
        assertFalse(debugPolicy.validate("http://user:password@agent.example.com", "ws://agent.example.com/ws").isSuccess)
    }

    @Test fun acceptsOnlyDocumentedHealthPayload() {
        assertTrue(EndpointSettings.isHealthyResponse("{\"status\":\"ok\",\"service\":\"translator-agent\"}"))
        assertFalse(EndpointSettings.isHealthyResponse("{\"status\":\"ok\"}"))
        assertFalse(EndpointSettings.isHealthyResponse("not json"))
    }

    @Test fun convertsHttpEndpointToDocumentedWebSocketPath() {
        // Kept as a policy-level assertion so endpoint derivation remains predictable.
        val result = debugPolicy.validate("http://114.132.83.144:18765", "ws://114.132.83.144:18765/ws/translate")

        assertEquals("ws://114.132.83.144:18765/ws/translate", result.getOrThrow().webSocketUrl)
    }

    @Test fun migratesExactLegacyTranslationWebSocketPath() {
        assertEquals(
            "ws://114.132.83.144:18765/ws/translate",
            EndpointSettings.migrateLegacyWebSocketUrl(
                "ws://114.132.83.144:18765/v1/translation",
                "ws://114.132.83.144:18765/ws/translate",
            ),
        )
    }

    @Test fun preservesCustomWebSocketUrlDuringLegacyMigration() {
        assertEquals(
            "ws://agent.example.test/custom-path",
            EndpointSettings.migrateLegacyWebSocketUrl(
                "ws://agent.example.test/custom-path",
                "ws://114.132.83.144:18765/ws/translate",
            ),
        )
    }

    @Test fun appendsDocumentedHealthPathToBaseUrl() {
        val healthUrl = EndpointConfig("https://agent.example.com/base", "wss://agent.example.com/ws/translate").healthUrl()

        assertEquals("https://agent.example.com/base/api/health", healthUrl.toString())
    }
}
