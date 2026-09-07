package com.verba.interpretation.ui.facetoface

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.verba.interpretation.ui.FaceToFacePhase
import com.verba.interpretation.ui.FaceToFaceSide
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EarMicButtonTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun pointerReleaseSurvivesListeningRecompositionAndRunsOnce() {
        val events = mutableListOf<String>()
        var active by mutableStateOf(false)
        var pointerEnabled by mutableStateOf(true)
        var stateLabel by mutableStateOf("按住说话")

        compose.setContent {
            MaterialTheme {
                EarMicButton(
                    side = FaceToFaceSide.LEFT,
                    language = "zh",
                    otherLanguage = "en",
                    pointerEnabled = pointerEnabled,
                    actionEnabled = true,
                    active = active,
                    activeSide = if (active) FaceToFaceSide.LEFT else null,
                    phase = if (active) FaceToFacePhase.LISTENING else FaceToFacePhase.IDLE,
                    stateLabel = stateLabel,
                    onPress = {
                        events += "press"
                        active = true
                        pointerEnabled = false
                        stateLabel = "听取中…"
                    },
                    onRelease = {
                        events += "release"
                        active = false
                    },
                    onCancel = { events += "cancel" },
                    onAccessibleClick = { events += "click" },
                    onLanguage = {},
                )
            }
        }

        val button = compose.onNodeWithContentDescription("左耳，中文，按住说话，译文送至右耳")
        button.performTouchInput { down(center) }
        compose.waitForIdle()

        assertEquals(listOf("press"), events)
        compose.onNodeWithContentDescription("左耳，中文，听取中…，译文送至右耳").assertExists()

        compose.onNodeWithContentDescription("左耳，中文，听取中…，译文送至右耳").performTouchInput { up() }
        compose.waitForIdle()

        assertEquals(listOf("press", "release"), events)
    }

    @Test
    fun semanticsExposeStartAndEndActions() {
        val events = mutableListOf<String>()
        var active by mutableStateOf(false)

        compose.setContent {
            MaterialTheme {
                EarMicButton(
                    side = FaceToFaceSide.RIGHT,
                    language = "en",
                    otherLanguage = "zh",
                    pointerEnabled = false,
                    actionEnabled = true,
                    active = active,
                    activeSide = if (active) FaceToFaceSide.RIGHT else FaceToFaceSide.LEFT,
                    phase = FaceToFacePhase.LISTENING,
                    stateLabel = if (active) "结束右侧临时接话" else "开始右侧临时接话",
                    onPress = {},
                    onRelease = {},
                    onCancel = {},
                    onAccessibleClick = {
                        events += if (active) "end" else "start"
                        active = !active
                    },
                    onLanguage = {},
                )
            }
        }

        compose.onNodeWithContentDescription("右耳，English，开始右侧临时接话，译文送至左耳")
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("右耳，English，结束右侧临时接话，译文送至左耳")
            .assertIsDisplayed()
            .performClick()

        assertEquals(listOf("start", "end"), events)
    }

    @Test
    fun removingPressedButtonReportsCancelWithoutRelease() {
        val events = mutableListOf<String>()
        var visible by mutableStateOf(true)

        compose.setContent {
            MaterialTheme {
                if (visible) {
                    EarMicButton(
                        side = FaceToFaceSide.LEFT,
                        language = "zh",
                        otherLanguage = "en",
                        pointerEnabled = true,
                        actionEnabled = true,
                        active = true,
                        activeSide = FaceToFaceSide.LEFT,
                        phase = FaceToFacePhase.LISTENING,
                        stateLabel = "按住说话",
                        onPress = {
                            events += "press"
                            visible = false
                        },
                        onRelease = { events += "release" },
                        onCancel = { events += "cancel" },
                        onAccessibleClick = { events += "click" },
                        onLanguage = {},
                    )
                }
            }
        }

        val description = "左耳，中文，按住说话，译文送至右耳"
        compose.onNodeWithContentDescription(description).performTouchInput { down(center) }
        compose.waitForIdle()

        assertEquals(listOf("press", "cancel"), events)
    }
}
