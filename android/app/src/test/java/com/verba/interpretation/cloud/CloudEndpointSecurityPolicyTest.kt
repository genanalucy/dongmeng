package com.verba.interpretation.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudEndpointSecurityPolicyTest {
    @Test fun debugAllowsHttpForLocalCloudTesting() {
        assertTrue(CloudEndpointSecurityPolicy(true).validate("http://127.0.0.1:8080").isSuccess)
    }

    @Test fun releaseRequiresHttpsAndRejectsCredentials() {
        val policy = CloudEndpointSecurityPolicy(false)
        assertFalse(policy.validate("http://cloud.example.com").isSuccess)
        assertFalse(policy.validate("https://user:password@cloud.example.com").isSuccess)
        assertTrue(policy.validate("https://cloud.example.com").isSuccess)
    }
}
