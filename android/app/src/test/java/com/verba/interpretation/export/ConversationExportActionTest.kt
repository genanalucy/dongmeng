package com.verba.interpretation.export

import com.verba.interpretation.audio.PlaybackRoute
import com.verba.interpretation.ui.FaceToFaceSide
import com.verba.interpretation.ui.FaceToFaceState
import com.verba.interpretation.ui.FaceToFaceTurn
import com.verba.interpretation.ui.FaceToFaceView
import com.verba.interpretation.ui.InterpretationUiState
import com.verba.interpretation.ui.SubtitleTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationExportActionTest {
    private val final = SubtitleTurn(1, "zh", "en", sourceFinals = listOf("你好"),
        translationFinals = listOf("Hello"), finished = true)

    @Test fun completionRequiresFinishedBilingualFinalsWithoutAnyPartial() {
        val turns = listOf(
            final,
            final.copy(finished = false),
            final.copy(sourcePartial = " "),
            final.copy(translationPartial = "pending"),
            final.copy(sourceFinals = emptyList()),
            final.copy(translationFinals = listOf(" ")),
            final.copy(sourceFinals = listOf("one", "two")),
        )
        val entries = InterpretationUiState(turns = turns).exportSnapshot(0).entries
        assertTrue(entries.first().completed)
        entries.drop(1).forEach { assertFalse(it.completed) }
        assertTrue(entries.all { it.completedAtMillis == null })
    }

    @Test fun preservesWholeTransportTextAndNeverPairsOrTruncatesFinalArrays() {
        val turn = final.copy(sourceFinals = listOf("  first\n", "second"),
            sourcePartial = "pending", translationFinals = listOf("一 二"),
            translationPartial = "译文 😀")
        val snapshot = InterpretationUiState(turns = listOf(turn, turn)).exportSnapshot(123)
        assertEquals(2, snapshot.entries.size)
        assertEquals(turn.sourceText, snapshot.entries[0].sourceText)
        assertEquals(turn.translatedText, snapshot.entries[0].translatedText)
        assertFalse(snapshot.entries[0].completed)
        assertEquals(snapshot.entries[0], snapshot.entries[1])
    }

    @Test fun frozenTextSurvivesStateResetAndMutableFinalListsChanging() {
        val sources = mutableListOf("old")
        val translations = mutableListOf("旧")
        var state = InterpretationUiState(turns = listOf(final.copy(
            sourceFinals = sources, translationFinals = translations)))
        val snapshot = state.exportSnapshot(0)
        state = state.copy(turns = emptyList())
        sources.clear()
        translations.add("new")
        assertTrue(state.turns.isEmpty())
        assertEquals("old", snapshot.entries.single().sourceText)
        assertEquals("旧", snapshot.entries.single().translatedText)
    }

    @Test fun faceAndConversationViewsPreserveSideDirectionAndPartialOnlyContent() {
        val turn = FaceToFaceTurn(1, FaceToFaceSide.RIGHT, "en", "zh", PlaybackRoute.LEFT,
            sourcePartial = "English only")
        val state = FaceToFaceState(turns = listOf(turn))
        assertEquals("conversation", state.exportSnapshot(0).mode)
        val snapshot = state.copy(view = FaceToFaceView.FACE_TO_FACE).exportSnapshot(0)
        assertEquals("face_to_face", snapshot.mode)
        val entry = snapshot.entries.single()
        assertEquals("RIGHT", entry.side)
        assertEquals("en", entry.sourceLanguage)
        assertEquals("zh", entry.targetLanguage)
        assertEquals("English only", entry.sourceText)
        assertEquals("", entry.translatedText)
        assertFalse(entry.completed)
    }

    @Test fun entirelyBlankTurnsAreNotExported() {
        assertTrue(InterpretationUiState(turns = listOf(SubtitleTurn(1, "zh", "en")))
            .exportSnapshot(0).entries.isEmpty())
    }
}
