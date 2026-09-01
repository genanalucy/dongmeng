package com.verba.interpretation.ui.facetoface

internal data class ConversationTimelineFollowState(
    val followsLatest: Boolean = true,
    val scrollToLatestRequested: Boolean = false,
)

internal sealed interface ConversationTimelineFollowEvent {
    data class TranscriptAppended(val addedItems: Int) : ConversationTimelineFollowEvent
    data class UserScrollFinished(val atLatest: Boolean) : ConversationTimelineFollowEvent
    data object UserTappedLatest : ConversationTimelineFollowEvent
    data object ScrollRequestStarted : ConversationTimelineFollowEvent
    data object ProgrammaticScrollFinished : ConversationTimelineFollowEvent
}

/** Keeps content changes and programmatic scrolling from being mistaken for user scrolling. */
internal object ConversationTimelineFollowReducer {
    fun reduce(
        state: ConversationTimelineFollowState,
        event: ConversationTimelineFollowEvent,
    ): ConversationTimelineFollowState = when (event) {
        is ConversationTimelineFollowEvent.TranscriptAppended -> {
            if (state.followsLatest) state.copy(scrollToLatestRequested = true) else state
        }
        is ConversationTimelineFollowEvent.UserScrollFinished -> state.copy(
            followsLatest = event.atLatest,
            scrollToLatestRequested = false,
        )
        ConversationTimelineFollowEvent.UserTappedLatest -> ConversationTimelineFollowState(
            followsLatest = true,
            scrollToLatestRequested = true,
        )
        ConversationTimelineFollowEvent.ScrollRequestStarted,
        ConversationTimelineFollowEvent.ProgrammaticScrollFinished,
        -> state.copy(scrollToLatestRequested = false)
    }
}
