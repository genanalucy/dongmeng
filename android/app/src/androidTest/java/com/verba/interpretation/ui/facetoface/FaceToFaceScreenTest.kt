package com.verba.interpretation.ui.facetoface

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.test.core.app.ApplicationProvider
import com.verba.interpretation.ui.FaceToFaceState
import com.verba.interpretation.ui.FaceToFaceViewModel
import com.verba.interpretation.ui.MicrophonePermissionAction
import org.junit.Rule
import org.junit.Test

class FaceToFaceScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun manualScreenExplainsBothEarDirectionsAndLanguages() {
        val viewModel = FaceToFaceViewModel(application())
        compose.setContent {
            MaterialTheme {
                FaceToFaceScreen(
                    state = FaceToFaceState(),
                    viewModel = viewModel,
                    requestMicrophone = { _: MicrophonePermissionAction -> },
                )
            }
        }

        compose.onNodeWithText("左耳").assertIsDisplayed()
        compose.onNodeWithText("右耳").assertIsDisplayed()
        compose.onNodeWithText("中文").assertIsDisplayed()
        compose.onNodeWithText("English").assertIsDisplayed()
        compose.onNodeWithContentDescription("左耳，中文，按住说话，译文送至右耳").assertIsDisplayed()
        compose.onNodeWithContentDescription("右耳，English，按住说话，译文送至左耳").assertIsDisplayed()
        compose.onNodeWithContentDescription("选择中文语言").assertIsDisplayed()
        compose.onNodeWithContentDescription("选择English语言").assertIsDisplayed()
    }

    @Test
    fun conversationIsDefaultAndToggleExposesTwoRotatedPanels() {
        val viewModel = FaceToFaceViewModel(application())
        compose.setContent {
            val state by viewModel.state.collectAsState()
            MaterialTheme {
                FaceToFaceScreen(state = state, viewModel = viewModel, requestMicrophone = { _: MicrophonePermissionAction -> })
            }
        }

        compose.onNodeWithContentDescription("切换到面对面布局").assertIsDisplayed().performClick()
        compose.onNodeWithTag("face-to-face-panels").assertIsDisplayed()
        compose.onNodeWithTag("face-to-face-panel-far").assertContentDescriptionEquals("远端右耳阅读区，旋转180度")
        compose.onNodeWithTag("face-to-face-panel-near").assertContentDescriptionEquals("近端左耳阅读区，正向")
    }

    @Test
    fun continuousScreenShowsCompactSessionActionsAndEarSemantics() {
        val state = FaceToFaceState(
            mode = com.verba.interpretation.ui.FaceToFaceMode.AUTO,
        )
        val viewModel = FaceToFaceViewModel(application())
        compose.setContent {
            MaterialTheme {
                FaceToFaceScreen(state = state, viewModel = viewModel, requestMicrophone = { _: MicrophonePermissionAction -> })
            }
        }

        compose.onNodeWithText("开始连续翻译").assertIsDisplayed()
        compose.onNodeWithContentDescription("左耳，中文，左侧连续收音，译文送至右耳").assertIsDisplayed()
        compose.onNodeWithContentDescription("右耳，English，按住临时接话，译文送至左耳").assertIsDisplayed()
    }

    @Test
    fun languagePickerCanChangeBeforeListening() {
        val viewModel = FaceToFaceViewModel(application())
        compose.setContent {
            MaterialTheme {
                FaceToFaceScreen(
                    state = FaceToFaceState(),
                    viewModel = viewModel,
                    requestMicrophone = { _: MicrophonePermissionAction -> },
                )
            }
        }

        compose.onNodeWithContentDescription("选择中文语言").performClick()
        compose.onNodeWithText("日语").performClick()
    }

    private fun application(): Application = ApplicationProvider.getApplicationContext()
}
