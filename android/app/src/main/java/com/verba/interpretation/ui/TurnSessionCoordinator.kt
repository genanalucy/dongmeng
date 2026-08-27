package com.verba.interpretation.ui

/**
 * Owns Turn ordering and lifecycle without depending on Android or transport classes.
 * All methods are synchronized because socket callbacks and microphone capture run on different threads.
 */
class TurnSessionCoordinator<S> {
    data class Playback(val turnId: Long, val pcm: ByteArray)
    data class Activation<S>(val previous: S?, val ready: Boolean)

    private data class Entry<S>(
        val session: S,
        val tts: ArrayDeque<ByteArray> = ArrayDeque(),
        var ready: Boolean = false,
        var finished: Boolean = false,
    )

    private val sessions = linkedMapOf<Long, Entry<S>>()
    private var activeTurnId: Long? = null
    private var playbackInProgress = false
    private var stopping = false

    @Synchronized
    fun add(turnId: Long, session: S) {
        check(!sessions.containsKey(turnId)) { "Turn $turnId already exists." }
        sessions[turnId] = Entry(session)
    }

    @Synchronized
    fun activate(turnId: Long): Activation<S>? {
        val entry = sessions[turnId] ?: return null
        val previous = activeTurnId?.let { sessions[it]?.session }
        activeTurnId = turnId
        return Activation(previous, entry.ready)
    }

    @Synchronized
    fun markReady(turnId: Long): Boolean {
        val entry = sessions[turnId] ?: return false
        entry.ready = true
        return activeTurnId == turnId
    }

    @Synchronized
    fun isActive(turnId: Long): Boolean = activeTurnId == turnId

    @Synchronized
    fun sendToActive(send: (S) -> Boolean): Boolean {
        val active = activeTurnId?.let { sessions[it]?.session } ?: return false
        return send(active)
    }

    @Synchronized
    fun pauseAndFinishSessions(): List<S> {
        activeTurnId = null
        return sessions.values.map { it.session }
    }

    @Synchronized
    fun stopAndFinishSessions(): List<S> {
        stopping = true
        activeTurnId = null
        return sessions.values.map { it.session }
    }

    @Synchronized
    fun offerTts(turnId: Long, pcm: ByteArray): Playback? {
        val entry = sessions[turnId] ?: return null
        if (entry.finished) return null
        entry.tts.addLast(pcm.copyOf())
        return claimPlaybackLocked()
    }

    @Synchronized
    fun sessionFinished(turnId: Long): Playback? {
        sessions[turnId]?.finished = true
        return claimPlaybackLocked()
    }

    @Synchronized
    fun playbackFinished(): Playback? {
        if (!playbackInProgress) return null // cancelAll may race a blocking platform write.
        playbackInProgress = false
        return claimPlaybackLocked()
    }

    @Synchronized
    fun cancelAll(): List<S> {
        val result = sessions.values.map { it.session }
        sessions.clear()
        activeTurnId = null
        playbackInProgress = false
        stopping = false
        return result
    }

    @Synchronized
    fun canBecomeIdle(): Boolean = stopping && sessions.isEmpty() && !playbackInProgress

    @Synchronized
    fun sessionCount(): Int = sessions.size

    private fun claimPlaybackLocked(): Playback? {
        if (playbackInProgress) return null
        while (sessions.isNotEmpty()) {
            val (turnId, entry) = sessions.entries.first()
            if (entry.tts.isNotEmpty()) {
                playbackInProgress = true
                return Playback(turnId, entry.tts.removeFirst())
            }
            if (!entry.finished) return null
            sessions.remove(turnId)
        }
        return null
    }
}
