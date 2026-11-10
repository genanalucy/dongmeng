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
        compose.onNodeWithText("左耳一方").assertIsDisplayed()
        compose.onNodeWithText("右耳一方").assertIsDisplayed()
        compose.onNodeWithContentDescription("交换左右语言").performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals("en", vm.state.value.leftLanguage)
            org.junit.Assert.assertEquals("zh", vm.state.value.rightLanguage)
        }
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
