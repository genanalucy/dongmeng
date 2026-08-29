package com.verba.interpretation.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductNavigationPolicyTest {
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
    fun userNavigationDefaultsToFaceToFaceWithThreeDestinations() {
        assertEquals(ProductScreen.FACE_TO_FACE_WORKBENCH, ProductNavigationPolicy.initialScreen(ProductNavigationMode.USER))
        assertEquals(
            listOf(
                ProductDestination.FACE_TO_FACE,
                ProductDestination.INTERPRETATION,
                ProductDestination.PROFILE,
            ),
            ProductNavigationPolicy.destinationsFor(ProductNavigationMode.USER),
        )
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
    fun immersiveWorkbenchesAndSettingsHideBottomNavigation() {
        assertFalse(ProductNavigationPolicy.showsBottomBar(ProductScreen.INTERPRETATION_WORKBENCH))
        assertFalse(ProductNavigationPolicy.showsBottomBar(ProductScreen.FACE_TO_FACE_WORKBENCH))
        assertFalse(ProductNavigationPolicy.showsBottomBar(ProductScreen.ENDPOINT_SETTINGS))

        assertTrue(ProductNavigationPolicy.showsBottomBar(ProductScreen.TRANSLATE))
        assertTrue(ProductNavigationPolicy.showsBottomBar(ProductScreen.HISTORY))
        assertTrue(ProductNavigationPolicy.showsBottomBar(ProductScreen.PROFILE))
    }

    @Test
    fun systemBackExitsSecondLevelScreensWithoutLeavingTheActivity() {
        assertEquals(ProductScreen.FACE_TO_FACE_WORKBENCH, ProductNavigationPolicy.exitTarget(ProductScreen.INTERPRETATION_WORKBENCH))
        assertEquals(ProductScreen.FACE_TO_FACE_WORKBENCH, ProductNavigationPolicy.exitTarget(ProductScreen.FACE_TO_FACE_WORKBENCH))
        assertEquals(ProductScreen.PROFILE, ProductNavigationPolicy.exitTarget(ProductScreen.ENDPOINT_SETTINGS))
    }

    @Test
    fun immersiveWorkbenchExitsHomeWhileSettingsReturnsToProfile() {
        assertEquals(ProductScreen.FACE_TO_FACE_WORKBENCH, ProductNavigationPolicy.exitTarget(ProductScreen.INTERPRETATION_WORKBENCH))
        assertEquals(ProductScreen.FACE_TO_FACE_WORKBENCH, ProductNavigationPolicy.exitTarget(ProductScreen.FACE_TO_FACE_WORKBENCH))
        assertEquals(ProductScreen.PROFILE, ProductNavigationPolicy.exitTarget(ProductScreen.ENDPOINT_SETTINGS))
    }

    @Test
    fun endpointSettingsKeepsProfileAsSelectedPrimaryDestination() {
        assertEquals(ProductDestination.PROFILE, ProductNavigationPolicy.selectedDestination(ProductScreen.ENDPOINT_SETTINGS))
        assertEquals(ProductDestination.ADMIN_TEST, ProductNavigationPolicy.selectedDestination(ProductScreen.ADMIN_TEST))
    }
}
