package com.verba.interpretation.ui.facetoface

import android.app.Application
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.verba.interpretation.ui.FaceToFaceState
import com.verba.interpretation.ui.FaceToFaceView
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
        compose.onNodeWithContentDescription("开启连续翻译").assertIsDisplayed()
    }

    @Test
    fun viewButtonOpensMenuAndChangesOnlyAfterSelectingAnOption() {
        val viewModel = FaceToFaceViewModel(application())
        compose.setContent {
            val state by viewModel.state.collectAsState()
            MaterialTheme { FaceToFaceScreen(state, viewModel, requestMicrophone = {}) }
        }

        compose.onNodeWithTag("face-view-menu").assertIsDisplayed().performClick()
        compose.onNodeWithTag("face-view-option-conversation").assertIsDisplayed()
        compose.onNodeWithTag("face-view-option-face_to_face").assertIsDisplayed()
        compose.onNodeWithTag("face-to-face-panels").assertDoesNotExist()
        compose.onNodeWithTag("face-view-option-face_to_face").performClick()
        compose.onNodeWithTag("face-to-face-panels").assertIsDisplayed()
        compose.onNodeWithTag("face-to-face-panel-far")
            .assertContentDescriptionEquals("远端右耳阅读区和麦克风，文字倒向对方，滑动方向自然")
        compose.onNodeWithTag("face-to-face-panel-near")
            .assertContentDescriptionEquals("近端左耳阅读区和麦克风，正向")
    }

    @Test
    fun constrainedPanelsCanScrollToAndOperateBothEarControls() {
        val viewModel = FaceToFaceViewModel(application())
        compose.setContent {
            val state by viewModel.state.collectAsState()
            MaterialTheme {
                FaceToFaceScreen(state, viewModel, requestMicrophone = {}, modifier = Modifier.height(240.dp))
            }
        }

        viewModel.setView(FaceToFaceView.FACE_TO_FACE)
        compose.onNodeWithTag("face-to-face-panel-far").assertIsDisplayed()
        compose.onNodeWithContentDescription("右耳，English，按住说话，译文送至左耳").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("face-to-face-panel-near").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("左耳，中文，按住说话，译文送至右耳").performScrollTo().assertIsDisplayed().performClick()
    }

    @Test
    fun constrainedPanelsCanUseFontScaleAndStillExposeControls() {
        val viewModel = FaceToFaceViewModel(application())
        compose.setContent {
            val state by viewModel.state.collectAsState()
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 2f)) {
                MaterialTheme {
                    FaceToFaceScreen(state, viewModel, requestMicrophone = {}, modifier = Modifier.height(260.dp))
                }
            }
        }

        viewModel.setView(FaceToFaceView.FACE_TO_FACE)
        compose.onNodeWithTag("face-to-face-panel-far").assertIsDisplayed().performScrollTo()
        compose.onNodeWithContentDescription("右耳，English，按住说话，译文送至左耳").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("face-to-face-panel-near").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("左耳，中文，按住说话，译文送至右耳").performScrollTo().assertIsDisplayed().performClick()
    }

    @Test
    fun continuousScreenShowsMiddlePrimarySessionControlAndEarSemantics() {
        val state = FaceToFaceState(mode = com.verba.interpretation.ui.FaceToFaceMode.AUTO)
        val viewModel = FaceToFaceViewModel(application())
        compose.setContent {
            MaterialTheme { FaceToFaceScreen(state, viewModel, requestMicrophone = { _: MicrophonePermissionAction -> }) }
        }

        compose.onNodeWithContentDescription("开启连续翻译").assertIsDisplayed()
        compose.onNodeWithContentDescription("左耳，中文，开始连续收音，译文送至右耳").assertIsDisplayed()
        compose.onNodeWithContentDescription("右耳，English，开始右侧临时接话，译文送至左耳").assertIsDisplayed()
    }

    @Test
    fun languagePickerCanChangeBeforeListening() {
        val viewModel = FaceToFaceViewModel(application())
        compose.setContent {
            MaterialTheme { FaceToFaceScreen(FaceToFaceState(), viewModel, requestMicrophone = { _: MicrophonePermissionAction -> }) }
        }

        compose.onNodeWithContentDescription("选择中文语言").performClick()
        compose.onNodeWithText("日语").performClick()
    }

    private fun application(): Application = ApplicationProvider.getApplicationContext()
}
