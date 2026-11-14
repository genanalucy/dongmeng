package com.verba.interpretation.ui.facetoface

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.verba.interpretation.ui.FaceToFaceState
import com.verba.interpretation.ui.FaceToFaceViewModel
import org.junit.Rule
import org.junit.Test

class ConversationGuidanceScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun idleLanguagePairSwapsAtomicallyAndExposesSystemInputGuidance() {
        val vm = FaceToFaceViewModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent {
            val state by vm.state.collectAsState()
            MaterialTheme { FaceToFaceScreen(state, vm, requestMicrophone = {}) }
        }
        compose.onNodeWithText("系统麦克风 · 非双耳独立收音").assertIsDisplayed()
        compose.onNodeWithText("左耳一方").assertDoesNotExist()
        compose.onNodeWithText("右耳一方").assertDoesNotExist()
        compose.onNodeWithText("左侧发言").assertDoesNotExist()
        compose.onNodeWithText("右侧发言").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回翻译首页").assertIsDisplayed()
        compose.onNodeWithContentDescription("交换左右语言").performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals("en", vm.state.value.leftLanguage)
            org.junit.Assert.assertEquals("zh", vm.state.value.rightLanguage)
        }
    }

    @Test fun languageMarkersStayOutsideLanguagesAndMicrophonesAreFlatTouchTargets() {
        val vm = FaceToFaceViewModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent { MaterialTheme { FaceToFaceScreen(FaceToFaceState(), vm, requestMicrophone = {}) } }
        val leftLanguage = compose.onNodeWithContentDescription("选择中文语言").fetchSemanticsNode().boundsInRoot
        val rightLanguage = compose.onNodeWithContentDescription("选择English语言").fetchSemanticsNode().boundsInRoot
        val leftMarker = compose.onAllNodesWithText("L", useUnmergedTree = true).fetchSemanticsNodes().minBy { it.boundsInRoot.top }.boundsInRoot
        val rightMarker = compose.onAllNodesWithText("R", useUnmergedTree = true).fetchSemanticsNodes().minBy { it.boundsInRoot.top }.boundsInRoot
        org.junit.Assert.assertTrue(leftMarker.right <= leftLanguage.left)
        org.junit.Assert.assertTrue(rightMarker.left >= rightLanguage.right)
        val microphone = compose.onNodeWithContentDescription("左耳，中文，按住说话，译文送至右耳").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(microphone.width > microphone.height)
        with(compose.density) { org.junit.Assert.assertTrue(microphone.height >= 48.dp.toPx()) }
    }

    @Test fun backgroundWorkDisablesSwapAndOffersSubtitleSizesWithoutStartingAudio() {
        val vm = FaceToFaceViewModel(ApplicationProvider.getApplicationContext<Application>())
        compose.setContent {
            MaterialTheme {
                FaceToFaceScreen(FaceToFaceState(configurationEditable = false), vm, requestMicrophone = {})
            }
        }
        compose.onNodeWithContentDescription("交换左右语言").assertIsNotEnabled()
        compose.onNodeWithText("等待上一轮收尾…").assertIsDisplayed()
        compose.onNodeWithContentDescription("面对面翻译更多选项").performClick()
        compose.onNodeWithContentDescription("字幕字号：大").performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(vm.state.value.turns.isEmpty()) }
    }
}
