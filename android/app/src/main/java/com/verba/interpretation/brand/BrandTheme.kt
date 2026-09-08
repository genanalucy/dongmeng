package com.verba.interpretation.brand

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.verba.interpretation.ui.design.VerbaColors

/**
 * Dark brand scheme built from the translation-shell [VerbaColors] tokens. The
 * surface-container roles consume the existing dark brand surface hierarchy
 * (Background -> Navigation -> TopControl -> History -> Raised):
 * `surfaceContainerHigh` is the only derived value, the midpoint between History
 * and Raised, so the five-step container ramp stays monotonic. Inverse roles
 * mirror [BrandLightColors] tones so snackbars stay readable in dark mode, and
 * `surfaceTint` follows the brand primary instead of the M3 default.
 */
internal val BrandDarkColors = darkColorScheme(
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
    surfaceDim = VerbaColors.Background,
    surfaceBright = VerbaColors.Raised,
    surfaceContainerLowest = VerbaColors.Navigation,
    surfaceContainerLow = VerbaColors.TopControl,
    surfaceContainer = VerbaColors.History,
    surfaceContainerHigh = Color(0xFF28303C),
    surfaceContainerHighest = VerbaColors.Raised,
    inverseSurface = Color(0xFFE9EDF3),
    inverseOnSurface = Color(0xFF171C24),
    inversePrimary = Color(0xFF8A4F00),
    surfaceTint = VerbaColors.Translation,
)

/**
 * Light counterpart of [BrandDarkColors]. It mirrors the dark palette hue-for-hue:
 * the navy/blue-gray neutrals flip to cool near-white surfaces with dark ink content,
 * the brand amber is deepened so it stays readable as text on light surfaces, and
 * [VerbaColors.Danger] (the dark theme's error text tone) is reused as the light
 * error container. Every content/container pair meets WCAG AA (>= 4.5:1); see
 * BrandThemeColorContrastTest.
 *
 * The surface-container ramp is defined explicitly (anchored on `surface` and
 * `surfaceVariant`) so screens like AccountScreen that read `surfaceContainerLow`
 * or `surfaceContainerHigh` never fall back to the purple-tinted M3 baseline
 * containers of `lightColorScheme()`. Inverse roles mirror the dark scheme tones
 * and `surfaceTint` follows the light primary for the same reason.
 */
internal val BrandLightColors = lightColorScheme(
    primary = Color(0xFF8A4F00),
    onPrimary = Color(0xFFFFF8EE),
    primaryContainer = Color(0xFFDDE2EA),
    onPrimaryContainer = Color(0xFF171C24),
    secondary = Color(0xFF45505F),
    onSecondary = Color(0xFFF5F7FA),
    secondaryContainer = Color(0xFFDEE3EB),
    onSecondaryContainer = Color(0xFF171C24),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF171C24),
    surface = Color(0xFFE9EDF3),
    onSurface = Color(0xFF171C24),
    surfaceVariant = Color(0xFFDEE3EB),
    onSurfaceVariant = Color(0xFF45505F),
    outline = Color(0xFFB0BAC8),
    outlineVariant = Color(0xFFA5B0BF),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFF8F7),
    errorContainer = VerbaColors.Danger,
    onErrorContainer = Color(0xFF4A0F08),
    surfaceDim = Color(0xFFD8DEE7),
    surfaceBright = Color(0xFFFCFDFE),
    surfaceContainerLowest = Color(0xFFFAFBFD),
    surfaceContainerLow = Color(0xFFF1F4F9),
    surfaceContainer = Color(0xFFE9EDF3),
    surfaceContainerHigh = Color(0xFFE4E9F0),
    surfaceContainerHighest = Color(0xFFDEE3EB),
    inverseSurface = VerbaColors.History,
    inverseOnSurface = VerbaColors.Ink,
    inversePrimary = VerbaColors.Translation,
    surfaceTint = Color(0xFF8A4F00),
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

/**
 * Brand theme following the system dark/light setting. `darkTheme` defaults to the
 * system value and stays overridable for previews and tests; existing callers that
 * use `BrandTheme { ... }` keep working unchanged.
 */
@Composable
fun BrandTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) BrandDarkColors else BrandLightColors
    MaterialTheme(colorScheme = colorScheme, typography = BrandTypography, content = content)
}
