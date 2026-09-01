package com.verba.interpretation.ui.facetoface

import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceTurn
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationDisplayBubbleTest {
    @Test
    fun pairsOnlyMatchingFinalEventIndexesRatherThanAggregatedSentenceIndexes() {
        val turn = FaceToFaceTurn(
            id = 42,
            side = FaceToFaceSide.LEFT,
            sourceLanguage = "zh",
            targetLanguage = "en",
            route = PlaybackRoute.RIGHT,
            sourceFinals = listOf("我叫程卫东。", "啊！你打听打听去，这片谁不认识我姓陈的？"),
            translationFinals = listOf("Bro, watch your mouth.", "Who are you?"),
        )

        assertEquals(
            listOf(
                ConversationDisplayBubble("42:0", "我叫程卫东。", "Bro, watch your mouth.", FaceToFaceSide.LEFT, "zh", "en", FaceToFaceTurnAlignment.START),
                ConversationDisplayBubble("42:1", "啊！你打听打听去，这片谁不认识我姓陈的？", "Who are you?", FaceToFaceSide.LEFT, "zh", "en", FaceToFaceTurnAlignment.START),
            ),
            displayConversationBubbles(listOf(turn)),
        )
    }

    @Test
    fun showsPendingSourcePartialWithoutAttachingAnUnmatchedTranslation() {
        val turn = FaceToFaceTurn(
            id = 43,
            side = FaceToFaceSide.LEFT,
            sourceLanguage = "zh",
            targetLanguage = "en",
            route = PlaybackRoute.RIGHT,
            sourceFinals = listOf("已经翻译。"),
            sourcePartial = "正在识别",
            translationFinals = listOf("Already translated."),
        )

        assertEquals(
            listOf(
                ConversationDisplayBubble("43:0", "已经翻译。", "Already translated.", FaceToFaceSide.LEFT, "zh", "en", FaceToFaceTurnAlignment.START),
                ConversationDisplayBubble("43:source-partial", "正在识别", "正在翻译…", FaceToFaceSide.LEFT, "zh", "en", FaceToFaceTurnAlignment.START),
            ),
            displayConversationBubbles(listOf(turn)),
        )
    }

    @Test
    fun keepsLongTranslationOnlyTextVisuallyCappedWithoutDroppingCharacters() {
        val longTranslation = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen"
        val bubbles = displayConversationBubbles(
            listOf(
                FaceToFaceTurn(
                    id = 44,
                    side = FaceToFaceSide.LEFT,
                    sourceLanguage = "zh",
                    targetLanguage = "en",
                    route = PlaybackRoute.RIGHT,
                    translationFinals = listOf(longTranslation),
                ),
            ),
        )

        assertEquals(longTranslation, bubbles.joinToString("") { it.translationText })
        assertEquals(listOf(null, null), bubbles.map { it.sourceText })
        assertEquals(listOf("44:0:0", "44:0:1"), bubbles.map { it.key })
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
