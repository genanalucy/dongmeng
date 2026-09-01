package com.verba.interpretation.ui.facetoface

import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceTurn
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationDisplayBubbleTest {
    @Test
    fun pairsDisplaySentencesByIndexAndKeepsTurnScopedStableKeys() {
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
                ConversationDisplayBubble("42:0", "甲。", "One.", FaceToFaceSide.LEFT, "zh", "en", FaceToFaceTurnAlignment.START),
                ConversationDisplayBubble("42:1", "乙。", " Two", FaceToFaceSide.LEFT, "zh", "en", FaceToFaceTurnAlignment.START),
                ConversationDisplayBubble("42:2", "丙", "正在翻译…", FaceToFaceSide.LEFT, "zh", "en", FaceToFaceTurnAlignment.START),
            ),
            displayConversationBubbles(listOf(turn)),
        )
    }

    @Test
    fun keepsTranslationOnlySegmentUnpairedAndUsesSpeakingSideAlignment() {
        val turn = FaceToFaceTurn(
            id = 7,
            side = FaceToFaceSide.RIGHT,
            sourceLanguage = "en",
            targetLanguage = "zh",
            route = PlaybackRoute.LEFT,
            sourceFinals = emptyList(),
            translationFinals = listOf("译文。"),
        )

        assertEquals(
            listOf(
                ConversationDisplayBubble("7:0", null, "译文。", FaceToFaceSide.RIGHT, "en", "zh", FaceToFaceTurnAlignment.END),
            ),
            displayConversationBubbles(listOf(turn)),
        )
    }
}
