package com.verba.interpretation.ui

import com.verba.interpretation.cloud.CloudSessionFailureCode
import com.verba.interpretation.cloud.TranslationSessionCoordinator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudSessionFailureBindingsTest {
    @Test
    fun faceToFaceBindingPublishesOnlySafeTerminalCode() = runTest {
        val failures = MutableSharedFlow<TranslationSessionCoordinator.EndFailure>()
        val state = MutableStateFlow<CloudSessionFailureCode?>(null)
        val observer = observeFaceToFaceCloudSessionFailures(failures, backgroundScope, state)
        runCurrent()

        failures.emit(TranslationSessionCoordinator.EndFailure("face-session", 3, willRetry = false))
        advanceUntilIdle()

        assertEquals(CloudSessionFailureCode.CLOUD_SESSION_CLOSE_FAILED, state.value)
        assertEquals("CLOUD_SESSION_CLOSE_FAILED", state.value?.name)
        observer.cancel()
    }

    @Test
    fun faceToFaceBindingIgnoresRetryableFailure() = runTest {
        val failures = MutableSharedFlow<TranslationSessionCoordinator.EndFailure>()
        val state = MutableStateFlow<CloudSessionFailureCode?>(null)
        val observer = observeFaceToFaceCloudSessionFailures(failures, backgroundScope, state)
        runCurrent()

        failures.emit(TranslationSessionCoordinator.EndFailure("face-retry-session", 1, willRetry = true))
        advanceUntilIdle()

        assertNull(state.value)
        observer.cancel()
    }

    @Test
    fun interpretationBindingPublishesOnlySafeTerminalCode() = runTest {
        val failures = MutableSharedFlow<TranslationSessionCoordinator.EndFailure>()
        val state = MutableStateFlow<CloudSessionFailureCode?>(null)
        val observer = observeInterpretationCloudSessionFailures(failures, backgroundScope, state)
        runCurrent()

        failures.emit(TranslationSessionCoordinator.EndFailure("interpret-session", 3, willRetry = false))
        advanceUntilIdle()

        assertEquals(CloudSessionFailureCode.CLOUD_SESSION_CLOSE_FAILED, state.value)
        assertEquals("CLOUD_SESSION_CLOSE_FAILED", state.value?.name)
        observer.cancel()
    }

    @Test
    fun interpretationBindingIgnoresRetryableFailure() = runTest {
        val failures = MutableSharedFlow<TranslationSessionCoordinator.EndFailure>()
        val state = MutableStateFlow<CloudSessionFailureCode?>(null)
        val observer = observeInterpretationCloudSessionFailures(failures, backgroundScope, state)
        runCurrent()

        failures.emit(TranslationSessionCoordinator.EndFailure("interpret-retry-session", 1, willRetry = true))
        advanceUntilIdle()

        assertNull(state.value)
        observer.cancel()
    }
}
