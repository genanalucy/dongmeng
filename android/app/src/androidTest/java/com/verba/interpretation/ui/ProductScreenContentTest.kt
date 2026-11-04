package com.verba.interpretation.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProductScreenContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun navigationStopsAudioBeforeExitAnimationAndDoesNotCancelReenteredSession() {
        var screen by mutableStateOf(ProductScreen.FACE_TO_FACE_WORKBENCH)
        val stopped = mutableListOf<String>()
        compose.setContent {
            ProductScreenContent(
                screen,
                onStopInterpretation = { stopped += "solo" },
                onStopFaceToFace = { stopped += "face" },
            ) { displayed, _ -> Text(displayed.name) }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { screen = ProductScreen.INTERPRETATION_WORKBENCH }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(listOf("face"), stopped) }
        // Reverse while the old face page is still fading out.
        compose.runOnIdle { screen = ProductScreen.FACE_TO_FACE_WORKBENCH }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { assertEquals(listOf("face", "solo"), stopped) }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(listOf("face", "solo"), stopped) }
        compose.onNodeWithText(ProductScreen.FACE_TO_FACE_WORKBENCH.name).assertExists()
    }

    @Test fun backgroundStopsSessionAndInactiveDestinationRemovesLifecycleObserver() {
        lateinit var registry: LifecycleRegistry
        val owner = object : LifecycleOwner {
            override val lifecycle: Lifecycle get() = registry
        }
        var active by mutableStateOf(true)
        var stops = 0
        compose.runOnUiThread {
            registry = LifecycleRegistry(owner)
            registry.currentState = Lifecycle.State.RESUMED
        }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                WorkbenchSessionEffect(active) { stops++ }
            }
        }
        compose.runOnIdle {
            registry.currentState = Lifecycle.State.CREATED
            assertEquals(1, stops)
            registry.currentState = Lifecycle.State.RESUMED
            active = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(2, stops)
            registry.currentState = Lifecycle.State.CREATED
            assertEquals(2, stops)
        }
    }

    @Test fun inactiveAnimatedPageIsNotExposedToAccessibility() {
        var screen by mutableStateOf(ProductScreen.FACE_TO_FACE_WORKBENCH)
        compose.setContent {
            ProductScreenContent(screen, {}, {}) { displayed, _ -> Text(displayed.name) }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { screen = ProductScreen.PROFILE }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(ProductScreen.FACE_TO_FACE_WORKBENCH.name).assertDoesNotExist()
        compose.onNodeWithText(ProductScreen.PROFILE.name).assertExists()
    }
}
