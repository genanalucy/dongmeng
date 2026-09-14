package com.verba.interpretation.ui.design

import android.animation.ValueAnimator
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp

/**
 * A lightweight glass treatment: translucent layers and a moving press highlight,
 * deliberately without backdrop blur so transcript lists remain smooth on older devices.
 */
@Composable
fun LiquidGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.medium,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val animationsEnabled = ValueAnimator.areAnimatorsEnabled() && !LocalInspectionMode.current
    val scale by animateFloatAsState(
        targetValue = if (animationsEnabled && pressed) 0.985f else 1f,
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "liquidGlassPressScale",
    )
    val highlightAlpha by animateFloatAsState(
        targetValue = if (animationsEnabled && pressed) 0.20f else 0.10f,
        animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
        label = "liquidGlassHighlight",
    )
    val colors = MaterialTheme.colorScheme
    val glass = colors.surfaceContainerLow.copy(alpha = 0.84f)
    val border = Brush.linearGradient(
        listOf(
            colors.surfaceBright.copy(alpha = highlightAlpha + 0.25f),
            colors.outline.copy(alpha = 0.42f),
            colors.primary.copy(alpha = 0.18f),
        ),
    )
    val base = modifier
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(shape)
        .background(glass)
        .border(1.dp, border, shape)
    Box(
        modifier = if (onClick != null) {
            base.clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
        } else base,
    ) {
        content()
    }
}
