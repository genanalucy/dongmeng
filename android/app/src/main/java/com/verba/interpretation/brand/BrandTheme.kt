package com.verba.interpretation.brand

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.verba.interpretation.ui.design.VerbaColors

private val BrandDarkColors = darkColorScheme(
    primary = VerbaColors.Translation,
    onPrimary = VerbaColors.Background,
    primaryContainer = VerbaColors.Raised,
    onPrimaryContainer = VerbaColors.Ink,
    secondary = VerbaColors.Muted,
    onSecondary = VerbaColors.Background,
    secondaryContainer = VerbaColors.TopControl,
    onSecondaryContainer = VerbaColors.Ink,
    background = VerbaColors.Background,
    onBackground = VerbaColors.Ink,
    surface = VerbaColors.History,
    onSurface = VerbaColors.Ink,
    surfaceVariant = VerbaColors.TopControl,
    onSurfaceVariant = VerbaColors.Muted,
    outline = VerbaColors.ShellStroke,
    outlineVariant = VerbaColors.Divider,
    error = VerbaColors.Danger,
    errorContainer = VerbaColors.ErrorSurface,
    onErrorContainer = VerbaColors.Danger,
)

private val BrandTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 43.sp,
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
        fontSize = 19.sp,
        lineHeight = 26.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 19.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun BrandTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BrandDarkColors, typography = BrandTypography, content = content)
}
