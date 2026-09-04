package com.verba.interpretation.protocol

import com.verba.interpretation.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointSettingsTest {
    private val debugPolicy = EndpointSecurityPolicy(allowInsecure = true)
    private val releasePolicy = EndpointSecurityPolicy(allowInsecure = false)

    // Mirrors the release default in EndpointSettings: BuildConfig production endpoints are locked.
    private val releaseLockedPolicy = EndpointSecurityPolicy(
        allowInsecure = false,
        lockedEndpoints = EndpointConfig(BuildConfig.AGENT_HTTP_URL, BuildConfig.TRANSLATION_WS_URL),
    )
    private val productionDefaults = EndpointConfig(BuildConfig.AGENT_HTTP_URL, BuildConfig.TRANSLATION_WS_URL)

    @Test fun debugAcceptsHttpAndWsEndpoints() {
        val result = debugPolicy.validate("http://127.0.0.1:18765", "ws://127.0.0.1:18765/ws/translate")

        assertTrue(result.isSuccess)
        assertEquals("http://127.0.0.1:18765", result.getOrThrow().httpUrl)
    }

    @Test fun debugAcceptsArbitraryHttpsHostsForDevelopmentServers() {
        val result = debugPolicy.validate("https://dev-agent.example.com", "wss://dev-agent.example.com/ws/translate")

        assertEquals("https://dev-agent.example.com", result.getOrThrow().httpUrl)
        assertEquals("wss://dev-agent.example.com/ws/translate", result.getOrThrow().webSocketUrl)
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

    @Test fun releaseLockRejectsArbitraryHttpsAndWssHosts() {
        assertTrue(releaseLockedPolicy.isLocked)

        val hostileHttp = releaseLockedPolicy.validate("https://attacker.example.com", BuildConfig.TRANSLATION_WS_URL)
        val hostileWebSocket = releaseLockedPolicy.validate(BuildConfig.AGENT_HTTP_URL, "wss://attacker.example.com/ws/translate")
        val bothHostile = releaseLockedPolicy.validate("https://attacker.example.com", "wss://attacker.example.com/ws/translate")

        assertTrue(hostileHttp.isFailure)
        assertTrue(hostileWebSocket.isFailure)
        assertTrue(bothHostile.isFailure)
        assertTrue(bothHostile.exceptionOrNull()?.message?.contains("生产") == true)
    }

    @Test fun releaseLockRejectsAlternatePortsAndPathsOnProductionHost() {
        val alternatePort = releaseLockedPolicy.validate("https://47-129-170-16.sslip.io:8443", BuildConfig.TRANSLATION_WS_URL)
        val alternatePath = releaseLockedPolicy.validate("https://47-129-170-16.sslip.io/exfil", BuildConfig.TRANSLATION_WS_URL)
        val alternateWebSocketPath = releaseLockedPolicy.validate(BuildConfig.AGENT_HTTP_URL, "wss://47-129-170-16.sslip.io/exfil")

        assertTrue(alternatePort.isFailure)
        assertTrue(alternatePath.isFailure)
        assertTrue(alternateWebSocketPath.isFailure)
    }

    @Test fun releaseLockAcceptsOnlyBuiltInProductionEndpoints() {
        val result = releaseLockedPolicy.validate(BuildConfig.AGENT_HTTP_URL, BuildConfig.TRANSLATION_WS_URL)

        assertEquals(productionDefaults, result.getOrThrow())
    }

    @Test fun releaseIgnoresStoredEndpointOverrides() {
        val resolved = EndpointSettings.resolveEndpointConfig(
            storedHttpUrl = "https://attacker.example.com",
            storedWebSocketUrl = "wss://attacker.example.com/ws/translate",
            defaults = productionDefaults,
            locked = true,
        )

        assertEquals(productionDefaults, resolved)
    }

    @Test fun debugResolutionKeepsStoredEndpointOverrides() {
        val resolved = EndpointSettings.resolveEndpointConfig(
            storedHttpUrl = "https://dev-agent.example.com",
            storedWebSocketUrl = "wss://dev-agent.example.com/ws/translate",
            defaults = productionDefaults,
            locked = false,
        )

        assertEquals("https://dev-agent.example.com", resolved.httpUrl)
        assertEquals("wss://dev-agent.example.com/ws/translate", resolved.webSocketUrl)
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

    @Test fun migratesHistoricalTencentAgentEndpoints() {
        val ec2HttpUrl = "https://47-129-170-16.sslip.io"
        val ec2WebSocketUrl = "wss://47-129-170-16.sslip.io/ws/translate"

        assertEquals(
            ec2HttpUrl,
            EndpointSettings.migrateLegacyHttpUrl("http://114.132.83.144:18765", ec2HttpUrl),
        )
        assertEquals(
            ec2WebSocketUrl,
            EndpointSettings.migrateLegacyWebSocketUrl(
                "ws://114.132.83.144:18765/v1/translation",
                ec2WebSocketUrl,
            ),
        )
        assertEquals(
            ec2WebSocketUrl,
            EndpointSettings.migrateLegacyWebSocketUrl(
                "ws://114.132.83.144:18765/ws/translate",
                ec2WebSocketUrl,
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
