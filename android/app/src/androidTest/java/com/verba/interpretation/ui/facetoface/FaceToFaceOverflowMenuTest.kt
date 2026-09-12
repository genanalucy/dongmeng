package com.verba.interpretation.ui.facetoface

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.verba.interpretation.ui.FaceToFaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FaceToFaceOverflowMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun debugAutomaticModeStartsAutomaticTranslationInsteadOfShowingPlaceholder() {
        var automaticStartCount = 0
        compose.setContent {
            MaterialTheme {
                FaceToFaceOverflowMenu(
                    state = FaceToFaceState(),
                    onSelectMode = {},
                    onStopAuto = {},
                    onStartAutomaticMode = { automaticStartCount += 1 },
                )
            }
        }

        compose.onNodeWithContentDescription("面对面翻译更多选项").performClick()
        compose.onNodeWithText("自动模式").assertIsDisplayed().performClick()

        assertEquals(1, automaticStartCount)
        assertTrue(compose.onAllNodesWithText("功能开发中").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun automaticModeEntryIsHiddenWhenDisabledForRelease() {
        compose.setContent {
            MaterialTheme {
                FaceToFaceOverflowMenu(
                    state = FaceToFaceState(),
                    onSelectMode = {},
                    onStopAuto = {},
                    onStartAutomaticMode = {},
                    showAutomaticMode = false,
                )
            }
        }

        compose.onNodeWithContentDescription("面对面翻译更多选项").performClick()

        assertTrue(compose.onAllNodesWithText("自动模式").fetchSemanticsNodes().isEmpty())
    }
}
