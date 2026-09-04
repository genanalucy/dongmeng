package com.verba.interpretation.cloud

import com.verba.interpretation.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudEndpointSecurityPolicyTest {
    private val releaseLockedPolicy = CloudEndpointSecurityPolicy(
        allowInsecure = false,
        lockedUrl = BuildConfig.CLOUD_API_URL,
    )

    @Test fun debugAllowsHttpForLocalCloudTesting() {
        assertTrue(CloudEndpointSecurityPolicy(true).validate("http://127.0.0.1:8080").isSuccess)
    }

    @Test fun debugAcceptsArbitraryHttpsCloudHosts() {
        val result = CloudEndpointSecurityPolicy(true).validate("https://dev-cloud.example.com")

        assertEquals("https://dev-cloud.example.com", result.getOrThrow())
    }

    @Test fun legacyTencentCloudApiUrlIsMigratedButCustomUrlsArePreserved() {
        assertTrue(obsoleteCloudApiDefaults().contains("http://114.132.83.144:8080"))
        assertFalse(obsoleteCloudApiDefaults().contains("https://custom.example.com"))
    }

    @Test fun releaseRequiresHttpsAndRejectsCredentials() {
        val policy = CloudEndpointSecurityPolicy(false)
        assertFalse(policy.validate("http://cloud.example.com").isSuccess)
        assertFalse(policy.validate("https://user:password@cloud.example.com").isSuccess)
        assertTrue(policy.validate("https://cloud.example.com").isSuccess)
    }

    @Test fun releaseLockRejectsArbitraryCloudHosts() {
        assertTrue(releaseLockedPolicy.isLocked)

        val hostileHost = releaseLockedPolicy.validate("https://attacker.example.com")
        val productionHostAlternatePath = releaseLockedPolicy.validate("${BuildConfig.CLOUD_API_URL}/exfil")
        val productionHostAlternatePort = releaseLockedPolicy.validate("https://47-129-170-16.sslip.io:8443")

        assertTrue(hostileHost.isFailure)
        assertTrue(productionHostAlternatePath.isFailure)
        assertTrue(productionHostAlternatePort.isFailure)
        assertTrue(hostileHost.exceptionOrNull()?.message?.contains("生产") == true)
    }

    @Test fun releaseLockAcceptsOnlyBuiltInProductionCloudUrl() {
        val result = releaseLockedPolicy.validate(BuildConfig.CLOUD_API_URL)

        assertEquals(BuildConfig.CLOUD_API_URL, result.getOrThrow())
    }

    @Test fun releaseIgnoresStoredCloudUrlOverrides() {
        val resolved = resolveCloudApiUrl(
            stored = "https://attacker.example.com",
            defaultUrl = BuildConfig.CLOUD_API_URL,
            locked = true,
        )

        assertEquals(BuildConfig.CLOUD_API_URL, resolved)
    }

    @Test fun debugResolutionKeepsStoredCloudUrlOverrides() {
        val resolved = resolveCloudApiUrl(
            stored = "https://dev-cloud.example.com",
            defaultUrl = BuildConfig.CLOUD_API_URL,
            locked = false,
        )

        assertEquals("https://dev-cloud.example.com", resolved)
    }
}
