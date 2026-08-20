package com.verba.interpretation.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnSessionCoordinatorTest {
    @Test fun playsOutOfOrderTtsInTurnCreationOrder() {
        val coordinator = TurnSessionCoordinator<String>()
        coordinator.add(1, "first")
        coordinator.activate(1)
        coordinator.add(2, "second")
        assertEquals("first", coordinator.activate(2)?.previous)
        assertEquals(listOf("first", "second"), coordinator.stopAndFinishSessions())

        assertNull(coordinator.offerTts(2, byteArrayOf(2)))
        assertNull(coordinator.sessionFinished(2))
        assertFalse(coordinator.canBecomeIdle())
        val firstPlayback = coordinator.offerTts(1, byteArrayOf(1))
        assertEquals(1L, firstPlayback?.turnId)
        assertArrayEquals(byteArrayOf(1), firstPlayback?.pcm)
        assertNull(coordinator.playbackFinished())

        val secondPlayback = coordinator.sessionFinished(1)
        assertEquals(2L, secondPlayback?.turnId)
        assertArrayEquals(byteArrayOf(2), secondPlayback?.pcm)
        assertNull(coordinator.playbackFinished())
        assertEquals(0, coordinator.sessionCount())
        assertTrue(coordinator.canBecomeIdle())
    }

    @Test fun readyBeforeActivationIsRetained() {
        val coordinator = TurnSessionCoordinator<String>()
        coordinator.add(1, "only")

        assertFalse(coordinator.markReady(1))
        assertTrue(coordinator.activate(1)?.ready == true)
    }

    @Test fun zeroTtsTurnCanDrainToIdle() {
        val coordinator = TurnSessionCoordinator<String>()
        coordinator.add(1, "only")
        coordinator.activate(1)

        assertEquals(listOf("only"), coordinator.stopAndFinishSessions())
        assertFalse(coordinator.canBecomeIdle())
        assertNull(coordinator.sessionFinished(1))
        assertTrue(coordinator.canBecomeIdle())
    }

    @Test fun cancelAllReturnsEverySessionAndClearsPlayback() {
        val coordinator = TurnSessionCoordinator<String>()
        coordinator.add(1, "first")
        coordinator.activate(1)
        coordinator.add(2, "second")
        coordinator.activate(2)
        assertEquals(1L, coordinator.offerTts(1, byteArrayOf(1))?.turnId)

        assertEquals(listOf("first", "second"), coordinator.cancelAll())
        assertEquals(0, coordinator.sessionCount())
        assertFalse(coordinator.isActive(1))
        assertFalse(coordinator.isActive(2))
        assertFalse(coordinator.canBecomeIdle())
        assertNull(coordinator.offerTts(1, byteArrayOf(9)))
    }
}
