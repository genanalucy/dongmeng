package com.verba.interpretation.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatestLoginIdentifierPolicyTest {
    @Test fun loginIdentifierTrimsSurroundingWhitespace() {
        assertEquals("alice_01", LatestLoginIdentifierPolicy.loginIdentifier("  alice_01 "))
    }

    @Test fun blankLoginIdentifierIsNotPersisted() {
        assertNull(LatestLoginIdentifierPolicy.loginIdentifier("   "))
    }

    @Test fun overlongLoginIdentifierIsRejected() {
        assertNull(LatestLoginIdentifierPolicy.loginIdentifier("a".repeat(255)))
    }

    @Test fun registrationIdentifierUsesVerifiedUsername() {
        assertEquals("alice_01", LatestLoginIdentifierPolicy.registrationIdentifier(" alice_01 "))
    }

    @Test fun registrationIdentifierRejectsBlankAndLegacyFallbackUsername() {
        assertNull(LatestLoginIdentifierPolicy.registrationIdentifier("  "))
        assertNull(LatestLoginIdentifierPolicy.registrationIdentifier("旧版用户"))
    }
}
