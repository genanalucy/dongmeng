package com.verba.interpretation.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductNavigationPolicyTest {
    @Test
    fun userPrimaryDestinationsDoNotExposeCameraOrHistory() {
        val destinations = ProductNavigationPolicy.destinationsFor(ProductNavigationMode.USER)

        assertFalse(ProductDestination.TRANSLATE in destinations)
        assertFalse(ProductDestination.HISTORY in destinations)
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
