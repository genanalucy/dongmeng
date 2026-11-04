package com.verba.interpretation.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.verba.interpretation.ui.design.MotionQuickMillis
import com.verba.interpretation.ui.design.MotionScreenMillis
import com.verba.interpretation.ui.design.motionEnabled

/** Navigation owns audio; outgoing animation content is display-only. */
@Composable
internal fun ProductScreenContent(
    screen: ProductScreen,
    onStopInterpretation: () -> Unit,
    onStopFaceToFace: () -> Unit,
    content: @Composable (ProductScreen, Boolean) -> Unit,
) {
    WorkbenchSessionEffect(screen == ProductScreen.INTERPRETATION_WORKBENCH, onStopInterpretation)
    WorkbenchSessionEffect(screen == ProductScreen.FACE_TO_FACE_WORKBENCH, onStopFaceToFace)
    val transitionsEnabled = motionEnabled()
    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            if (!transitionsEnabled) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (fadeIn(tween(MotionScreenMillis, easing = FastOutSlowInEasing)) +
                    slideInHorizontally(tween(MotionScreenMillis, easing = FastOutSlowInEasing)) { it / 18 }) togetherWith
                    (fadeOut(tween(MotionQuickMillis, easing = FastOutSlowInEasing)) +
                        slideOutHorizontally(tween(MotionQuickMillis, easing = FastOutSlowInEasing)) { -it / 28 })
            }
        },
        label = "productScreen",
    ) { displayedScreen ->
        val active = displayedScreen == screen
        Box(
            modifier = if (active) Modifier else Modifier
                .clearAndSetSemantics { }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                        }
                    }
                },
        ) {
            content(displayedScreen, active)
        }
    }
}
