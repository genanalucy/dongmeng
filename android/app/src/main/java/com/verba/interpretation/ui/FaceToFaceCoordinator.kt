package com.verba.interpretation.ui

import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.protocol.TranslationSessionEndReason

enum class FaceToFaceMode { MANUAL, AUTO }
enum class FaceToFaceSide { LEFT, RIGHT }
enum class FaceToFacePhase { IDLE, LISTENING, PAUSED, PROCESSING, STOPPING, ERROR }

data class FaceToFaceTurn(
    val id: Long,
    val side: FaceToFaceSide,
    val sourceLanguage: String,
    val targetLanguage: String,
    val route: PlaybackRoute,
    val sourceFinals: List<String> = emptyList(),
    val sourcePartial: String = "",
    val translationFinals: List<String> = emptyList(),
    val translationPartial: String = "",
    val finished: Boolean = false,
) {
    val sourceText: String get() = aggregateSubtitle(sourceFinals, sourcePartial)
    val translatedText: String get() = aggregateSubtitle(translationFinals, translationPartial)
    val hasSourceText: Boolean get() = sourceText.isNotBlank()

    fun withSubtitle(kind: SubtitleKind, text: String): FaceToFaceTurn = when (kind) {
        SubtitleKind.SOURCE_PARTIAL -> copy(sourcePartial = text)
        SubtitleKind.SOURCE_FINAL -> copy(sourceFinals = sourceFinals + text, sourcePartial = "")
        SubtitleKind.TRANSLATION_PARTIAL -> copy(translationPartial = text)
        SubtitleKind.TRANSLATION_FINAL -> copy(translationFinals = translationFinals + text, translationPartial = "")
    }

    fun completedOnly(): FaceToFaceTurn? {
        val completedCount = minOf(sourceFinals.size, translationFinals.size)
        if (completedCount == 0) return null
        return copy(
            sourceFinals = sourceFinals.take(completedCount),
            sourcePartial = "",
            translationFinals = translationFinals.take(completedCount),
            translationPartial = "",
            finished = true,
        )
    }
}

data class FaceToFaceState(
    val mode: FaceToFaceMode = FaceToFaceMode.MANUAL,
    val phase: FaceToFacePhase = FaceToFacePhase.IDLE,
    val leftLanguage: String = "zh",
    val rightLanguage: String = "en",
    val activeSide: FaceToFaceSide? = null,
    val captureActive: Boolean = false,
    val captureLevel: Float = 0f,
    val turns: List<FaceToFaceTurn> = emptyList(),
    val error: String? = null,
    val sessionEndReason: TranslationSessionEndReason? = null,
) {
    val manualInputLocked: Boolean
        get() = mode == FaceToFaceMode.MANUAL && (captureActive || phase == FaceToFacePhase.PROCESSING)
}

/** Pure, synchronized state machine shared by UI, socket, capture, timer and playback threads. */
class FaceToFaceCoordinator<S> {
    data class TimerIntent(val turnId: Long, val delayMillis: Long)

    data class Transition<S>(
        val accepted: Boolean,
        val finishSessions: List<S> = emptyList(),
        val cancelSessions: List<S> = emptyList(),
        val startCapture: Boolean = false,
        val stopCapture: Boolean = false,
        val timer: TimerIntent? = null,
        val cancelTimer: Boolean = false,
        val closeCloudSession: Boolean = false,
    )

    sealed interface PlaybackWork {
        val turnId: Long
        data class Chunk(override val turnId: Long, val pcm: ByteArray, val route: PlaybackRoute) : PlaybackWork
        data class Drain(override val turnId: Long) : PlaybackWork
    }

    private data class Entry<S>(
        val session: S,
        val route: PlaybackRoute,
        val tts: ArrayDeque<ByteArray> = ArrayDeque(),
        var sessionFinished: Boolean = false,
    )

    private val entries = linkedMapOf<Long, Entry<S>>()
    private var current = FaceToFaceState()
    private var activeTurnId: Long? = null
    private var playbackInProgress = false

    @Synchronized
    fun state(): FaceToFaceState = current

