package com.verba.interpretation.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophonePermissionPolicyTest {
    @Test
    fun grantedManualRequestPreservesSideAndConsumesExactlyOnce() {
        val policy = MicrophonePermissionPolicy()
        val action = MicrophonePermissionAction.Manual(FaceToFaceSide.RIGHT)

        assertTrue(policy.request(action))
        assertEquals(MicrophonePermissionPolicy.Result(action, true), policy.consumeResult(true))
        assertNull(policy.consumeResult(true))
    }

    @Test
    fun denialConsumesPendingActionWithoutExecutingIt() {
        val policy = MicrophonePermissionPolicy()
        val action = MicrophonePermissionAction.Manual(FaceToFaceSide.LEFT)

        policy.request(action)

        assertEquals(MicrophonePermissionPolicy.Result(action, false), policy.consumeResult(false))
        assertFalse(policy.hasPendingRequest())
    }

    @Test
    fun conflictingRequestAndClearCannotResurrectContinuousOrManualAction() {
        val policy = MicrophonePermissionPolicy()

        assertTrue(policy.request(MicrophonePermissionAction.Continuous))
        assertFalse(policy.request(MicrophonePermissionAction.Manual(FaceToFaceSide.LEFT)))
        policy.clear()

        assertNull(policy.consumeResult(true))
        assertTrue(policy.request(MicrophonePermissionAction.Manual(FaceToFaceSide.LEFT)))
    }

    @Test
    fun releaseAndCancelClearPendingRequestBeforePermissionCallback() {
        val policy = MicrophonePermissionPolicy()
        val manual = MicrophonePermissionAction.Manual(FaceToFaceSide.LEFT)
        val continuous = MicrophonePermissionAction.Continuous

        assertTrue(policy.request(manual))
        policy.clear()
        assertNull(policy.consumeResult(true))
        assertTrue(policy.request(continuous))
        policy.clear()
        assertNull(policy.consumeResult(false))
    }
}
