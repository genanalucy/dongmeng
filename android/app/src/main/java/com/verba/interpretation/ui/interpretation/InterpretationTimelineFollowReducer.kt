package com.verba.interpretation.ui.interpretation

internal data class InterpretationTimelineFollowState(
    val followsLatest: Boolean = true,
    val scrollToLatestRequested: Boolean = false,
)

internal sealed interface InterpretationTimelineFollowEvent {
    data class TranscriptAppended(val addedItems: Int) : InterpretationTimelineFollowEvent
    data class UserScrollFinished(val atLatest: Boolean) : InterpretationTimelineFollowEvent
    data object UserTappedLatest : InterpretationTimelineFollowEvent
    data object ScrollRequestStarted : InterpretationTimelineFollowEvent
    data object ProgrammaticScrollFinished : InterpretationTimelineFollowEvent
}

/** Keeps data changes separate from actual user scroll input. */
internal object InterpretationTimelineFollowReducer {
    fun reduce(
        state: InterpretationTimelineFollowState,
        event: InterpretationTimelineFollowEvent,
    ): InterpretationTimelineFollowState = when (event) {
        is InterpretationTimelineFollowEvent.TranscriptAppended -> {
            if (state.followsLatest) state.copy(scrollToLatestRequested = true) else state
        }
        is InterpretationTimelineFollowEvent.UserScrollFinished -> state.copy(
            followsLatest = event.atLatest,
            scrollToLatestRequested = false,
        )
        InterpretationTimelineFollowEvent.UserTappedLatest -> InterpretationTimelineFollowState(
            followsLatest = true,
            scrollToLatestRequested = true,
        )
        InterpretationTimelineFollowEvent.ScrollRequestStarted,
        InterpretationTimelineFollowEvent.ProgrammaticScrollFinished,
        -> state.copy(scrollToLatestRequested = false)
    }
}
