package com.verba.interpretation.ui.facetoface

import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceTurn
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationDisplayBubbleTest {
    @Test
    fun unfinishedTurnIsOneLiveBilingualBubbleAndFinishesInPlace() {
        val turn = FaceToFaceTurn(
            id = 9,
            side = FaceToFaceSide.RIGHT,
            sourceLanguage = "en",
            targetLanguage = "zh",
            route = PlaybackRoute.LEFT,
            sourcePartial = "hello",
            translationPartial = "你好",
        )

        val live = displayConversationBubbles(listOf(turn), FaceToFacePhase.PROCESSING).single()
        assertEquals("9:0", live.key)
        assertEquals("hello", live.sourceText)
        assertEquals("你好", live.translationText)
        assertEquals(true, live.isLive)
        assertEquals(FaceToFacePhase.PROCESSING, live.livePhase)

        val finished = displayConversationBubbles(listOf(turn.copy(finished = true)))
        assertEquals("9:0", finished.first().key)
        assertEquals(false, finished.first().isLive)
    }

    @Test
    fun onlyNewestUnfinishedTurnIsLiveWhenOlderTurnIsStillDraining() {
        val old = FaceToFaceTurn(
            id = 10,
            side = FaceToFaceSide.LEFT,
            sourceLanguage = "zh",
            targetLanguage = "en",
            route = PlaybackRoute.RIGHT,
            sourcePartial = "old",
        )
        val current = FaceToFaceTurn(
            id = 11,
            side = FaceToFaceSide.RIGHT,
            sourceLanguage = "en",
            targetLanguage = "zh",
            route = PlaybackRoute.LEFT,
            sourcePartial = "current",
        )

        val bubbles = displayConversationBubbles(listOf(old, current), FaceToFacePhase.LISTENING)

        assertEquals(listOf("10:0", "11:0"), bubbles.map { it.key })
        assertEquals(listOf(false, true), bubbles.map { it.isLive })
        assertEquals(11L, activeConversationTurnId(listOf(old, current), FaceToFacePhase.LISTENING))
    }

    @Test
    fun explicitActiveTurnIdWinsOverPhaseFallback() {
        val first = FaceToFaceTurn(
            id = 1,
            side = FaceToFaceSide.LEFT,
            sourceLanguage = "zh",
            targetLanguage = "en",
            route = PlaybackRoute.RIGHT,
            sourcePartial = "first",
        )
        val second = first.copy(id = 2, sourcePartial = "second")

        val bubbles = displayConversationBubbles(
            listOf(first, second),
            phase = FaceToFacePhase.LISTENING,
            activeTurnId = 1,
        )

        assertEquals(listOf(true, false), bubbles.map { it.isLive })
    }

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
    fun partialThenMultipleFinalsHaveUniqueStableKeysWithoutDuplication() {
        val turn = FaceToFaceTurn(
            id = 45,
            side = FaceToFaceSide.LEFT,
            sourceLanguage = "zh",
            targetLanguage = "en",
            route = PlaybackRoute.RIGHT,
            sourceFinals = listOf("第一句", "第二句"),
            sourcePartial = "第三句",
            translationFinals = listOf("first", "second"),
        )

        val bubbles = displayConversationBubbles(listOf(turn))

        assertEquals(listOf("45:0", "45:1", "45:source-partial"), bubbles.map { it.key })
        assertEquals(3, bubbles.distinctBy { it.key }.size)
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
