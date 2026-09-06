package com.verba.interpretation.ui

/** The user intent that must survive the runtime permission dialog. */
sealed interface MicrophonePermissionAction {
    data class Manual(val side: FaceToFaceSide) : MicrophonePermissionAction
    data object Continuous : MicrophonePermissionAction
}

/**
 * Small, UI-independent state machine for one RECORD_AUDIO request at a time.
 * A result consumes the pending action exactly once; clear invalidates it.
 */
internal class MicrophonePermissionPolicy {
    data class Result(val action: MicrophonePermissionAction, val granted: Boolean)

    private var pending: MicrophonePermissionAction? = null

    fun request(action: MicrophonePermissionAction): Boolean {
        if (pending != null) return false
        pending = action
        return true
    }

    fun consumeResult(granted: Boolean): Result? {
        val action = pending ?: return null
        pending = null
        return Result(action, granted)
    }

    fun clear() {
        pending = null
    }

    fun hasPendingRequest(): Boolean = pending != null
}
