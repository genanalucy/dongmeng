package com.verba.interpretation.ui.history

import androidx.compose.ui.graphics.toArgb
import com.verba.interpretation.brand.BrandDarkColors
import com.verba.interpretation.brand.BrandLightColors
import com.verba.interpretation.ui.design.VerbaColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Guards the LocalHistorySaveFeedback semantic color mapping. The footer surface and
 * border read MaterialTheme.colorScheme roles instead of VerbaColors constants (the
 * message text already read colorScheme before). These plain JVM tests pin the dark
 * scheme to the exact legacy VerbaColors pixels and pin light mode as a genuinely light
 * variant. See ProductBottomBarColorMappingTest for the same pattern.
 */
class LocalHistorySaveFeedbackColorMappingTest {
    @Test fun darkSchemeRendersExactLegacyFooterColors() {
        // VerbaColors.TopControl -> secondaryContainer (footer surface)
        assertEquals(VerbaColors.TopControl.toArgb(), BrandDarkColors.secondaryContainer.toArgb())
        // VerbaColors.ShellStroke -> outline (footer border)
        assertEquals(VerbaColors.ShellStroke.toArgb(), BrandDarkColors.outline.toArgb())
    }

    @Test fun lightSchemeUsesLightCounterpartsForTheSameRoles() {
        listOf(
            BrandDarkColors.secondaryContainer to BrandLightColors.secondaryContainer,
            BrandDarkColors.outline to BrandLightColors.outline,
        ).forEachIndexed { index, (dark, light) ->
            assertNotEquals("role $index did not change between schemes", dark.toArgb(), light.toArgb())
        }
    }
}
