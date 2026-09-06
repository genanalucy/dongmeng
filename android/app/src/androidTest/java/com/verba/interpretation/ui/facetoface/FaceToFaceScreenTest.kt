package com.verba.interpretation.ui.facetoface

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.verba.interpretation.ui.FaceToFaceState
import com.verba.interpretation.ui.FaceToFaceViewModel
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
                    requestMicrophone = {},
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
    fun continuousScreenSeparatesSessionActionsFromEarControls() {
        val state = FaceToFaceState(
            mode = com.verba.interpretation.ui.FaceToFaceMode.AUTO,
        )
        val viewModel = FaceToFaceViewModel(application())
        compose.setContent {
            MaterialTheme {
                FaceToFaceScreen(state = state, viewModel = viewModel, requestMicrophone = {})
            }
        }

        compose.onNodeWithText("开始连续翻译").assertIsDisplayed()
        compose.onNodeWithText("左侧连续收音；按住右耳临时切换，松开恢复左耳").assertIsDisplayed()
    }

    @Test
    fun languagePickerCanChangeBeforeListening() {
        val viewModel = FaceToFaceViewModel(application())
        compose.setContent {
            MaterialTheme {
                FaceToFaceScreen(
                    state = FaceToFaceState(),
                    viewModel = viewModel,
                    requestMicrophone = {},
                )
            }
        }

        compose.onNodeWithContentDescription("选择中文语言").performClick()
        compose.onNodeWithText("日语").performClick()
    }

    private fun application(): Application = ApplicationProvider.getApplicationContext()
}
