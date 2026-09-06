package com.verba.interpretation.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountPresentationPolicyTest {
    @Test fun formatsIsoTimeForPeople() {
        val formatted = formatAccountTime("2026-09-02T12:00:00Z")
        assertTrue(formatted.startsWith("2026年9月2日 "))
        assertTrue(formatted != "2026-09-02T12:00:00Z")
    }

    @Test fun malformedOrMissingTimeDoesNotLeakRawValue() {
        assertEquals("暂无记录", formatAccountTime(null))
        assertEquals("时间不可用", formatAccountTime("not-a-time"))
    }

    @Test fun parsesOffsetTimeAndRejectsMalformedInput() {
        assertTrue(parseAccountInstant("2026-09-02T12:00:00+02:00") != null)
        assertNull(parseAccountInstant("2026-09-02"))
    }

    @Test fun clampsNegativeUsageAndUsesReadableUnits() {
        assertEquals("0 分钟", formatDuration(-10))
        assertEquals("1 小时 1 分", formatDuration(3660))
    }
}
