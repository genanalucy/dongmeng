package com.verba.interpretation.ui.account

import com.verba.interpretation.update.AppUpdateProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class AppAboutDownloadProgressTest {
    private val megabyte = 1024L * 1024L

    @Test fun knownSizeRendersZeroIntermediateAndCompletedPercentages() {
        assertEquals("0.0 MB / 20.0 MB（0%）", formatDownloadProgress(AppUpdateProgress(0, 20 * megabyte, 0)))
        assertEquals("10.0 MB / 20.0 MB（50%） · 2.0 MB/s", formatDownloadProgress(AppUpdateProgress(10 * megabyte, 20 * megabyte, 5_000)))
        assertEquals("20.0 MB / 20.0 MB（100%） · 2.0 MB/s", formatDownloadProgress(AppUpdateProgress(20 * megabyte, 20 * megabyte, 10_000)))
    }

    @Test fun missingOrUnknownSizeDoesNotFormatAPercentage() {
        assertEquals("已下载 0.0 MB", formatDownloadProgress(null))
        assertEquals("已下载 2.0 MB · 1.0 MB/s", formatDownloadProgress(AppUpdateProgress(2 * megabyte, null, 2_000)))
        assertEquals("已下载 2.0 MB", formatDownloadProgress(AppUpdateProgress(2 * megabyte, 0, 0)))
    }

    @Test fun percentageIsClampedWhenReportedBytesExceedTheTotal() {
        assertEquals("21.0 MB / 20.0 MB（100%）", formatDownloadProgress(AppUpdateProgress(21 * megabyte, 20 * megabyte, 0)))
    }
}
