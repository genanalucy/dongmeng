package com.verba.interpretation.ui.interpretation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InterpretationTimelineFollowReducerTest {
    @Test
    fun appendDoesNotStopFollowingButUserScrollDoes() {
        var state = InterpretationTimelineFollowState()

        // A new bubble changes latest from 3 to 4 while item 3 is still visible.
        state = InterpretationTimelineFollowReducer.reduce(
            state,
            InterpretationTimelineFollowEvent.TranscriptAppended(addedItems = 1),
        )
        assertTrue(state.followsLatest)
        assertTrue(state.scrollToLatestRequested)

        state = InterpretationTimelineFollowReducer.reduce(
            state,
            InterpretationTimelineFollowEvent.ProgrammaticScrollFinished,
        )
        assertTrue(state.followsLatest)

        state = InterpretationTimelineFollowReducer.reduce(
            state,
            InterpretationTimelineFollowEvent.UserScrollFinished(atLatest = false),
        )
        assertFalse(state.followsLatest)

        state = InterpretationTimelineFollowReducer.reduce(
            state,
            InterpretationTimelineFollowEvent.UserScrollFinished(atLatest = true),
        )
        assertTrue(state.followsLatest)
    }
}
