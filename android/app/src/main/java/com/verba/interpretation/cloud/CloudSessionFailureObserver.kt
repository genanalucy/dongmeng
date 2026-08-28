package com.verba.interpretation.cloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** A UI-safe terminal Cloud session failure category with no session or transport details. */
enum class CloudSessionFailureCode {
    CLOUD_SESSION_CLOSE_FAILED,
}

/**
 * Starts a lifecycle-bound consumer for terminal end failures. Retryable failures deliberately do
 * not change UI state, and the callback receives only a fixed safe code.
 */
internal fun collectTerminalCloudSessionFailures(
    failures: Flow<TranslationSessionCoordinator.EndFailure>,
    scope: CoroutineScope,
    onTerminalFailure: (CloudSessionFailureCode) -> Unit,
): Job = scope.launch {
    failures.collect { failure ->
        if (!failure.willRetry) onTerminalFailure(CloudSessionFailureCode.CLOUD_SESSION_CLOSE_FAILED)
    }
}