    @Synchronized
    fun setMode(mode: FaceToFaceMode): Boolean {
        if (current.phase != FaceToFacePhase.IDLE || entries.isNotEmpty()) return false
        current = current.copy(mode = mode, error = null, sessionEndReason = null)
        return true
    }

    @Synchronized
    fun setLanguages(leftLanguage: String, rightLanguage: String): Boolean {
        if (current.phase != FaceToFacePhase.IDLE || entries.isNotEmpty() || !supportsTranslationPair(leftLanguage, rightLanguage)) return false
        current = current.copy(leftLanguage = leftLanguage, rightLanguage = rightLanguage, error = null, sessionEndReason = null)
        return true
    }

    @Synchronized
    fun manualPress(turnId: Long, side: FaceToFaceSide, session: S): Transition<S> {
        if (current.mode != FaceToFaceMode.MANUAL || current.phase != FaceToFacePhase.IDLE || entries.isNotEmpty()) {
            return Transition(accepted = false, cancelSessions = listOf(session))
        }
        addTurnLocked(turnId, side, session)
        activeTurnId = turnId
        current = current.copy(phase = FaceToFacePhase.LISTENING, activeSide = side, captureActive = true, error = null)
        return Transition(
            accepted = true,
            startCapture = true,
            timer = TimerIntent(turnId, MANUAL_LIMIT_MILLIS),
        )
    }

    @Synchronized
    fun endManualInput(turnId: Long? = activeTurnId): Transition<S> {
        if (current.mode != FaceToFaceMode.MANUAL || current.phase != FaceToFacePhase.LISTENING || activeTurnId != turnId) {
            return Transition(accepted = false)
        }
        val activeId = turnId ?: return Transition(accepted = false)
        val session = entries[activeId]?.session ?: return Transition(accepted = false)
        val discard = shouldDiscardTurnLocked(activeId)
        if (discard) entries.remove(activeId)
        activeTurnId = null
        current = current.copy(
            phase = if (discard) FaceToFacePhase.IDLE else FaceToFacePhase.PROCESSING,
            activeSide = null,
            captureActive = false,
            captureLevel = 0f,
            turns = if (discard) current.turns.filterNot { it.id == activeId } else current.turns,
        )
        return Transition(
            accepted = true,
            finishSessions = if (discard) emptyList() else listOf(session),
            cancelSessions = if (discard) listOf(session) else emptyList(),
            stopCapture = true,
            cancelTimer = true,
            closeCloudSession = discard,
        )
    }

    @Synchronized
    fun startAuto(turnId: Long, session: S): Transition<S> {
        if (current.mode != FaceToFaceMode.AUTO || current.phase != FaceToFacePhase.IDLE || entries.isNotEmpty()) {
            return Transition(accepted = false, cancelSessions = listOf(session))
        }
        addTurnLocked(turnId, FaceToFaceSide.LEFT, session)
        activeTurnId = turnId
        current = current.copy(phase = FaceToFacePhase.LISTENING, activeSide = FaceToFaceSide.LEFT, captureActive = true, error = null)
        return Transition(accepted = true, startCapture = true)
    }

    @Synchronized
    fun switchAuto(turnId: Long, side: FaceToFaceSide, session: S): Transition<S> {
        if (current.mode != FaceToFaceMode.AUTO || current.phase != FaceToFacePhase.LISTENING || !current.captureActive || current.activeSide == side) {
            return Transition(accepted = false, cancelSessions = listOf(session))
        }
        return replaceAutoTurnLocked(turnId, side, session)
    }

    @Synchronized
    fun pauseAuto(): Transition<S> {
        if (current.mode != FaceToFaceMode.AUTO || current.phase != FaceToFacePhase.LISTENING) return Transition(accepted = false)
        val turnId = activeTurnId
        val session = turnId?.let { entries[it]?.session }
        val discard = turnId != null && shouldDiscardTurnLocked(turnId)
        if (discard && turnId != null) entries.remove(turnId)
        activeTurnId = null
        current = current.copy(
            phase = FaceToFacePhase.PAUSED,
            activeSide = null,
            captureActive = false,
            captureLevel = 0f,
            turns = if (discard) current.turns.filterNot { it.id == turnId } else current.turns,
        )
        return Transition(accepted = true, finishSessions = if (discard) emptyList() else listOfNotNull(session), cancelSessions = if (discard) listOfNotNull(session) else emptyList(), stopCapture = true)
    }

