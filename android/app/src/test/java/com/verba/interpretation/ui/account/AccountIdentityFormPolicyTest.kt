package com.verba.interpretation.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountIdentityFormPolicyTest {
    @Test fun initialPhoneIsMaskedAndCannotBeSubmittedWithoutFullReplacement() {
        val validation = AccountIdentityFormPolicy.validate(
            username = "Alice_01",
            email = "Alice@example.test",
            phone = "138****8000",
            currentPassword = "Aa123456",
        )

        assertEquals("alice_01", validation.username)
        assertEquals("alice@example.test", validation.email)
        assertEquals("", validation.phone)
        assertFalse(validation.isValid)
        assertEquals("请输入有效的中国大陆手机号。", validation.phoneError)
    }

    @Test fun validIdentityNormalizesAndCanDispatch() {
        val validation = AccountIdentityFormPolicy.validate(
            username = "Alice_01",
            email = "Alice@Example.Test",
            phone = "13800138000",
            currentPassword = "Aa123456",
        )

        assertTrue(validation.isValid)
        assertEquals("alice_01", validation.username)
        assertEquals("alice@example.test", validation.email)
        assertEquals("+8613800138000", validation.phone)
    }

    @Test fun invalidIdentityDoesNotDispatch() {
        var calls = 0

        AccountIdentitySubmissionPolicy.submit("bad name", "invalid", "138****8000", "",) { _, _, _, _ -> calls++ }

        assertEquals(0, calls)
    }
}
