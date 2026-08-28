package com.verba.interpretation.ui

import com.verba.interpretation.cloud.CloudSessionFailureCode
import com.verba.interpretation.cloud.TranslationSessionCoordinator
import com.verba.interpretation.cloud.collectTerminalCloudSessionFailures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Binds terminal close failures to the Face-to-Face UI's safe diagnostic state. */
internal fun observeFaceToFaceCloudSessionFailures(
    failures: Flow<TranslationSessionCoordinator.EndFailure>,
    scope: CoroutineScope,
    diagnostic: MutableStateFlow<CloudSessionFailureCode?>,
): Job = collectTerminalCloudSessionFailures(failures, scope) { code -> diagnostic.value = code }

/** Binds terminal close failures to the Interpretation UI's safe diagnostic state. */
internal fun observeInterpretationCloudSessionFailures(
    failures: Flow<TranslationSessionCoordinator.EndFailure>,
    scope: CoroutineScope,
    diagnostic: MutableStateFlow<CloudSessionFailureCode?>,
): Job = collectTerminalCloudSessionFailures(failures, scope) { code -> diagnostic.value = code }