    @Synchronized
    fun resumeAuto(turnId: Long, session: S): Transition<S> {
        if (current.mode != FaceToFaceMode.AUTO || current.phase != FaceToFacePhase.PAUSED) {
            return Transition(accepted = false, cancelSessions = listOf(session))
        }
        addTurnLocked(turnId, FaceToFaceSide.LEFT, session)
        activeTurnId = turnId
        current = current.copy(phase = FaceToFacePhase.LISTENING, activeSide = FaceToFaceSide.LEFT, captureActive = true, captureLevel = 0f)
        return Transition(accepted = true, startCapture = true)
    }

    @Synchronized
    fun stopAuto(): Transition<S> {
        if (current.mode != FaceToFaceMode.AUTO || (current.phase != FaceToFacePhase.LISTENING && current.phase != FaceToFacePhase.PAUSED)) return Transition(accepted = false)
        val turnId = activeTurnId
        val session = turnId?.let { entries[it]?.session }
        val discard = turnId != null && shouldDiscardTurnLocked(turnId)
        if (discard && turnId != null) entries.remove(turnId)
        activeTurnId = null
        current = current.copy(
            phase = FaceToFacePhase.STOPPING,
            activeSide = null,
            captureActive = false,
            captureLevel = 0f,
            turns = if (discard) current.turns.filterNot { it.id == turnId } else current.turns,
        )
        settleIfDrainedLocked()
        return Transition(
            accepted = true,
            finishSessions = if (discard) emptyList() else listOfNotNull(session),
            cancelSessions = if (discard) listOfNotNull(session) else emptyList(),
            stopCapture = true,
            cancelTimer = true,
            closeCloudSession = discard,
        )
    }

    @Synchronized
    fun sendToActive(send: (S) -> Boolean): Boolean {
        val session = activeTurnId?.let { entries[it]?.session } ?: return false
        return send(session)
    }

    /** True only when a requested automatic stop has reached terminal turn and playback drain. */
    @Synchronized
    fun canCloseCloudSession(): Boolean =
        current.phase == FaceToFacePhase.IDLE && entries.isEmpty() && !playbackInProgress

    @Synchronized
    fun containsTurn(turnId: Long): Boolean = entries.containsKey(turnId)

    @Synchronized
    fun isActiveTurn(turnId: Long): Boolean = activeTurnId == turnId

    @Synchronized
    fun updateCaptureLevel(level: Float): Boolean {
        if (!current.captureActive) return false
        val smoothed = (current.captureLevel * 0.72f + level * 0.28f).coerceIn(0f, 1f)
        current = current.copy(captureLevel = smoothed)
        return true
    }

    @Synchronized
    fun updateSubtitle(turnId: Long, kind: SubtitleKind, text: String): Boolean {
        if (!entries.containsKey(turnId)) return false
        current = current.copy(turns = current.turns.map { if (it.id == turnId) it.withSubtitle(kind, text) else it })
        return true
    }

    @Synchronized
    fun offerTts(turnId: Long, pcm: ByteArray): PlaybackWork? {
        val entry = entries[turnId] ?: return null
        if (entry.sessionFinished) return null
        entry.tts.addLast(pcm.copyOf())
        return claimPlaybackLocked()
    }

    @Synchronized
    fun sessionFinished(turnId: Long): PlaybackWork? {
        val entry = entries[turnId] ?: return null
        entry.sessionFinished = true
        current = current.copy(turns = current.turns.map { if (it.id == turnId) it.copy(finished = true) else it })
        return claimPlaybackLocked()
    }

    @Synchronized
    fun playbackWorkFinished(turnId: Long, drained: Boolean): PlaybackWork? {
        if (!playbackInProgress) return null
        val firstId = entries.entries.firstOrNull()?.key
        if (firstId != turnId) return null
        playbackInProgress = false
        if (drained) {
            val first = entries[turnId]
            if (first?.sessionFinished == true && first.tts.isEmpty()) entries.remove(turnId)
        }
        settleIfDrainedLocked()
        return claimPlaybackLocked()
    }

