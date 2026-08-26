package com.verba.interpretation.cloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Creates one Cloud grant for each agent connection and ends it exactly once. */
class TranslationSessionCoordinator(
    private val cloud: CloudTranslationSessionService,
    private val scope: CoroutineScope,
) {
    fun open(onGranted: (TranslationSessionGrant) -> Unit, onFailure: (String) -> Unit) {
        scope.launch {
            try {
                onGranted(withContext(Dispatchers.IO) { cloud.createTranslationSession() })
            } catch (error: Exception) {
                onFailure(error.message ?: "无法创建云端翻译会话。")
            }
        }
    }

    fun end(sessionId: String?) {
        if (sessionId == null) return
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { cloud.endTranslationSession(sessionId) } }
        }
    }
}
