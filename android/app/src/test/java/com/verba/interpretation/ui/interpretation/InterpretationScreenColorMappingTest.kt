package com.verba.interpretation.ui.interpretation

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.verba.interpretation.brand.BrandDarkColors
import com.verba.interpretation.brand.BrandLightColors
import com.verba.interpretation.ui.design.VerbaColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Guards the InterpretationScreen semantic color mapping. The screen reads
 * MaterialTheme.colorScheme roles instead of VerbaColors constants; BrandTheme populates
 * those roles from BrandDarkColors/BrandLightColors. These plain JVM tests pin the dark
 * scheme so the migrated roles keep rendering the exact legacy VerbaColors pixels, pin
 * light mode so it is genuinely a light variant, and document the one intentionally
 * theme-stable pair (the left-ear primary action accent).
 */
class InterpretationScreenColorMappingTest {
    private fun linearChannel(channel: Float): Double =
        if (channel <= 0.03928) {
            channel / 12.92
        } else {
            ((channel + 0.055) / 1.055).pow(2.4)
        }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * linearChannel(color.red) +
            0.7152 * linearChannel(color.green) +
            0.0722 * linearChannel(color.blue)

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val lighter = max(relativeLuminance(foreground), relativeLuminance(background))
        val darker = min(relativeLuminance(foreground), relativeLuminance(background))
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test fun darkSchemeRendersExactLegacyInterpretationColors() {
        // Screen canvas (Column background, pinned controls, empty state, language chip).
        assertEquals(VerbaColors.Canvas.toArgb(), BrandDarkColors.background.toArgb())
        // Header title/back icon on the canvas.
        assertEquals(VerbaColors.Ink.toArgb(), BrandDarkColors.onBackground.toArgb())
        // Header direction row and pinned status label.
        assertEquals(VerbaColors.Muted.toArgb(), BrandDarkColors.onSurfaceVariant.toArgb())
        // Target language label and translation text.
        assertEquals(VerbaColors.Translation.toArgb(), BrandDarkColors.primary.toArgb())
        // Transcript bubble surface, in-bubble divider and border.
        assertEquals(VerbaColors.History.toArgb(), BrandDarkColors.surfaceContainer.toArgb())
        assertEquals(VerbaColors.Ink.toArgb(), BrandDarkColors.onSurface.toArgb())
        assertEquals(VerbaColors.Divider.toArgb(), BrandDarkColors.outlineVariant.toArgb())
        assertEquals(VerbaColors.ShellStroke.toArgb(), BrandDarkColors.outline.toArgb())
        // Error card container and content.
        assertEquals(VerbaColors.ErrorSurface.toArgb(), BrandDarkColors.errorContainer.toArgb())
        assertEquals(VerbaColors.Danger.toArgb(), BrandDarkColors.onErrorContainer.toArgb())
        assertEquals(VerbaColors.Danger.toArgb(), BrandDarkColors.error.toArgb())
        // Finish (outlined) button container and content.
        assertEquals(VerbaColors.TopControl.toArgb(), BrandDarkColors.secondaryContainer.toArgb())
        assertEquals(VerbaColors.Ink.toArgb(), BrandDarkColors.onSecondaryContainer.toArgb())
    }

    @Test fun lightSchemeUsesLightCounterpartsForTheSameRoles() {
        // Every mapped role must change between schemes so the screen follows the system
        // light/dark setting instead of staying dark-themed.
        listOf(
            BrandDarkColors.background to BrandLightColors.background,
            BrandDarkColors.onBackground to BrandLightColors.onBackground,
            BrandDarkColors.onSurfaceVariant to BrandLightColors.onSurfaceVariant,
            BrandDarkColors.primary to BrandLightColors.primary,
            BrandDarkColors.surfaceContainer to BrandLightColors.surfaceContainer,
            BrandDarkColors.onSurface to BrandLightColors.onSurface,
            BrandDarkColors.outlineVariant to BrandLightColors.outlineVariant,
            BrandDarkColors.outline to BrandLightColors.outline,
            BrandDarkColors.errorContainer to BrandLightColors.errorContainer,
            BrandDarkColors.onErrorContainer to BrandLightColors.onErrorContainer,
            BrandDarkColors.error to BrandLightColors.error,
            BrandDarkColors.secondaryContainer to BrandLightColors.secondaryContainer,
            BrandDarkColors.onSecondaryContainer to BrandLightColors.onSecondaryContainer,
        ).forEachIndexed { index, (dark, light) ->
            assertNotEquals("role $index did not change between schemes", dark.toArgb(), light.toArgb())
        }
    }

    @Test fun primaryActionEarAccentPairStaysReadableInBothThemes() {
        // The primary action button intentionally keeps the left-ear identity accent
        // (VerbaColors documents its tokens as theme-stable; no colorScheme role exists).
        // The dark canvas ink painted on the accent must meet WCAG AA in light mode too,
        // because the pair does not change with the theme.
        val onAccentRatio = contrastRatio(VerbaColors.Canvas, VerbaColors.LeftMic)
        assertTrue(
            "left-ear accent content contrast ${"%.2f".format(onAccentRatio)} is below WCAG AA 4.5",
            onAccentRatio >= 4.5,
        )
    }
}
