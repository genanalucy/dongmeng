package com.verba.interpretation.ui.facetoface

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
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
