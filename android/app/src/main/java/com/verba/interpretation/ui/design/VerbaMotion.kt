package com.verba.interpretation.ui.design

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp

internal const val MotionQuickMillis = 140
internal const val MotionScreenMillis = 220

@Composable
internal fun motionEnabled(): Boolean =
    ValueAnimator.areAnimatorsEnabled() && !LocalInspectionMode.current

/** Stable lazy-item keys restore this flag after recycling; text updates never restart entry. */
@Composable
fun GlassReveal(
    revealKey: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val enabled = motionEnabled()
    var revealed by rememberSaveable(revealKey) { mutableStateOf(!enabled) }
    val progress by animateFloatAsState(
        targetValue = if (revealed || !enabled) 1f else 0f,
        animationSpec = tween(MotionScreenMillis, easing = FastOutSlowInEasing),
        label = "glassReveal",
    )
    LaunchedEffect(revealKey) { revealed = true }
    // Always measure/place content at its final size; only its drawing layer moves.
    Box(modifier.graphicsLayer {
        alpha = if (enabled) progress else 1f
        translationY = if (enabled) (1f - progress) * 8.dp.toPx() else 0f
    }) {
        content()
    }
}
