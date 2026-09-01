package com.verba.interpretation.ui.display

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplaySentenceSplitterTest {
    @Test
    fun splitsOnChineseAndAsciiFullStopsWhileKeepingPunctuation() {
        assertEquals(
            listOf("第一句。", "Second sentence.", "尾句"),
            DisplaySentenceSplitter.split("第一句。Second sentence.尾句"),
        )
    }

    @Test
    fun splitsAfterThirdCumulativeCommaInDisplaySegment() {
        assertEquals(
            listOf(
                "空气症状慢慢缓和，他告诉德里斯这种痛苦像是药物带来的副作用，身体明明没有知觉，",
                "精神却仍然会被疼痛折磨。",
            ),
            DisplaySentenceSplitter.split("空气症状慢慢缓和，他告诉德里斯这种痛苦像是药物带来的副作用，身体明明没有知觉，精神却仍然会被疼痛折磨。"),
        )
    }

    @Test
    fun countsFullWidthAndAsciiCommasTogether() {
        assertEquals(listOf("甲，乙,丙，", "丁"), DisplaySentenceSplitter.split("甲，乙,丙，丁"))
    }

    @Test
    fun resetsCommaCountAfterFullStop() {
        assertEquals(listOf("甲，乙。", "丙，丁，戊"), DisplaySentenceSplitter.split("甲，乙。丙，丁，戊"))
    }

    @Test
    fun doesNotSplitOnOneOrTwoCommas() {
        assertEquals(listOf("甲，乙"), DisplaySentenceSplitter.split("甲，乙"))
        assertEquals(listOf("甲，乙,丙"), DisplaySentenceSplitter.split("甲，乙,丙"))
    }

    @Test
    fun preservesWhitespaceAndOmitsEmptySegmentsAcrossMultipleDelimiters() {
        assertEquals(
            listOf(" 甲。", " 乙，，，", " 丙."),
            DisplaySentenceSplitter.split(" 甲。 乙，，， 丙."),
        )
        assertEquals(listOf("。", "甲"), DisplaySentenceSplitter.split("。甲"))
    }

    @Test
    fun keepsNonterminalTailAsLatestSegment() {
        assertEquals(listOf("已完成。", "正在识别"), DisplaySentenceSplitter.split("已完成。正在识别"))
    }

    @Test
    fun splitsSourceAndTranslationIndependentlyWithoutPairingByIndex() {
        val split = DisplaySentenceSplitter.split(
            source = "甲。乙。丙",
            translation = "One. Two",
        )

        assertEquals(listOf("甲。", "乙。", "丙"), split.source)
        assertEquals(listOf("One.", " Two"), split.translation)
    }
}
