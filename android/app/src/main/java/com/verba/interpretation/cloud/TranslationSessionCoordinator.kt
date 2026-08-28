package com.verba.interpretation.cloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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
    private data class SessionState(
        val startedAtMillis: Long,
        var usage: UsageRecordPayload? = null,
        var usageStarted: Boolean = false,
        var endInFlight: Boolean = false,
    )

    interface OpenHandle {
        fun cancel()
    }

    private val lock = Any()
    private val operationScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + SupervisorJob())
    private val sessions = mutableMapOf<String, SessionState>()

    /**
     * Starts an acquisition that may be cancelled independently from the owner scope. A grant that
     * arrives after cancellation is registered only long enough to use the normal end path.
     */
    fun open(onGranted: (TranslationSessionGrant) -> Unit, onFailure: (String) -> Unit): OpenHandle {
        val handle = object : OpenHandle {
            private var cancelled = false

            override fun cancel() = synchronized(lock) { cancelled = true }

            fun isCancelled(): Boolean = synchronized(lock) { cancelled }
        }
        operationScope.launch {
            try {
                val grant = withContext(NonCancellable + Dispatchers.IO) { cloud.createTranslationSession() }
                val cancelled = handle.isCancelled()
                register(grant.sessionId)
                if (cancelled) {
                    end(grant.sessionId)
                } else {
                    onGranted(grant)
                }
            } catch (error: Exception) {
                if (!handle.isCancelled()) onFailure(error.message ?: "无法创建云端翻译会话。")
            }
        }
        return handle
    }

    /**
     * Reports usage once and attempts one serialized remote end. A failed transport attempt retains
     * eligibility, so a later explicit call retries it; concurrent calls never overlap.
     */
    fun end(sessionId: String?) {
        if (sessionId == null) return
        val session = synchronized(lock) {
            val current = sessions[sessionId] ?: return
            if (current.endInFlight) return
            current.endInFlight = true
            if (current.usage == null) {
                current.usage = UsageRecordPayload(
                    sessionId = sessionId,
                    audioSeconds = max(0L, (elapsedRealtimeMillis() - current.startedAtMillis) / 1_000L)
                        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                )
            }
            current
        }
        operationScope.launch {
            val usage = synchronized(lock) {
                if (session.usageStarted) null else {
                    session.usageStarted = true
                    checkNotNull(session.usage)
                }
            }
            if (usage != null) launch { runCatching { cloud.createUsageRecord(usage) } }

            val ended = runCatching { cloud.endTranslationSession(sessionId) }.isSuccess
            synchronized(lock) {
                if (ended) sessions.remove(sessionId) else session.endInFlight = false
            }
        }
    }

    private fun register(sessionId: String) = synchronized(lock) {
        sessions.putIfAbsent(sessionId, SessionState(startedAtMillis = elapsedRealtimeMillis()))
    }

}