    @Synchronized
    fun terminateAll(reason: TranslationSessionEndReason): Transition<S> {
        if (current.sessionEndReason != null) return Transition(accepted = false)
        val sessions = entries.values.map { it.session }
        entries.clear()
        activeTurnId = null
        playbackInProgress = false
        current = current.copy(
            phase = FaceToFacePhase.ERROR,
            activeSide = null,
            captureActive = false,
            captureLevel = 0f,
            turns = current.turns.mapNotNull(FaceToFaceTurn::completedOnly),
            error = null,
            sessionEndReason = reason,
        )
        return Transition(accepted = true, cancelSessions = sessions, stopCapture = true, cancelTimer = true)
    }

    @Synchronized
    fun cancelAll(error: String? = null): Transition<S> {
        val sessions = entries.values.map { it.session }
        entries.clear()
        activeTurnId = null
        playbackInProgress = false
        current = current.copy(
            phase = if (error == null) FaceToFacePhase.IDLE else FaceToFacePhase.ERROR,
            activeSide = null,
            captureActive = false,
            captureLevel = 0f,
            error = error,
            sessionEndReason = null,
        )
        return Transition(accepted = true, cancelSessions = sessions, stopCapture = true, cancelTimer = true)
    }

    @Synchronized
    fun clearError() {
        if (current.phase == FaceToFacePhase.ERROR) {
            current = current.copy(phase = FaceToFacePhase.IDLE, error = null, sessionEndReason = null)
        }
    }

    private fun replaceAutoTurnLocked(turnId: Long, side: FaceToFaceSide, session: S): Transition<S> {
        val previousId = activeTurnId
        val previous = previousId?.let { entries[it]?.session }
        val discard = previousId != null && shouldDiscardTurnLocked(previousId)
        if (discard && previousId != null) entries.remove(previousId)
        addTurnLocked(turnId, side, session)
        activeTurnId = turnId
        current = current.copy(
            activeSide = side,
            captureLevel = 0f,
            turns = if (discard) current.turns.filterNot { it.id == previousId } else current.turns,
        )
        return Transition(
            accepted = true,
            finishSessions = if (discard) emptyList() else listOfNotNull(previous),
            cancelSessions = if (discard) listOfNotNull(previous) else emptyList(),
            cancelTimer = true,
        )
    }

    private fun shouldDiscardTurnLocked(turnId: Long): Boolean = current.turns.firstOrNull { it.id == turnId }?.hasSourceText == false

    private fun addTurnLocked(turnId: Long, side: FaceToFaceSide, session: S) {
        check(!entries.containsKey(turnId)) { "Turn $turnId already exists." }
        val source = if (side == FaceToFaceSide.LEFT) current.leftLanguage else current.rightLanguage
        val target = if (side == FaceToFaceSide.LEFT) current.rightLanguage else current.leftLanguage
        val route = if (side == FaceToFaceSide.LEFT) PlaybackRoute.RIGHT else PlaybackRoute.LEFT
        entries[turnId] = Entry(session, route)
        current = current.copy(turns = current.turns + FaceToFaceTurn(turnId, side, source, target, route))
    }

    private fun claimPlaybackLocked(): PlaybackWork? {
        if (playbackInProgress) return null
        val (turnId, entry) = entries.entries.firstOrNull() ?: run {
            settleIfDrainedLocked()
            return null
        }
        if (entry.tts.isNotEmpty()) {
            playbackInProgress = true
            return PlaybackWork.Chunk(turnId, entry.tts.removeFirst(), entry.route)
        }
        if (entry.sessionFinished) {
            playbackInProgress = true
            return PlaybackWork.Drain(turnId)
        }
        return null
    }

    private fun settleIfDrainedLocked() {
        if (entries.isNotEmpty() || playbackInProgress) return
        if (current.phase == FaceToFacePhase.PROCESSING || current.phase == FaceToFacePhase.STOPPING) {
            current = current.copy(phase = FaceToFacePhase.IDLE, activeSide = null, captureActive = false, captureLevel = 0f)
        }
    }

    companion object {
        const val MANUAL_LIMIT_MILLIS = 25_000L
    }
}
