package com.verba.interpretation.ui.facetoface

import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceTurn
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationDisplayBubbleTest {
    @Test
    fun flattensSourceAndTranslationSeparatelyWhenTheirSplitCountsDiffer() {
        val turn = FaceToFaceTurn(
            id = 42,
            side = FaceToFaceSide.LEFT,
            sourceLanguage = "zh",
            targetLanguage = "en",
            route = PlaybackRoute.RIGHT,
            sourceFinals = listOf("甲。乙。丙"),
            translationFinals = listOf("One. Two"),
        )

        assertEquals(
            listOf(
                ConversationDisplayBubble("42:source:0", "甲。", FaceToFaceSide.LEFT, "zh", FaceToFaceTurnAlignment.START, false),
                ConversationDisplayBubble("42:source:1", "乙。", FaceToFaceSide.LEFT, "zh", FaceToFaceTurnAlignment.START, false),
                ConversationDisplayBubble("42:source:2", "丙", FaceToFaceSide.LEFT, "zh", FaceToFaceTurnAlignment.START, false),
                ConversationDisplayBubble("42:translation:0", "One.", FaceToFaceSide.LEFT, "en", FaceToFaceTurnAlignment.START, true),
                ConversationDisplayBubble("42:translation:1", " Two", FaceToFaceSide.LEFT, "en", FaceToFaceTurnAlignment.START, true),
            ),
            displayConversationBubbles(listOf(turn)),
        )
    }
}
