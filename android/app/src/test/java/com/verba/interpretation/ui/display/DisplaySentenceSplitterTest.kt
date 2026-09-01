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
    fun capsLongUnpairedTextAtSafeDelimiterWithoutDataLoss() {
        val text = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen"

        val segments = DisplaySentenceSplitter.splitForVisualCap(text)

        assertEquals(text, segments.joinToString(""))
        assertEquals(listOf("one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen ", "sixteen seventeen eighteen"), segments)
    }

    @Test
    fun hardCutsLongTextWhenNoSafeDelimiterExists() {
        val text = "x".repeat(DisplaySentenceSplitter.MAX_LATIN_DISPLAY_CHARS + 1)

        val segments = DisplaySentenceSplitter.splitForVisualCap(text)

        assertEquals(text, segments.joinToString(""))
        assertEquals(listOf("x".repeat(DisplaySentenceSplitter.MAX_LATIN_DISPLAY_CHARS), "x"), segments)
    }

    @Test
    fun preservesWhitespaceAndOmitsEmptySegmentsAcrossMultipleDelimiters() {
        assertEquals(
            listOf(" 甲。", " 乙，，， 丙."),
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
