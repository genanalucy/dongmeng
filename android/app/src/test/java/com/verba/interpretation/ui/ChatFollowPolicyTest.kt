package com.verba.interpretation.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFollowPolicyTest {
    @Test
    fun transcriptUpdatesStayFollowingAtLatest() {
        val next = ChatFollowPolicy.reduce(ChatFollowState(), ChatFollowEvent.TranscriptChanged(1))

        assertTrue(next.followsLatest)
        assertEquals(0, next.unseenUpdates)
    }

    @Test
    fun partialAndNewItemUpdatesCountWhileReadingHistory() {
        val readingHistory = ChatFollowPolicy.reduce(ChatFollowState(), ChatFollowEvent.UserLeftLatest)
        val afterPartial = ChatFollowPolicy.reduce(readingHistory, ChatFollowEvent.TranscriptChanged(1))
        val afterTwoNewItems = ChatFollowPolicy.reduce(afterPartial, ChatFollowEvent.TranscriptChanged(2))

        assertFalse(afterTwoNewItems.followsLatest)
        assertEquals(3, afterTwoNewItems.unseenUpdates)
    }

    @Test
    fun returningToLatestClearsUnseenUpdates() {
        val state = ChatFollowState(followsLatest = false, unseenUpdates = 4)

        assertEquals(ChatFollowState(), ChatFollowPolicy.reduce(state, ChatFollowEvent.UserReachedLatest))
    }

    @Test
    fun changedTranscriptAlwaysCountsAtLeastOneUpdate() {
        val state = ChatFollowState(followsLatest = false, unseenUpdates = 2)

        val next = ChatFollowPolicy.reduce(state, ChatFollowEvent.TranscriptChanged(0))

        assertEquals(3, next.unseenUpdates)
    }
}
