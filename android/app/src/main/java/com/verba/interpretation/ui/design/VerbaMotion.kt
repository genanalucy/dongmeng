package com.verba.interpretation.ui.design

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode

internal const val MotionQuickMillis = 140
internal const val MotionScreenMillis = 220

@Composable
internal fun motionEnabled(): Boolean =
    ValueAnimator.areAnimatorsEnabled() && !LocalInspectionMode.current

internal fun motionEnter(): EnterTransition =
    fadeIn(tween(MotionScreenMillis, easing = FastOutSlowInEasing)) +
        slideInVertically(tween(MotionScreenMillis, easing = FastOutSlowInEasing)) { it / 24 }

internal fun motionExit(): ExitTransition =
    fadeOut(tween(MotionQuickMillis, easing = FastOutSlowInEasing)) +
        slideOutVertically(tween(MotionQuickMillis, easing = FastOutSlowInEasing)) { -it / 32 }

/** Reveals stable list content once without replaying for live transcript text updates. */
@Composable
fun GlassReveal(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val enabled = motionEnabled()
    var visible by remember { mutableStateOf(!enabled) }
    LaunchedEffect(enabled) { visible = true }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = if (enabled) motionEnter() else EnterTransition.None,
        exit = ExitTransition.None,
        content = { content() },
    )
}
