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
        compose.onNodeWithText("英文").assertIsDisplayed()
        compose.onNodeWithText("左耳说中文，译文送到右耳；右耳说英文，译文送到左耳").assertIsDisplayed()
        compose.onNodeWithContentDescription("左耳，中文，按住麦克风开始，译文送至右耳").assertIsDisplayed()
        compose.onNodeWithContentDescription("右耳，英文，按住麦克风开始，译文送至左耳").assertIsDisplayed()
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
