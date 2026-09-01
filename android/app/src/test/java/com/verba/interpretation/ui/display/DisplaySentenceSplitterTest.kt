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
    fun splitsOnExactlyThreeContiguousFullWidthOrAsciiCommas() {
        assertEquals(listOf("甲，，，", "乙,,,", "丙"), DisplaySentenceSplitter.split("甲，，，乙,,,丙"))
    }

    @Test
    fun doesNotSplitOnOneOrTwoCommas() {
        assertEquals(listOf("甲，乙，，丙"), DisplaySentenceSplitter.split("甲，乙，，丙"))
        assertEquals(listOf("one,two,,three"), DisplaySentenceSplitter.split("one,two,,three"))
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
