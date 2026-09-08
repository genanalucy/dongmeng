package com.verba.interpretation.brand

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.verba.interpretation.ui.design.VerbaColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Guards the WCAG AA contrast contract of both brand color schemes, including the
 * explicit surface-container ramps and inverse roles. These are plain JVM tests:
 * Color/ColorScheme arithmetic has no Android framework dependency.
 */
class BrandThemeColorContrastTest {
    private data class ContentPair(val name: String, val foreground: Color, val background: Color)

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

    private fun assertWcagAa(pairs: List<ContentPair>, scheme: String) {
        pairs.forEach { pair ->
            val ratio = contrastRatio(pair.foreground, pair.background)
            assertTrue(
                "$scheme ${pair.name} contrast ${"%.2f".format(ratio)} is below WCAG AA 4.5",
                ratio >= 4.5,
            )
        }
    }

    private fun contentPairs(scheme: ColorScheme): List<ContentPair> = listOf(
        ContentPair("onPrimary/primary", scheme.onPrimary, scheme.primary),
        ContentPair("onPrimaryContainer/primaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer),
        ContentPair("onSecondary/secondary", scheme.onSecondary, scheme.secondary),
        ContentPair("onSecondaryContainer/secondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer),
        ContentPair("onBackground/background", scheme.onBackground, scheme.background),
        ContentPair("onSurface/surface", scheme.onSurface, scheme.surface),
        ContentPair("onSurface/surfaceContainerLowest", scheme.onSurface, scheme.surfaceContainerLowest),
        ContentPair("onSurface/surfaceContainerLow", scheme.onSurface, scheme.surfaceContainerLow),
        ContentPair("onSurface/surfaceContainer", scheme.onSurface, scheme.surfaceContainer),
        ContentPair("onSurface/surfaceContainerHigh", scheme.onSurface, scheme.surfaceContainerHigh),
        ContentPair("onSurface/surfaceContainerHighest", scheme.onSurface, scheme.surfaceContainerHighest),
        ContentPair("onSurfaceVariant/surfaceVariant", scheme.onSurfaceVariant, scheme.surfaceVariant),
        // AccountScreen cards put primary-tinted icons/labels and variant supporting
        // text on surfaceContainerLow/High containers.
        ContentPair("primary on surfaceContainerLow", scheme.primary, scheme.surfaceContainerLow),
        ContentPair("primary on surfaceContainerHighest", scheme.primary, scheme.surfaceContainerHighest),
        ContentPair("onSurfaceVariant on surfaceContainerLow", scheme.onSurfaceVariant, scheme.surfaceContainerLow),
        ContentPair("onSurfaceVariant on surfaceContainerHighest", scheme.onSurfaceVariant, scheme.surfaceContainerHighest),
        ContentPair("inverseOnSurface/inverseSurface", scheme.inverseOnSurface, scheme.inverseSurface),
        ContentPair("inversePrimary on inverseSurface", scheme.inversePrimary, scheme.inverseSurface),
        ContentPair("onError/error", scheme.onError, scheme.error),
        ContentPair("onErrorContainer/errorContainer", scheme.onErrorContainer, scheme.errorContainer),
        // primary/error also act as text or icon tints on top-level surfaces.
        ContentPair("primary on background", scheme.primary, scheme.background),
        ContentPair("primary on surface", scheme.primary, scheme.surface),
        ContentPair("error on background", scheme.error, scheme.background),
        ContentPair("error on surface", scheme.error, scheme.surface),
    )

    @Test fun darkSchemeContentPairsMeetWcagAa() {
        assertWcagAa(contentPairs(BrandDarkColors), "dark")
    }

    @Test fun lightSchemeContentPairsMeetWcagAa() {
        assertWcagAa(contentPairs(BrandLightColors), "light")
    }

    @Test fun darkSchemeKeepsEstablishedBrandValues() {
        val scheme = BrandDarkColors
        assertEquals(0xFFFFC46B.toInt(), scheme.primary.toArgb())
        assertEquals(0xFF07111F.toInt(), scheme.onPrimary.toArgb())
        assertEquals(0xFF303947.toInt(), scheme.primaryContainer.toArgb())
        assertEquals(0xFFABB5C3.toInt(), scheme.secondary.toArgb())
        assertEquals(0xFF151F2B.toInt(), scheme.secondaryContainer.toArgb())
        assertEquals(0xFF07111F.toInt(), scheme.background.toArgb())
        assertEquals(0xFF202630.toInt(), scheme.surface.toArgb())
        assertEquals(0xFF151F2B.toInt(), scheme.surfaceVariant.toArgb())
        assertEquals(0xFF36404C.toInt(), scheme.outline.toArgb())
        assertEquals(0xFF48515E.toInt(), scheme.outlineVariant.toArgb())
        assertEquals(0xFFFFDAD6.toInt(), scheme.error.toArgb())
        assertEquals(0xFF35242A.toInt(), scheme.errorContainer.toArgb())
    }

    @Test fun lightSchemeMirrorsDarkRolesInsteadOfReusingDarkSurfaces() {
        // The light scheme must genuinely be a light variant, not a copy of the dark one.
        assertEquals(VerbaColors.Danger.toArgb(), BrandLightColors.errorContainer.toArgb())
        listOf(
            BrandDarkColors.background to BrandLightColors.background,
            BrandDarkColors.surface to BrandLightColors.surface,
            BrandDarkColors.onSurface to BrandLightColors.onSurface,
            BrandDarkColors.primary to BrandLightColors.primary,
            BrandDarkColors.error to BrandLightColors.error,
        ).forEachIndexed { index, (dark, light) ->
            assertTrue("role $index did not change between schemes", dark.toArgb() != light.toArgb())
        }
        // Light surfaces stay light so dark-scheme surfaces cannot leak into light mode.
        assertTrue(relativeLuminance(BrandLightColors.background) > 0.8)
        assertTrue(relativeLuminance(BrandLightColors.surface) > 0.8)
    }

    @Test fun darkSchemeMapsContainerRolesOntoBrandSurfaceHierarchy() {
        val scheme = BrandDarkColors
        // Previously unspecified container roles map onto the established dark brand
        // surface hierarchy; surfaceContainerHigh is the single derived midpoint
        // between History and Raised so the five-step ramp stays monotonic.
        assertEquals(0xFF07111F.toInt(), scheme.surfaceDim.toArgb())
        assertEquals(0xFF303947.toInt(), scheme.surfaceBright.toArgb())
        assertEquals(0xFF141D28.toInt(), scheme.surfaceContainerLowest.toArgb())
        assertEquals(0xFF151F2B.toInt(), scheme.surfaceContainerLow.toArgb())
        assertEquals(0xFF202630.toInt(), scheme.surfaceContainer.toArgb())
        assertEquals(0xFF28303C.toInt(), scheme.surfaceContainerHigh.toArgb())
        assertEquals(0xFF303947.toInt(), scheme.surfaceContainerHighest.toArgb())
        assertEquals(0xFFE9EDF3.toInt(), scheme.inverseSurface.toArgb())
        assertEquals(0xFF171C24.toInt(), scheme.inverseOnSurface.toArgb())
        assertEquals(0xFF8A4F00.toInt(), scheme.inversePrimary.toArgb())
        assertEquals(VerbaColors.Translation.toArgb(), scheme.surfaceTint.toArgb())
        // The ramp anchors onto the established dark surface tones at both ends.
        assertEquals(scheme.background.toArgb(), scheme.surfaceDim.toArgb())
        assertEquals(scheme.surface.toArgb(), scheme.surfaceContainer.toArgb())
    }

    @Test fun lightSchemeDefinesContainerRampInsteadOfM3PurpleDefaults() {
        val scheme = BrandLightColors
        assertEquals(0xFFD8DEE7.toInt(), scheme.surfaceDim.toArgb())
        assertEquals(0xFFFCFDFE.toInt(), scheme.surfaceBright.toArgb())
        assertEquals(0xFFFAFBFD.toInt(), scheme.surfaceContainerLowest.toArgb())
        assertEquals(0xFFF1F4F9.toInt(), scheme.surfaceContainerLow.toArgb())
        assertEquals(0xFFE9EDF3.toInt(), scheme.surfaceContainer.toArgb())
        assertEquals(0xFFE4E9F0.toInt(), scheme.surfaceContainerHigh.toArgb())
        assertEquals(0xFFDEE3EB.toInt(), scheme.surfaceContainerHighest.toArgb())
        assertEquals(VerbaColors.History.toArgb(), scheme.inverseSurface.toArgb())
        assertEquals(VerbaColors.Ink.toArgb(), scheme.inverseOnSurface.toArgb())
        assertEquals(VerbaColors.Translation.toArgb(), scheme.inversePrimary.toArgb())
        assertEquals(scheme.primary.toArgb(), scheme.surfaceTint.toArgb())
        // The account screen cards must never fall back to the purple-tinted M3
        // baseline values that lightColorScheme() otherwise substitutes.
        val actual = mapOf(
            "surfaceDim" to scheme.surfaceDim.toArgb(),
            "surfaceBright" to scheme.surfaceBright.toArgb(),
            "surfaceContainerLowest" to scheme.surfaceContainerLowest.toArgb(),
            "surfaceContainerLow" to scheme.surfaceContainerLow.toArgb(),
            "surfaceContainer" to scheme.surfaceContainer.toArgb(),
            "surfaceContainerHigh" to scheme.surfaceContainerHigh.toArgb(),
            "surfaceContainerHighest" to scheme.surfaceContainerHighest.toArgb(),
            "inverseSurface" to scheme.inverseSurface.toArgb(),
            "inverseOnSurface" to scheme.inverseOnSurface.toArgb(),
            "inversePrimary" to scheme.inversePrimary.toArgb(),
            "surfaceTint" to scheme.surfaceTint.toArgb(),
        )
        val m3LightDefaults = mapOf(
            "surfaceDim" to 0xFFDED8E1.toInt(),
            "surfaceBright" to 0xFFFDF8FD.toInt(),
            "surfaceContainerLowest" to 0xFFFFFFFF.toInt(),
            "surfaceContainerLow" to 0xFFF7F2FA.toInt(),
            "surfaceContainer" to 0xFFF1ECF4.toInt(),
            "surfaceContainerHigh" to 0xFFEBE6EE.toInt(),
            "surfaceContainerHighest" to 0xFFE5E1E9.toInt(),
            "inverseSurface" to 0xFF313033.toInt(),
            "inverseOnSurface" to 0xFFF4EFF4.toInt(),
            "inversePrimary" to 0xFF6750A4.toInt(),
            "surfaceTint" to 0xFF6750A4.toInt(),
        )
        m3LightDefaults.forEach { (role, m3Default) ->
            assertTrue(
                "light $role must be brand-defined, not the M3 default 0x${Integer.toHexString(m3Default)}",
                actual[role] != m3Default,
            )
        }
        // The ramp ties into the established light surface tones.
        assertEquals(scheme.surface.toArgb(), scheme.surfaceContainer.toArgb())
        assertEquals(scheme.surfaceVariant.toArgb(), scheme.surfaceContainerHighest.toArgb())
    }

    @Test fun containerRampsStayMonotonicInBothSchemes() {
        val containerWalk = listOf(
            "surfaceContainerLowest",
            "surfaceContainerLow",
            "surfaceContainer",
            "surfaceContainerHigh",
            "surfaceContainerHighest",
        )
        fun roleColor(scheme: ColorScheme, role: String): Color = when (role) {
            "surfaceContainerLowest" -> scheme.surfaceContainerLowest
            "surfaceContainerLow" -> scheme.surfaceContainerLow
            "surfaceContainer" -> scheme.surfaceContainer
            "surfaceContainerHigh" -> scheme.surfaceContainerHigh
            else -> scheme.surfaceContainerHighest
        }
        // Dark: container luminance rises with elevation (Lowest -> Highest).
        BrandDarkColors.let { scheme ->
            containerWalk.zipWithNext().forEach { (upper, lower) ->
                assertTrue(
                    "dark container ramp $upper -> $lower must not drop luminance",
                    relativeLuminance(roleColor(scheme, lower)) + 1e-9 >=
                        relativeLuminance(roleColor(scheme, upper)),
                )
            }
        }
        // Light: container luminance falls with elevation (Lowest -> Highest).
        BrandLightColors.let { scheme ->
            containerWalk.zipWithNext().forEach { (upper, lower) ->
                assertTrue(
                    "light container ramp $upper -> $lower must not raise luminance",
                    relativeLuminance(roleColor(scheme, upper)) + 1e-9 >=
                        relativeLuminance(roleColor(scheme, lower)),
                )
            }
        }
        // In both schemes surfaceDim is the darkest and surfaceBright the lightest role.
        listOf("dark" to BrandDarkColors, "light" to BrandLightColors).forEach { (name, scheme) ->
            val dims = containerWalk.map { relativeLuminance(roleColor(scheme, it)) }
            assertTrue(
                "$name surfaceDim must not be lighter than any container",
                dims.min() + 1e-9 >= relativeLuminance(scheme.surfaceDim),
            )
            assertTrue(
                "$name surfaceBright must not be darker than any container",
                relativeLuminance(scheme.surfaceBright) + 1e-9 >= dims.max(),
            )
        }
    }

    @Test fun outlinesRemainVisibleAgainstSurfacesInBothSchemes() {
        // The brand intentionally uses subtle strokes (dark outline is ~1.8:1 on the
        // background); the light outlines keep comparable visibility instead of 4.5:1.
        listOf(
            "dark outline" to contrastRatio(BrandDarkColors.outline, BrandDarkColors.background),
            "dark outlineVariant" to contrastRatio(BrandDarkColors.outlineVariant, BrandDarkColors.background),
            "light outline" to contrastRatio(BrandLightColors.outline, BrandLightColors.background),
            "light outlineVariant" to contrastRatio(BrandLightColors.outlineVariant, BrandLightColors.surface),
        ).forEach { (name, ratio) ->
            assertTrue("$name contrast ${"%.2f".format(ratio)} is below 1.5", ratio >= 1.5)
        }
    }
}
