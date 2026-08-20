package com.verba.interpretation.brand

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BrandLightColors = lightColorScheme(
    primary = BrandConfig.primary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB9EDEA),
    onPrimaryContainer = Color(0xFF00201F),
    secondary = BrandConfig.secondary,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD0E8E3),
    onSecondaryContainer = Color(0xFF0A1F1C),
    background = Color(0xFFF7FAF9),
    onBackground = Color(0xFF191C1C),
    surface = Color(0xFFF7FAF9),
    onSurface = Color(0xFF191C1C),
    surfaceVariant = Color(0xFFDAE5E2),
    onSurfaceVariant = Color(0xFF3F4947),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFBEC9C6),
)

@Composable
fun BrandTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BrandLightColors, content = content)
}
