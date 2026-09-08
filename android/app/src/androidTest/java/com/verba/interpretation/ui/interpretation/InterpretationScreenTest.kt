package com.verba.interpretation.ui.interpretation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.verba.interpretation.ui.InterpretationUiState
import com.verba.interpretation.ui.SessionPhase
import com.verba.interpretation.ui.SubtitleTurn
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class InterpretationScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun idleStateShowsHumanLanguageNamesAndOneStartAction() {
        var starts = 0
        setScreen(
            state = InterpretationUiState(sourceLanguage = "zh", targetLanguage = "en"),
            onStart = { starts += 1 },
        )

        compose.onNodeWithText("中文 原文").assertIsDisplayed()
        compose.onNodeWithText("English 译文").assertIsDisplayed()
        compose.onNodeWithText("译文会显示在这里").assertIsDisplayed()
        compose.onNodeWithContentDescription("开始同传").assertIsDisplayed()
        compose.onNodeWithContentDescription("开始同传").performClick()
        assertEquals(1, starts)
        compose.onAllNodesWithText("正在翻译").assertCountEquals(0)
    }

    @Test
    fun idleLanguageSelectorChangesSourceLanguage() {
        var selected: Pair<String, String>? = null
        compose.setContent {
            MaterialTheme {
                InterpretationScreen(
                    model = InterpretationUiMapper.map(InterpretationUiState(sourceLanguage = "zh", targetLanguage = "en")),
                    onExit = {}, onStart = {}, onPause = {}, onResume = {}, onFinish = {}, onReset = {},
                    onSetLanguages = { source, target -> selected = source to target },
                )
            }
        }

        compose.onNodeWithContentDescription("选择中文语言").performClick()
        compose.onNodeWithText("Tiếng Việt").performClick()

        assertEquals("vi" to "en", selected)
    }

    @Test
    fun controlsExposeOnlyIconsWhileKeepingAccessibleLabels() {
        setScreen(state = InterpretationUiState(phase = SessionPhase.RUNNING))

        compose.onNodeWithContentDescription("暂停同传").assertIsDisplayed()
        compose.onNodeWithContentDescription("结束同传").assertIsDisplayed()
        compose.onAllNodesWithText("暂停").assertCountEquals(0)
        compose.onAllNodesWithText("结束").assertCountEquals(0)
    }

    @Test
    fun largeLabelTextKeepsStartTargetAtLeast48Dp() {
        setScreen(state = InterpretationUiState())

        val height = compose.onNodeWithContentDescription("开始同传").fetchSemanticsNode().size.height
        with(compose.density) {
            assert(height >= 48.dp.toPx())
        }
    }

    @Test
    fun translationIsReadBeforeSourceAndPausedStateOffersResume() {
        setScreen(
            state = InterpretationUiState(
                phase = SessionPhase.PAUSED,
                turns = listOf(
                    SubtitleTurn(
                        id = 7,
                        sourceLanguage = "zh",
                        targetLanguage = "en",
                        sourceFinals = listOf("原文"),
                        translationFinals = listOf("Translation"),
                    ),
                ),
            ),
        )

        compose.onNodeWithText("Translation").assertIsDisplayed()
        compose.onNodeWithText("原文").assertIsDisplayed()
        compose.onNodeWithContentDescription("继续同传").assertIsDisplayed()
        compose.onAllNodesWithText("译文会显示在这里").assertCountEquals(0)
    }

    @Test
    fun errorStateDoesNotShowPreparationCopyOrResumeAction() {
        setScreen(
            state = InterpretationUiState(
                phase = SessionPhase.ERROR,
                error = "token=private",
            ),
        )

        compose.onNodeWithText("翻译服务暂时不可用，请重试或重新开始。").assertIsDisplayed()
        compose.onNodeWithContentDescription("重新开始翻译").assertIsDisplayed()
        compose.onAllNodesWithText("准备开始").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("继续同传").assertCountEquals(0)
    }

    private fun setScreen(
        state: InterpretationUiState,
        onStart: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                InterpretationScreen(
                    model = InterpretationUiMapper.map(state),
                    onExit = {},
                    onStart = onStart,
                    onPause = {},
                    onResume = {},
                    onFinish = {},
                    onReset = {},
                )
            }
        }
    }
}
