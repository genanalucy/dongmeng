package com.verba.interpretation.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.verba.interpretation.ui.design.VerbaColors
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductNavigationPolicyTest {
    @Test
    fun userPrimaryDestinationsHaveExactSupportedOrder() {
        val destinations = ProductNavigationPolicy.destinationsFor(ProductNavigationMode.USER)

        assertEquals(
            listOf(
                ProductDestination.FACE_TO_FACE,
                ProductDestination.INTERPRETATION,
                ProductDestination.PROFILE,
            ),
            destinations,
        )
        assertFalse(ProductDestination.TRANSLATE in destinations)
        assertFalse(ProductDestination.HISTORY in destinations)
    }

    @Test
    fun bottomNavigationLabelColorMeetsSmallTextContrastRequirement() {
        assertTrue(
            contrastRatio(VerbaColors.BottomNavigationLabel, VerbaColors.Background) >= 4.5,
        )
    }

    @Test
    fun roleDrivenNavigationSelectsAuthenticationUserAndAdminExperiences() {
        assertEquals(ProductScreen.ACCOUNT, ProductNavigationPolicy.initialScreen(ProductNavigationMode.AUTHENTICATION))
        assertEquals(ProductScreen.ADMIN_TEST, ProductNavigationPolicy.initialScreen(ProductNavigationMode.ADMIN_TEST))
        assertTrue(ProductNavigationPolicy.destinationsFor(ProductNavigationMode.AUTHENTICATION).isEmpty())
        assertEquals(
            listOf(ProductDestination.ADMIN_TEST, ProductDestination.PROFILE),
            ProductNavigationPolicy.destinationsFor(ProductNavigationMode.ADMIN_TEST),
        )
    }

    @Test
    fun userNavigationDefaultsToFaceToFaceWithThreeReachableDestinations() {
        val destinations = listOf(
            ProductDestination.FACE_TO_FACE,
            ProductDestination.INTERPRETATION,
            ProductDestination.PROFILE,
        )

        assertEquals(ProductScreen.FACE_TO_FACE_WORKBENCH, ProductNavigationPolicy.initialScreen(ProductNavigationMode.USER))
        assertEquals(destinations, ProductNavigationPolicy.destinationsFor(ProductNavigationMode.USER))
        assertEquals(
            listOf(
                ProductScreen.FACE_TO_FACE_WORKBENCH,
                ProductScreen.INTERPRETATION_WORKBENCH,
                ProductScreen.PROFILE,
            ),
            destinations.map(ProductNavigationPolicy::screenFor),
        )
        destinations.map(ProductNavigationPolicy::screenFor).forEach { screen ->
            assertTrue(ProductNavigationPolicy.showsBottomBar(screen))
        }
    }

    @Test
    fun everyPrimaryDestinationMapsToItsExpectedScreen() {
        val expected = mapOf(
            ProductDestination.TRANSLATE to ProductScreen.TRANSLATE,
            ProductDestination.INTERPRETATION to ProductScreen.INTERPRETATION_WORKBENCH,
            ProductDestination.FACE_TO_FACE to ProductScreen.FACE_TO_FACE_WORKBENCH,
            ProductDestination.HISTORY to ProductScreen.HISTORY,
            ProductDestination.PROFILE to ProductScreen.PROFILE,
            ProductDestination.ADMIN_TEST to ProductScreen.ADMIN_TEST,
        )

        assertEquals(expected, ProductDestination.entries.associateWith(ProductNavigationPolicy::screenFor))
    }

    @Test
    fun onlyNonPrimaryScreensHideBottomNavigation() {
        assertFalse(ProductNavigationPolicy.showsBottomBar(ProductScreen.ENDPOINT_SETTINGS))
        assertTrue(ProductNavigationPolicy.showsBottomBar(ProductScreen.TRANSLATE))
        assertTrue(ProductNavigationPolicy.showsBottomBar(ProductScreen.HISTORY))
    }

    @Test
    fun userPrimaryScreensAreRootsWithoutBackTargets() {
        val primaryScreens = listOf(
            ProductScreen.FACE_TO_FACE_WORKBENCH,
            ProductScreen.INTERPRETATION_WORKBENCH,
            ProductScreen.PROFILE,
        )

        primaryScreens.forEach { screen ->
            assertFalse(ProductNavigationPolicy.hasExitTarget(screen))
            assertNotEquals(screen, ProductNavigationPolicy.exitTarget(screen))
            assertEquals(ProductScreen.TRANSLATE, ProductNavigationPolicy.exitTarget(screen))
        }
        assertTrue(ProductNavigationPolicy.hasExitTarget(ProductScreen.ENDPOINT_SETTINGS))
        assertEquals(ProductScreen.PROFILE, ProductNavigationPolicy.exitTarget(ProductScreen.ENDPOINT_SETTINGS))
    }

    @Test
    fun endpointSettingsKeepsProfileAsSelectedPrimaryDestination() {
        assertEquals(ProductDestination.PROFILE, ProductNavigationPolicy.selectedDestination(ProductScreen.ENDPOINT_SETTINGS))
        assertEquals(ProductDestination.ADMIN_TEST, ProductNavigationPolicy.selectedDestination(ProductScreen.ADMIN_TEST))
    }
}

private fun contrastRatio(foreground: Color, background: Color): Double {
    val foregroundLuminance = foreground.toArgb().relativeLuminance()
    val backgroundLuminance = background.toArgb().relativeLuminance()
    return (max(foregroundLuminance, backgroundLuminance) + 0.05) /
        (min(foregroundLuminance, backgroundLuminance) + 0.05)
}

private fun Int.relativeLuminance(): Double {
    fun linearize(component: Int): Double {
        val normalized = component / 255.0
        return if (normalized <= 0.04045) normalized / 12.92 else ((normalized + 0.055) / 1.055).let { it * it * it }
    }

    return 0.2126 * linearize(this shr 16 and 0xFF) +
        0.7152 * linearize(this shr 8 and 0xFF) +
        0.0722 * linearize(this and 0xFF)
}
