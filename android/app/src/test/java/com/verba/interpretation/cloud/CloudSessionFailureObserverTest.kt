package com.verba.interpretation.cloud

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
class CloudSessionFailureObserverTest {
    @Test
    fun terminalFailureReachesSafeStateWithoutSessionIdentity() = runTest {
        val failures = MutableSharedFlow<TranslationSessionCoordinator.EndFailure>()
        val safeState = MutableStateFlow<CloudSessionFailureCode?>(null)
        val observer = collectTerminalCloudSessionFailures(failures, backgroundScope) { code ->
            safeState.value = code
        }
        runCurrent()

        failures.emit(
            TranslationSessionCoordinator.EndFailure(
                sessionId = "session-id-must-not-escape",
                attempts = 3,
                willRetry = false,
            ),
        )
        advanceUntilIdle()

        assertEquals(CloudSessionFailureCode.CLOUD_SESSION_CLOSE_FAILED, safeState.value)
        assertEquals("CLOUD_SESSION_CLOSE_FAILED", safeState.value?.name)
        observer.cancel()
    }

    @Test
    fun retryableFailureDoesNotCreateTerminalState() = runTest {
        val failures = MutableSharedFlow<TranslationSessionCoordinator.EndFailure>()
        val safeState = MutableStateFlow<CloudSessionFailureCode?>(null)
        val observer = collectTerminalCloudSessionFailures(failures, backgroundScope) { code ->
            safeState.value = code
        }
        runCurrent()

        failures.emit(
            TranslationSessionCoordinator.EndFailure(
                sessionId = "retry-session",
                attempts = 1,
                willRetry = true,
            ),
        )
        advanceUntilIdle()

        assertNull(safeState.value)
        observer.cancel()
    }
}
