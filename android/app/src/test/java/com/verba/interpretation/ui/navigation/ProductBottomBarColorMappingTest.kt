package com.verba.interpretation.ui.navigation

import androidx.compose.ui.graphics.toArgb
import com.verba.interpretation.brand.BrandDarkColors
import com.verba.interpretation.brand.BrandLightColors
import com.verba.interpretation.ui.design.VerbaColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Guards the ProductBottomBar semantic color mapping. The bar no longer reads
 * [VerbaColors] directly; it reads MaterialTheme.colorScheme roles, which
 * BrandTheme populates from BrandDarkColors/BrandLightColors. These plain JVM
 * tests pin the dark scheme so the five roles keep rendering the exact legacy
 * VerbaColors pixels, and they pin light mode so it is genuinely a light variant.
 */
class ProductBottomBarColorMappingTest {
    @Test fun darkSchemeRendersExactLegacyBottomBarColors() {
        // VerbaColors.Navigation -> surfaceContainerLowest (bar + unselected item background)
        assertEquals(VerbaColors.Navigation.toArgb(), BrandDarkColors.surfaceContainerLowest.toArgb())
        // VerbaColors.Raised -> primaryContainer (selected item background)
        assertEquals(VerbaColors.Raised.toArgb(), BrandDarkColors.primaryContainer.toArgb())
        // VerbaColors.Translation -> primary (selected icon/label tint)
        assertEquals(VerbaColors.Translation.toArgb(), BrandDarkColors.primary.toArgb())
        // VerbaColors.Muted -> onSurfaceVariant (unselected icon/label tint)
        assertEquals(VerbaColors.Muted.toArgb(), BrandDarkColors.onSurfaceVariant.toArgb())
        // VerbaColors.ShellStroke -> outline. outlineVariant is VerbaColors.Divider,
        // a different tone, so outline is the correct role for the bar stroke.
        assertNotEquals(VerbaColors.ShellStroke.toArgb(), BrandDarkColors.outlineVariant.toArgb())
        assertEquals(VerbaColors.ShellStroke.toArgb(), BrandDarkColors.outline.toArgb())
    }

    @Test fun lightSchemeUsesLightCounterpartsForTheSameRoles() {
        // Every mapped role must change between schemes so the bar follows the
        // system light/dark setting instead of staying dark-themed.
        listOf(
            BrandDarkColors.surfaceContainerLowest to BrandLightColors.surfaceContainerLowest,
            BrandDarkColors.primaryContainer to BrandLightColors.primaryContainer,
            BrandDarkColors.primary to BrandLightColors.primary,
            BrandDarkColors.onSurfaceVariant to BrandLightColors.onSurfaceVariant,
            BrandDarkColors.outline to BrandLightColors.outline,
        ).forEachIndexed { index, (dark, light) ->
            assertNotEquals("role $index did not change between schemes", dark.toArgb(), light.toArgb())
        }
    }
}
