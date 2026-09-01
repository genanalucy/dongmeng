package com.verba.interpretation.ui.facetoface

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTimelineFollowReducerTest {
    @Test
    fun appendIndexAdvanceKeepsFollowingAndRequestsScroll() {
        val afterAppend = ConversationTimelineFollowReducer.reduce(
            ConversationTimelineFollowState(),
            ConversationTimelineFollowEvent.TranscriptAppended(addedItems = 1),
        )

        assertTrue(afterAppend.followsLatest)
        assertTrue(afterAppend.scrollToLatestRequested)
    }

    @Test
    fun onlyUserScrollAwayPausesFollow() {
        var state = ConversationTimelineFollowState()

        state = ConversationTimelineFollowReducer.reduce(
            state,
            ConversationTimelineFollowEvent.ProgrammaticScrollFinished,
        )
        assertTrue(state.followsLatest)

        state = ConversationTimelineFollowReducer.reduce(
            state,
            ConversationTimelineFollowEvent.UserScrollFinished(atLatest = false),
        )
        assertFalse(state.followsLatest)
    }

    @Test
    fun tappingOrReachingLatestResumesFollow() {
        val paused = ConversationTimelineFollowReducer.reduce(
            ConversationTimelineFollowState(),
            ConversationTimelineFollowEvent.UserScrollFinished(atLatest = false),
        )

        val afterTap = ConversationTimelineFollowReducer.reduce(
            paused,
            ConversationTimelineFollowEvent.UserTappedLatest,
        )
        assertTrue(afterTap.followsLatest)
        assertTrue(afterTap.scrollToLatestRequested)

        val afterReach = ConversationTimelineFollowReducer.reduce(
            paused,
            ConversationTimelineFollowEvent.UserScrollFinished(atLatest = true),
        )
        assertTrue(afterReach.followsLatest)
        assertFalse(afterReach.scrollToLatestRequested)
    }
}
