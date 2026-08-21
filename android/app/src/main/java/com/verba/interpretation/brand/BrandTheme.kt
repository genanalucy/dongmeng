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
    primaryContainer = Color(0xFFD7F0EA),
    onPrimaryContainer = Color(0xFF063A36),
    secondary = BrandConfig.secondary,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0EAE7),
    onSecondaryContainer = Color(0xFF21302D),
    background = Color(0xFFF4F7F6),
    onBackground = Color(0xFF14211F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14211F),
    surfaceVariant = Color(0xFFEEF3F1),
    onSurfaceVariant = Color(0xFF60706C),
    outline = Color(0xFF71817C),
    outlineVariant = Color(0xFFD9E3E0),
    errorContainer = Color(0xFFF8DEDB),
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
