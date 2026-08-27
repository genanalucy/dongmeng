package com.verba.interpretation.ui

/** Pure policy for a transcript feed that follows live captions without fighting user scroll. */
data class ChatFollowState(
    val followsLatest: Boolean = true,
    val unseenUpdates: Int = 0,
)

sealed interface ChatFollowEvent {
    data object UserLeftLatest : ChatFollowEvent
    data object UserReachedLatest : ChatFollowEvent
    data class TranscriptChanged(val addedItems: Int) : ChatFollowEvent
}

object ChatFollowPolicy {
    fun reduce(state: ChatFollowState, event: ChatFollowEvent): ChatFollowState = when (event) {
        ChatFollowEvent.UserLeftLatest -> state.copy(followsLatest = false)
        ChatFollowEvent.UserReachedLatest -> ChatFollowState(followsLatest = true)
        is ChatFollowEvent.TranscriptChanged -> {
            if (state.followsLatest) {
                state.copy(unseenUpdates = 0)
            } else {
                state.copy(unseenUpdates = state.unseenUpdates + event.addedItems.coerceAtLeast(1))
            }
        }
    }
}
