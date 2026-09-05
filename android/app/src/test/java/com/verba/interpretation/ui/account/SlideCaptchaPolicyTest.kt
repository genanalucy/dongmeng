package com.verba.interpretation.ui.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideCaptchaPolicyTest {
    @Test fun convertsReleaseOffsetToChallengePixelsAndClampsTileToBoard() {
        val scale = SlideCaptchaGeometryPolicy.displayScale(150f, 300)
        assertEquals(6, SlideCaptchaGeometryPolicy.submittedTileX(3f, 0, 40, 300, scale))
        assertEquals(260, SlideCaptchaGeometryPolicy.submittedTileX(999f, 0, 40, 300, scale))
        assertEquals(0, SlideCaptchaGeometryPolicy.submittedTileX(-999f, 0, 40, 300, scale))
    }

    @Test fun submissionOnlyEmitsLegalCoordinate() {
        var submitted: Int? = null
        assertTrue(SlideCaptchaSubmissionPolicy.submit(130f, 0, 40, 300, 0.5f) { submitted = it })
        assertEquals(260, submitted)
        assertFalse(SlideCaptchaGeometryPolicy.isSubmittable(261, 300, 40))
    }
}
