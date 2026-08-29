package com.verba.interpretation.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.verba.interpretation.ui.design.FaceToFaceConversationIcon
import com.verba.interpretation.ui.design.VerbaColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val ratio = contrastRatio(VerbaColors.BottomNavigationLabel, VerbaColors.Background)

        assertTrue(ratio >= 4.5)
        assertTrue(ratio in 6.7..6.9)
    }

    @Test
    fun faceToFaceNavigationUsesProjectConversationVector() {
        assertEquals("FaceToFaceConversation", FaceToFaceConversationIcon.Image.name)
        assertEquals("面对面翻译", FaceToFaceConversationIcon.ContentDescription)
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
            assertTrue(ProductNavigationPolicy.showsProductBottomBar(ProductNavigationMode.USER, screen))
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
    fun hostShowsOnlyTheCorrectBottomBarForModeAndScreen() {
        listOf(
            ProductScreen.FACE_TO_FACE_WORKBENCH,
            ProductScreen.INTERPRETATION_WORKBENCH,
            ProductScreen.PROFILE,
        ).forEach { screen ->
            assertTrue(ProductNavigationPolicy.showsProductBottomBar(ProductNavigationMode.USER, screen))
        }
        assertTrue(ProductNavigationPolicy.showsProductBottomBar(ProductNavigationMode.ADMIN_TEST, ProductScreen.ADMIN_TEST))
        assertTrue(ProductNavigationPolicy.showsProductBottomBar(ProductNavigationMode.ADMIN_TEST, ProductScreen.PROFILE))

        assertFalse(ProductNavigationPolicy.showsProductBottomBar(ProductNavigationMode.AUTHENTICATION, ProductScreen.ACCOUNT))
        assertFalse(ProductNavigationPolicy.showsProductBottomBar(ProductNavigationMode.USER, ProductScreen.ACCOUNT))
        assertFalse(ProductNavigationPolicy.showsProductBottomBar(ProductNavigationMode.USER, ProductScreen.ENDPOINT_SETTINGS))
        assertFalse(ProductNavigationPolicy.showsProductBottomBar(ProductNavigationMode.ADMIN_TEST, ProductScreen.ENDPOINT_SETTINGS))
    }

    @Test
    fun onlyInterpretationHasAnExplicitExitTarget() {
        assertFalse(ProductNavigationPolicy.hasExitTarget(ProductScreen.FACE_TO_FACE_WORKBENCH))
        assertTrue(ProductNavigationPolicy.hasExitTarget(ProductScreen.INTERPRETATION_WORKBENCH))
        assertEquals(
            ProductScreen.FACE_TO_FACE_WORKBENCH,
            ProductNavigationPolicy.exitTarget(ProductScreen.INTERPRETATION_WORKBENCH),
        )
    }

    @Test
    fun accountSecondaryScreensReturnToProfilePrimaryDestination() {
        assertEquals(ProductScreen.PROFILE, ProductNavigationPolicy.exitTarget(ProductScreen.ACCOUNT))
        assertEquals(ProductScreen.PROFILE, ProductNavigationPolicy.exitTarget(ProductScreen.ENDPOINT_SETTINGS))
    }

    @Test
    fun accountSecondaryServiceSettingsUsesDedicatedEndpointRoute() {
        assertEquals(ProductScreen.ENDPOINT_SETTINGS, ProductNavigationPolicy.accountSecondaryScreen(AccountSecondaryDestination.SERVICE_SETTINGS))
        assertEquals(ProductScreen.HISTORY, ProductNavigationPolicy.accountSecondaryScreen(AccountSecondaryDestination.HISTORY))
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
        return if (normalized <= 0.04045) normalized / 12.92 else ((normalized + 0.055) / 1.055).pow(2.4)
    }

    return 0.2126 * linearize(this shr 16 and 0xFF) +
        0.7152 * linearize(this shr 8 and 0xFF) +
        0.0722 * linearize(this and 0xFF)
}
