package com.verba.interpretation.brand

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17201F),
    surfaceVariant = Color(0xFFE5EFED),
    onSurfaceVariant = Color(0xFF3F4947),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFCBD6D3),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val BrandTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 43.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 35.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp,
    ),
)

@Composable
fun BrandTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BrandLightColors, typography = BrandTypography, content = content)
}
