package com.verba.interpretation.ui

import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.history.LocalHistorySaveState
import com.verba.interpretation.protocol.TranslationSessionEndReason

enum class FaceToFaceMode { MANUAL, AUTO }
enum class FaceToFaceView { CONVERSATION, FACE_TO_FACE }
enum class FaceToFaceSide { LEFT, RIGHT }

/** Playback stays on the opposite physical side, regardless of presentation orientation. */
fun faceToFacePlaybackRoute(side: FaceToFaceSide): PlaybackRoute = when (side) {
    FaceToFaceSide.LEFT -> PlaybackRoute.RIGHT
    FaceToFaceSide.RIGHT -> PlaybackRoute.LEFT
}
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
    val view: FaceToFaceView = FaceToFaceView.CONVERSATION,
    val mode: FaceToFaceMode = FaceToFaceMode.MANUAL,
    val phase: FaceToFacePhase = FaceToFacePhase.IDLE,
    val leftLanguage: String = "zh",
    val rightLanguage: String = "en",
    val activeSide: FaceToFaceSide? = null,
    val activeTurnId: Long? = null,
    val captureActive: Boolean = false,
    val captureLevel: Float = 0f,
    /** Azure continuous LID owns side selection; manual right-side takeover is unavailable. */
    val automaticLanguageDetection: Boolean = false,
    val turns: List<FaceToFaceTurn> = emptyList(),
    val error: String? = null,
    val sessionEndReason: TranslationSessionEndReason? = null,
    val localHistorySave: LocalHistorySaveState = LocalHistorySaveState(),
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

    /** A transport owns exactly one socket; logical turns merely reference it. */
    private data class Transport<S>(
        val session: S,
        var finishing: Boolean = false,
        var finished: Boolean = false,
    )

    private data class Entry<S>(
        val transport: Transport<S>,
        var route: PlaybackRoute,
        var autoDirectionResolved: Boolean = true,
        var continuousAutoSegment: Boolean = false,
        var logicalComplete: Boolean = false,
        val tts: ArrayDeque<ByteArray> = ArrayDeque(),
    )

    private val entries = linkedMapOf<Long, Entry<S>>()
    private val transports = linkedMapOf<S, Transport<S>>()
    private var continuousTransport: Transport<S>? = null
    private var current = FaceToFaceState()
    private var activeTurnId: Long? = null
    private var playbackInProgress = false

    @Synchronized
    fun state(): FaceToFaceState = current.copy(activeTurnId = activeTurnId)

    @Synchronized
    fun setMode(mode: FaceToFaceMode): Boolean {
        if (current.phase != FaceToFacePhase.IDLE || entries.isNotEmpty()) return false
        current = current.copy(mode = mode, error = null, sessionEndReason = null)
        return true
    }

    /** Changes presentation only after active capture has been ended safely. */
    @Synchronized
    fun setView(view: FaceToFaceView): Transition<S> {
        if (current.view == view) return Transition(accepted = false)
        val transition = when {
            current.phase == FaceToFacePhase.LISTENING && current.mode == FaceToFaceMode.MANUAL -> endManualInput()
            current.phase == FaceToFacePhase.LISTENING && current.mode == FaceToFaceMode.AUTO -> stopAuto()
            current.phase == FaceToFacePhase.PROCESSING || current.phase == FaceToFacePhase.STOPPING ->
                return Transition(accepted = false)
            else -> Transition(accepted = true)
        }
        if (!transition.accepted) return transition
        current = current.copy(view = view)
        return transition
    }

    @Synchronized
    fun setAutomaticLanguageDetection(enabled: Boolean): Boolean {
        if (current.phase != FaceToFacePhase.IDLE || entries.isNotEmpty()) return false
        current = current.copy(automaticLanguageDetection = enabled)
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
        // A released manual turn may still translate or drain TTS. Like AUTO takeovers,
        // keep that entry alive while accepting the next press immediately.
        val canStart = current.mode == FaceToFaceMode.MANUAL &&
            current.phase in setOf(FaceToFacePhase.IDLE, FaceToFacePhase.PROCESSING) &&
            !current.captureActive && activeTurnId == null
        if (!canStart) {
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
        val entry = entries[activeId] ?: return Transition(accepted = false)
        val turn = current.turns.firstOrNull { it.id == activeId } ?: return Transition(accepted = false)
        // Finished is terminal at the socket. Keep the entry so queued TTS can drain; the
        // release must not send a second finish or discard the terminal session.
        val terminal = entry.transport.finishing || entry.transport.finished || turn.finished
        val discard = !terminal && shouldDiscardTurnLocked(activeId)
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
            finishSessions = if (discard || terminal) emptyList() else finishTransportLocked(entry.transport),
            cancelSessions = if (discard) listOf(entry.transport.session) else emptyList(),
            stopCapture = true,
            cancelTimer = true,
            closeCloudSession = discard,
        )
    }

    /** Cancels only the currently pressed manual turn; completed history turns remain intact. */
    @Synchronized
    fun cancelManualInput(turnId: Long? = activeTurnId): Transition<S> {
        if (current.mode != FaceToFaceMode.MANUAL || current.phase != FaceToFacePhase.LISTENING || activeTurnId != turnId) {
            return Transition(accepted = false)
        }
        val activeId = turnId ?: return Transition(accepted = false)
        val entry = entries[activeId] ?: return Transition(accepted = false)
        val turn = current.turns.firstOrNull { it.id == activeId } ?: return Transition(accepted = false)
        if (turn.finished) {
            // Finished already arrived at the socket. Do not finish/cancel it again: playback
            // owns the normal drain and will remove the entry and close the cloud session.
            activeTurnId = null
            current = current.copy(
                phase = FaceToFacePhase.PROCESSING,
                activeSide = null,
                captureActive = false,
                captureLevel = 0f,
            )
            return Transition(accepted = true, stopCapture = true, cancelTimer = true)
        }
        entries.remove(activeId)
        activeTurnId = null
        current = current.copy(
            phase = FaceToFacePhase.IDLE,
            activeSide = null,
            captureActive = false,
            captureLevel = 0f,
            turns = current.turns.filterNot { it.id == activeId },
        )
        return Transition(
            accepted = true,
            cancelSessions = listOf(entry.transport.session),
            stopCapture = true,
            cancelTimer = true,
            closeCloudSession = entries.isEmpty(),
        )
    }

    @Synchronized
    fun startAuto(
        turnId: Long,
        session: S,
        requiresDetection: Boolean = false,
        continuousSession: Boolean = false,
    ): Transition<S> {
        if (current.mode != FaceToFaceMode.AUTO || current.phase != FaceToFacePhase.IDLE || entries.isNotEmpty()) {
            return Transition(accepted = false, cancelSessions = listOf(session))
        }
        return beginAutoSegmentLocked(turnId, session, requiresDetection, continuousSession)
    }

    /** Stops capture at a complete final pair, before the socket can receive another speaker. */
    @Synchronized
    fun completeAutoSegmentAfterFinalPair(turnId: Long): Transition<S> {
        val entry = entries[turnId] ?: return Transition(accepted = false)
        val turn = current.turns.firstOrNull { it.id == turnId } ?: return Transition(accepted = false)
        if (current.mode != FaceToFaceMode.AUTO || activeTurnId != turnId || !current.captureActive ||
            entry.transport.finishing || entry.transport.finished || turn.sourceFinals.isEmpty() || turn.translationFinals.isEmpty()
        ) return Transition(accepted = false)
        if (entry.continuousAutoSegment) {
            // Final text may precede synchronous TTS synthesis by an unbounded
            // interval. Keep capture running until the server's tts_start
            // prelude confirms that feedback-producing PCM is imminent.
            return Transition(accepted = true)
        }
        activeTurnId = null
        current = current.copy(
            phase = FaceToFacePhase.PROCESSING,
            activeSide = null,
            captureActive = false,
            captureLevel = 0f,
        )
        return Transition(accepted = true, finishSessions = finishTransportLocked(entry.transport), stopCapture = true)
    }

    /**
     * Stops continuous capture only at the validated TTS prelude. Duplicate or stale preludes
     * are no-ops, so a delayed metadata frame cannot stop a newly resumed segment.
     */
    @Synchronized
    fun beginContinuousTtsPlayback(turnId: Long): Transition<S> {
        val entry = entries[turnId] ?: return Transition(accepted = false)
        val turn = current.turns.firstOrNull { it.id == turnId } ?: return Transition(accepted = false)
        if (!entry.continuousAutoSegment || !entry.autoDirectionResolved || activeTurnId != turnId || !current.captureActive ||
            entry.transport.finishing || entry.transport.finished || turn.sourceFinals.isEmpty() || turn.translationFinals.isEmpty()
        ) return Transition(accepted = false)
        entry.logicalComplete = true
        activeTurnId = null
        current = current.copy(
            phase = FaceToFacePhase.PROCESSING,
            activeSide = null,
            captureActive = false,
            captureLevel = 0f,
            turns = current.turns.map { if (it.id == turnId) it.copy(finished = true) else it },
        )
        return Transition(accepted = true, stopCapture = true)
    }

    /** Installs a new logical Azure segment without opening another transport socket. */
    @Synchronized
    fun beginContinuousAutoSegment(turnId: Long, session: S): Boolean {
        val transport = continuousTransport
        if (current.mode != FaceToFaceMode.AUTO || !current.captureActive || activeTurnId != null || entries.containsKey(turnId) ||
            transport == null || transport.session != session || transport.finishing || transport.finished
        ) return false
        addTurnLocked(turnId, FaceToFaceSide.LEFT, session, requiresDetection = true, continuousAutoSegment = true)
        activeTurnId = turnId
        return true
    }

    @Synchronized
    fun switchAuto(turnId: Long, side: FaceToFaceSide, session: S): Transition<S> {
        if (current.mode != FaceToFaceMode.AUTO || current.phase != FaceToFacePhase.LISTENING || !current.captureActive || current.activeSide == side) {
            return Transition(accepted = false, cancelSessions = listOf(session))
        }
        return replaceAutoTurnLocked(turnId, side, session, discardPrevious = false)
    }

    /** Cancels a temporary right-side takeover and immediately opens one fresh left turn. */
    @Synchronized
    fun cancelAutoTakeover(turnId: Long, session: S): Transition<S> {
        if (current.mode != FaceToFaceMode.AUTO || current.phase != FaceToFacePhase.LISTENING ||
            !current.captureActive || current.activeSide != FaceToFaceSide.RIGHT
        ) return Transition(accepted = false, cancelSessions = listOf(session))
        // Validate before mutating either the transcript or the playback queue.
        if (current.turns.any { it.id == turnId }) return Transition(accepted = false, cancelSessions = listOf(session))
        val previousId = activeTurnId ?: return Transition(accepted = false, cancelSessions = listOf(session))
        val previousTurn = current.turns.firstOrNull { it.id == previousId }
            ?: return Transition(accepted = false, cancelSessions = listOf(session))
        val previousEntry = entries[previousId]
        // A terminal Finished event belongs to playback drain; never cancel that socket here.
        // The entry may already have drained and been removed while activeTurnId/side still
        // points at RIGHT. The finished turn in the transcript is the durable terminal marker.
        val preserveFinished = previousEntry?.transport?.finished == true || previousTurn.finished
        if (!preserveFinished) {
            if (previousEntry == null) return Transition(accepted = false, cancelSessions = listOf(session))
            entries.remove(previousId)
            current = current.copy(turns = current.turns.filterNot { it.id == previousId })
        }
        addTurnLocked(turnId, FaceToFaceSide.LEFT, session)
        activeTurnId = turnId
        current = current.copy(activeSide = FaceToFaceSide.LEFT, captureLevel = 0f)
        return Transition(
            accepted = true,
            cancelSessions = if (preserveFinished) emptyList() else listOfNotNull(previousEntry?.transport?.session),
            cancelTimer = true,
        )
    }

    @Synchronized
    fun pauseAuto(): Transition<S> {
        if (current.mode != FaceToFaceMode.AUTO || current.phase !in setOf(FaceToFacePhase.LISTENING, FaceToFacePhase.PROCESSING)) return Transition(accepted = false)
        val activeContinuousTransport = continuousTransport
        if (activeContinuousTransport != null && !activeContinuousTransport.finished) {
            activeTurnId = null
            current = current.copy(phase = FaceToFacePhase.PAUSED, activeSide = null, captureActive = false, captureLevel = 0f)
            return Transition(accepted = true, finishSessions = finishTransportLocked(activeContinuousTransport), stopCapture = true)
        }
        val turnId = activeTurnId
        val entry = turnId?.let { entries[it] }
        val turn = turnId?.let { id -> current.turns.firstOrNull { it.id == id } }
        val terminal = entry?.transport?.finishing == true || entry?.transport?.finished == true || turn?.finished == true
        val discard = turnId != null && !terminal && shouldDiscardTurnLocked(turnId)
        if (discard) entries.remove(turnId)
        activeTurnId = null
        current = current.copy(
            phase = FaceToFacePhase.PAUSED,
            activeSide = null,
            captureActive = false,
            captureLevel = 0f,
            turns = if (discard) current.turns.filterNot { it.id == turnId } else current.turns,
        )
        return Transition(
            accepted = true,
            finishSessions = if (discard || terminal || entry == null) emptyList() else finishTransportLocked(entry.transport),
            cancelSessions = if (discard) listOfNotNull(entry?.transport?.session) else emptyList(),
            stopCapture = true,
        )
    }

    @Synchronized
    fun resumeAuto(
        turnId: Long,
        session: S,
        requiresDetection: Boolean = false,
        continuousSession: Boolean = false,
    ): Transition<S> {
        if (current.mode != FaceToFaceMode.AUTO || current.phase != FaceToFacePhase.PAUSED) {
            return Transition(accepted = false, cancelSessions = listOf(session))
        }
        addTurnLocked(turnId, FaceToFaceSide.LEFT, session, requiresDetection, continuousAutoSegment = continuousSession)
        activeTurnId = turnId
        current = current.copy(phase = FaceToFacePhase.LISTENING, activeSide = FaceToFaceSide.LEFT, captureActive = true, captureLevel = 0f)
        return Transition(accepted = true, startCapture = true)
    }

    @Synchronized
    fun stopAuto(): Transition<S> {
        if (current.mode != FaceToFaceMode.AUTO || current.phase !in setOf(FaceToFacePhase.LISTENING, FaceToFacePhase.PAUSED, FaceToFacePhase.PROCESSING)) return Transition(accepted = false)
        val activeContinuousTransport = continuousTransport
        if (activeContinuousTransport != null && !activeContinuousTransport.finished) {
            activeTurnId = null
            current = current.copy(phase = FaceToFacePhase.STOPPING, activeSide = null, captureActive = false, captureLevel = 0f)
            return Transition(accepted = true, finishSessions = finishTransportLocked(activeContinuousTransport), stopCapture = true, cancelTimer = true)
        }
        val turnId = activeTurnId
        val entry = turnId?.let { entries[it] }
        val turn = turnId?.let { id -> current.turns.firstOrNull { it.id == id } }
        val terminal = entry?.transport?.finishing == true || entry?.transport?.finished == true || turn?.finished == true
        val discard = turnId != null && !terminal && shouldDiscardTurnLocked(turnId)
        if (discard) entries.remove(turnId)
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
            finishSessions = if (discard || terminal || entry == null) emptyList() else finishTransportLocked(entry.transport),
            cancelSessions = if (discard) listOfNotNull(entry?.transport?.session) else emptyList(),
            stopCapture = true,
            cancelTimer = true,
            closeCloudSession = entries.isEmpty() && !playbackInProgress,
        )
    }

    @Synchronized
    fun sendToActive(send: (S) -> Boolean): Boolean {
        if (!current.captureActive) return true
        val entry = activeTurnId?.let { entries[it] }
        // Finished can precede the pointer release/cancel. Drop packets until the new turn
        // is installed instead of treating a normally closed socket as a capture failure.
        if (entry?.transport?.finishing == true || entry?.transport?.finished == true || current.turns.any { it.id == activeTurnId && it.finished }) return true
        return entry?.let { send(it.transport.session) } ?: false
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
    fun resolveAutoDirection(turnId: Long, sourceLanguage: String, declaredTargetLanguage: String? = null): Boolean {
        val entry = entries[turnId] ?: return false
        if (current.mode != FaceToFaceMode.AUTO || activeTurnId != turnId || entry.autoDirectionResolved) return false
        val state = current
        val side = when (sourceLanguage) {
            state.leftLanguage -> FaceToFaceSide.LEFT
            state.rightLanguage -> FaceToFaceSide.RIGHT
            else -> {
                entries.remove(turnId)
                activeTurnId = null
                current = state.copy(
                    phase = FaceToFacePhase.IDLE,
                    activeSide = null,
                    captureActive = false,
                    captureLevel = 0f,
                    turns = state.turns.filterNot { it.id == turnId },
                )
                return false
            }
        }
        val target = if (side == FaceToFaceSide.LEFT) state.rightLanguage else state.leftLanguage
        if (declaredTargetLanguage != null && declaredTargetLanguage != target) return false
        entry.route = faceToFacePlaybackRoute(side)
        entry.autoDirectionResolved = true
        current = state.copy(
            activeSide = side,
            turns = state.turns.map { turn ->
                if (turn.id == turnId) turn.copy(
                    side = side,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = target,
                    route = entry.route,
                ) else turn
            },
        )
        return true
    }

    @Synchronized
    fun updateSubtitle(turnId: Long, kind: SubtitleKind, text: String): Boolean {
        val entry = entries[turnId] ?: return false
        if (!entry.autoDirectionResolved) return false
        current = current.copy(turns = current.turns.map { if (it.id == turnId) it.withSubtitle(kind, text) else it })
        return true
    }

    @Synchronized
    fun offerTts(turnId: Long, pcm: ByteArray): PlaybackWork? {
        val entry = entries[turnId] ?: return null
        if (!entry.autoDirectionResolved || (entry.transport.finished && !entry.continuousAutoSegment)) return null
        entry.tts.addLast(pcm.copyOf())
        return claimPlaybackLocked()
    }

    @Synchronized
    fun sessionFinished(turnId: Long): PlaybackWork? {
        val entry = entries[turnId] ?: return null
        return transportFinishedLocked(entry.transport)
    }

    @Synchronized
    fun transportFinished(session: S): PlaybackWork? = transports[session]?.let(::transportFinishedLocked)

    private fun transportFinishedLocked(transport: Transport<S>): PlaybackWork? {
        transport.finishing = true
        transport.finished = true
        if (continuousTransport == transport) continuousTransport = null
        current = current.copy(turns = current.turns.map { turn ->
            if (entries[turn.id]?.transport == transport) turn.copy(finished = true) else turn
        })
        return claimPlaybackLocked()
    }

    @Synchronized
    fun playbackWorkFinished(turnId: Long, drained: Boolean): PlaybackWork? {
        if (!playbackInProgress) return null
        val firstId = entries.entries.firstOrNull()?.key
        if (firstId != turnId) return null
        playbackInProgress = false
        val first = entries[turnId]
        if (drained) {
            if (first?.transport?.finished == true && first.tts.isEmpty()) entries.remove(turnId)
            if (first?.continuousAutoSegment == true && first.logicalComplete && !first.transport.finished && first.tts.isEmpty()) {
                return null
            }
        } else if (first?.continuousAutoSegment == true && first.logicalComplete && !first.transport.finished && first.tts.isEmpty()) {
            // The just-played chunk is the final TTS for this logical segment.
            // Do not reopen capture until AudioTrack confirms it rendered.
            playbackInProgress = true
            return PlaybackWork.Drain(turnId)
        }
        settleIfDrainedLocked()
        return claimPlaybackLocked()
    }

    /** Resumes microphone capture only after the completed segment's TTS is audibly drained. */
    @Synchronized
    fun resumeContinuousCaptureAfterDrain(turnId: Long): Transition<S> {
        val entry = entries[turnId] ?: return Transition(accepted = false)
        if (!entry.continuousAutoSegment || !entry.logicalComplete || entry.transport.finished ||
            current.mode != FaceToFaceMode.AUTO || current.captureActive || activeTurnId != null || playbackInProgress
        ) return Transition(accepted = false)
        current = current.copy(
            phase = FaceToFacePhase.LISTENING,
            activeSide = FaceToFaceSide.LEFT,
            captureActive = true,
            captureLevel = 0f,
        )
        return Transition(accepted = true, startCapture = true)
    }

    @Synchronized
    fun terminateAll(reason: TranslationSessionEndReason): Transition<S> {
        if (current.sessionEndReason != null) return Transition(accepted = false)
        val sessions = transports.keys.toList()
        entries.clear()
        transports.clear()
        continuousTransport = null
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
        val sessions = transports.keys.toList()
        entries.clear()
        transports.clear()
        continuousTransport = null
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

    private fun beginAutoSegmentLocked(
        turnId: Long,
        session: S,
        requiresDetection: Boolean,
        continuousSession: Boolean,
    ): Transition<S> {
        addTurnLocked(turnId, FaceToFaceSide.LEFT, session, requiresDetection, continuousAutoSegment = continuousSession)
        activeTurnId = turnId
        current = current.copy(
            phase = FaceToFacePhase.LISTENING,
            activeSide = FaceToFaceSide.LEFT,
            captureActive = true,
            captureLevel = 0f,
            error = null,
        )
        return Transition(accepted = true, startCapture = true)
    }

    private fun replaceAutoTurnLocked(
        turnId: Long,
        side: FaceToFaceSide,
        session: S,
        discardPrevious: Boolean,
    ): Transition<S> {
        val previousId = activeTurnId
        val previousEntry = previousId?.let { entries[it] }
        val previous = previousEntry?.transport?.session
        val previousTurn = previousId?.let { id -> current.turns.firstOrNull { it.id == id } }
        val previousFinished = previousEntry?.transport?.finishing == true || previousEntry?.transport?.finished == true || previousTurn?.finished == true
        // A terminal socket must remain in the playback queue even when it has no source text.
        val discard = !previousFinished && (discardPrevious || (previousId != null && shouldDiscardTurnLocked(previousId)))
        if (previousId != null) {
            if (discard) entries.remove(previousId)
            else current = current.copy(turns = current.turns.map { turn ->
                if (turn.id == previousId) turn.copy(finished = true) else turn
            })
        }
        addTurnLocked(turnId, side, session)
        activeTurnId = turnId
        current = current.copy(
            activeSide = side,
            captureLevel = 0f,
            turns = if (discard) current.turns.filterNot { it.id == previousId } else current.turns,
        )
        return Transition(
            accepted = true,
            finishSessions = if (discard || previousFinished) emptyList() else listOfNotNull(previous),
            cancelSessions = if (discard) listOfNotNull(previous) else emptyList(),
            cancelTimer = true,
        )
    }

    private fun shouldDiscardTurnLocked(turnId: Long): Boolean = current.turns.firstOrNull { it.id == turnId }?.hasSourceText == false

    private fun addTurnLocked(
        turnId: Long,
        side: FaceToFaceSide,
        session: S,
        requiresDetection: Boolean = false,
        continuousAutoSegment: Boolean = false,
    ) {
        check(!entries.containsKey(turnId)) { "Turn $turnId already exists." }
        val source = if (side == FaceToFaceSide.LEFT) current.leftLanguage else current.rightLanguage
        val target = if (side == FaceToFaceSide.LEFT) current.rightLanguage else current.leftLanguage
        val route = faceToFacePlaybackRoute(side)
        val transport = transports.getOrPut(session) { Transport(session) }
        if (continuousAutoSegment) continuousTransport = transport
        entries[turnId] = Entry(transport, route, autoDirectionResolved = !requiresDetection, continuousAutoSegment = continuousAutoSegment)
        current = current.copy(turns = current.turns + FaceToFaceTurn(turnId, side, source, target, route))
    }

    private fun finishTransportLocked(transport: Transport<S>): List<S> {
        if (transport.finishing || transport.finished) return emptyList()
        transport.finishing = true
        return listOf(transport.session)
    }

    private fun claimPlaybackLocked(): PlaybackWork? {
        if (playbackInProgress) return null
        // Preserve FIFO across independent sockets. A completed continuous Azure
        // segment is the sole exception: its live transport may carry a later
        // segment's PCM, so it must not block that logical turn.
        val ready = entries.entries.firstOrNull { (_, entry) ->
            !(entry.continuousAutoSegment && !entry.transport.finished && entry.tts.isEmpty())
        } ?: run {
            settleIfDrainedLocked()
            return null
        }
        val (turnId, entry) = ready
        if (entry.tts.isNotEmpty()) {
            playbackInProgress = true
            return PlaybackWork.Chunk(turnId, entry.tts.removeFirst(), entry.route)
        }
        // Azure synthesis emits one validated PCM event for each logical final.
        // Drain it before reopening capture on the same live transport.
        if (entry.continuousAutoSegment && entry.logicalComplete && !entry.transport.finished) {
            playbackInProgress = true
            return PlaybackWork.Drain(turnId)
        }
        if (!entry.transport.finished) return null
        playbackInProgress = true
        return PlaybackWork.Drain(turnId)
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
