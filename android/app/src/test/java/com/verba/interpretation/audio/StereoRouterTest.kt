package com.verba.interpretation.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class StereoRouterTest {
    private val mono = byteArrayOf(1, 2, 3, 4)

    @Test fun routesLeft() = assertArrayEquals(byteArrayOf(1, 2, 0, 0, 3, 4, 0, 0), StereoRouter.routeMonoPcm16(mono, PlaybackRoute.LEFT))
    @Test fun routesRight() = assertArrayEquals(byteArrayOf(0, 0, 1, 2, 0, 0, 3, 4), StereoRouter.routeMonoPcm16(mono, PlaybackRoute.RIGHT))
    @Test fun routesBoth() = assertArrayEquals(byteArrayOf(1, 2, 1, 2, 3, 4, 3, 4), StereoRouter.routeMonoPcm16(mono, PlaybackRoute.BOTH))
    @Test fun captionsAreSilent() = assertEquals(0, StereoRouter.routeMonoPcm16(mono, PlaybackRoute.CAPTIONS).size)
}
