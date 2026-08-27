package com.verba.interpretation.cloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Owns a Cloud translation session's short-lived, in-memory lifecycle metadata.
 * Usage is aggregated once per Cloud session; no audio, transcript or credentials are retained.
 */
data class UsageRecordPayload(
    val sessionId: String,
    val audioSeconds: Int,
    val characters: Int = 0,
) {
    init {
        require(sessionId.isNotBlank())
        require(audioSeconds >= 0)
        require(characters >= 0)
    }
}

class TranslationSessionCoordinator(
    private val cloud: CloudTranslationSessionService,
    private val scope: CoroutineScope,
    private val elapsedRealtimeMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private val lock = Any()
    private val startedAtMillis = mutableMapOf<String, Long>()

    fun open(onGranted: (TranslationSessionGrant) -> Unit, onFailure: (String) -> Unit) {
        scope.launch {
            try {
                val grant = withContext(Dispatchers.IO) { cloud.createTranslationSession() }
                synchronized(lock) { startedAtMillis.putIfAbsent(grant.sessionId, elapsedRealtimeMillis()) }
                onGranted(grant)
            } catch (error: Exception) {
                onFailure(error.message ?: "无法创建云端翻译会话。")
            }
        }
    }

    /** Reports aggregate wall-clock audio duration and ends the session without blocking the caller. */
    fun end(sessionId: String?) {
        if (sessionId == null) return
        val usage = synchronized(lock) {
            val startedAt = startedAtMillis.remove(sessionId) ?: return
            UsageRecordPayload(
                sessionId = sessionId,
                audioSeconds = max(0L, (elapsedRealtimeMillis() - startedAt) / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            )
        }
        scope.launch {
            // Each operation is isolated: telemetry cannot delay or prevent remote session termination.
            launch { runCatching { withContext(Dispatchers.IO) { cloud.createUsageRecord(usage) } } }
            runCatching { withContext(Dispatchers.IO) { cloud.endTranslationSession(sessionId) } }
        }
    }
}
