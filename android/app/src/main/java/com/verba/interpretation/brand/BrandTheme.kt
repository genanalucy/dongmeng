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
    primaryContainer = BrandConfig.primarySoft,
    onPrimaryContainer = BrandConfig.text,
    secondary = BrandConfig.secondary,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = BrandConfig.surface,
    onSecondaryContainer = BrandConfig.text,
    background = BrandConfig.background,
    onBackground = BrandConfig.text,
    surface = BrandConfig.surface,
    onSurface = BrandConfig.text,
    surfaceVariant = BrandConfig.surface,
    onSurfaceVariant = BrandConfig.secondary,
    outline = BrandConfig.secondary,
    outlineVariant = Color(0xFFD9DEE7),
    error = Color(0xFFBA1A1A),
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
