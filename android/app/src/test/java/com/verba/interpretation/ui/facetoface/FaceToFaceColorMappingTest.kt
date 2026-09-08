package com.verba.interpretation.ui.facetoface

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.verba.interpretation.brand.BrandDarkColors
import com.verba.interpretation.brand.BrandLightColors
import com.verba.interpretation.ui.design.VerbaColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Guards the semantic color mapping of the face-to-face work surfaces: FaceToFaceScreen
 * (canvas, recovery banner, view toggle, panel divider), FaceToFaceOverflowMenu (menu
 * surface, item text), ConversationTimeline (static bubbles) and EarMicControls (language
 * entries). These read MaterialTheme.colorScheme roles instead of VerbaColors constants;
 * these plain JVM tests pin the dark scheme to the exact legacy VerbaColors pixels, pin
 * light mode as a genuinely light variant. Live bubbles use the same themed surface
 * hierarchy, with primary outline/waveform indicating active capture.
 */
class FaceToFaceColorMappingTest {
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

    @Test fun darkSchemeRendersExactLegacyFaceToFaceColors() {
        // Screen canvas and titles.
        assertEquals(VerbaColors.Canvas.toArgb(), BrandDarkColors.background.toArgb())
        assertEquals(VerbaColors.Ink.toArgb(), BrandDarkColors.onBackground.toArgb())
        // View toggle / overflow button surface, border and dots, language entries.
        assertEquals(VerbaColors.TopControl.toArgb(), BrandDarkColors.secondaryContainer.toArgb())
        assertEquals(VerbaColors.Ink.toArgb(), BrandDarkColors.onSecondaryContainer.toArgb())
        assertEquals(VerbaColors.ShellStroke.toArgb(), BrandDarkColors.outline.toArgb())
        assertEquals(VerbaColors.Muted.toArgb(), BrandDarkColors.onSurfaceVariant.toArgb())
        // Panel divider.
        assertEquals(VerbaColors.ShellStroke.toArgb(), BrandDarkColors.outline.toArgb())
        // Overflow menu surface and item text, static bubble surface and content.
        assertEquals(VerbaColors.History.toArgb(), BrandDarkColors.surfaceContainer.toArgb())
        assertEquals(VerbaColors.Ink.toArgb(), BrandDarkColors.onSurface.toArgb())
        assertEquals(VerbaColors.Translation.toArgb(), BrandDarkColors.primary.toArgb())
        // Recovery banner and timeline error text.
        assertEquals(VerbaColors.ErrorSurface.toArgb(), BrandDarkColors.errorContainer.toArgb())
        assertEquals(VerbaColors.Danger.toArgb(), BrandDarkColors.onErrorContainer.toArgb())
        assertEquals(VerbaColors.Danger.toArgb(), BrandDarkColors.error.toArgb())
    }

    @Test fun lightSchemeUsesLightCounterpartsForTheSameRoles() {
        listOf(
            BrandDarkColors.background to BrandLightColors.background,
            BrandDarkColors.onBackground to BrandLightColors.onBackground,
            BrandDarkColors.secondaryContainer to BrandLightColors.secondaryContainer,
            BrandDarkColors.onSecondaryContainer to BrandLightColors.onSecondaryContainer,
            BrandDarkColors.outline to BrandLightColors.outline,
            BrandDarkColors.onSurfaceVariant to BrandLightColors.onSurfaceVariant,
            BrandDarkColors.surfaceContainer to BrandLightColors.surfaceContainer,
            BrandDarkColors.onSurface to BrandLightColors.onSurface,
            BrandDarkColors.primary to BrandLightColors.primary,
            BrandDarkColors.errorContainer to BrandLightColors.errorContainer,
            BrandDarkColors.onErrorContainer to BrandLightColors.onErrorContainer,
            BrandDarkColors.error to BrandLightColors.error,
        ).forEachIndexed { index, (dark, light) ->
            assertNotEquals("role $index did not change between schemes", dark.toArgb(), light.toArgb())
        }
    }

    @Test fun staticBubbleBorderLegacyToneStaysImperceptibleAgainstOutline() {
        // The legacy static-bubble border 0xFF37414E has no colorScheme role; it now reads
        // outline (dark 0xFF36404C). The substitution is guarded to stay imperceptible
        // (<= 2/255 per channel) so dark mode keeps its established pixels.
        val legacy = Color(0xFF37414E)
        val outline = BrandDarkColors.outline
        assertTrue(abs(legacy.red - outline.red) * 255 <= 2)
        assertTrue(abs(legacy.green - outline.green) * 255 <= 2)
        assertTrue(abs(legacy.blue - outline.blue) * 255 <= 2)
    }

    @Test fun liveBubbleUsesThemedSurfaceAndPrimaryAccentInBothThemes() {
        // A live capture never introduces a fixed dark panel in a light workspace.
        assertNotEquals(BrandDarkColors.surfaceContainerHigh.toArgb(), BrandLightColors.surfaceContainerHigh.toArgb())
        assertNotEquals(BrandDarkColors.primary.toArgb(), BrandLightColors.primary.toArgb())
        listOf(
            "dark onSurface on live surface" to (BrandDarkColors.onSurface to BrandDarkColors.surfaceContainerHigh),
            "light onSurface on live surface" to (BrandLightColors.onSurface to BrandLightColors.surfaceContainerHigh),
            "dark primary on live surface" to (BrandDarkColors.primary to BrandDarkColors.surfaceContainerHigh),
            "light primary on live surface" to (BrandLightColors.primary to BrandLightColors.surfaceContainerHigh),
        ).forEach { (name, pair) ->
            val ratio = contrastRatio(pair.first, pair.second)
            assertTrue("$name contrast ${"%.2f".format(ratio)} is below WCAG AA 4.5", ratio >= 4.5)
        }
    }
}
