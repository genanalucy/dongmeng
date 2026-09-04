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
            assertTrue(ProductNavigationPolicy.shellFor(ProductNavigationMode.USER, screen).showBottomBar)
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
    fun productShellProvidesExactUserAndAdministratorBottomBars() {
        val userShell = ProductNavigationPolicy.shellFor(
            ProductNavigationMode.USER,
            ProductScreen.FACE_TO_FACE_WORKBENCH,
        )
        assertTrue(userShell.showBottomBar)
        assertEquals(
            listOf(
                ProductDestination.FACE_TO_FACE,
                ProductDestination.INTERPRETATION,
                ProductDestination.PROFILE,
            ),
            userShell.destinations,
        )

        val administratorShell = ProductNavigationPolicy.shellFor(
            ProductNavigationMode.ADMIN_TEST,
            ProductScreen.ADMIN_TEST,
        )
        assertTrue(administratorShell.showBottomBar)
        assertEquals(
            listOf(ProductDestination.ADMIN_TEST, ProductDestination.PROFILE),
            administratorShell.destinations,
        )
    }

    @Test
    fun productShellHidesBottomBarAndDestinationsForAuthenticationAndSecondaryRoutes() {
        listOf(
            ProductNavigationPolicy.shellFor(ProductNavigationMode.AUTHENTICATION, ProductScreen.ACCOUNT),
            ProductNavigationPolicy.shellFor(ProductNavigationMode.USER, ProductScreen.ACCOUNT),
            ProductNavigationPolicy.shellFor(ProductNavigationMode.USER, ProductScreen.ENDPOINT_SETTINGS),
            ProductNavigationPolicy.shellFor(ProductNavigationMode.ADMIN_TEST, ProductScreen.ENDPOINT_SETTINGS),
        ).forEach { shell ->
            assertFalse(shell.showBottomBar)
            assertTrue(shell.destinations.isEmpty())
        }
    }

    @Test
    fun navigationStackStartsWithTheModeRootAndCannotPopIt() {
        val stack = ProductNavigationStack.initial(ProductNavigationMode.USER)

        assertEquals(ProductScreen.FACE_TO_FACE_WORKBENCH, stack.current)
        assertFalse(stack.canPop)
        assertTrue(stack === stack.pop())
    }

    @Test
    fun accountHistoryBackReturnsToAccount() {
        val stack = ProductNavigationStack.initial(ProductNavigationMode.AUTHENTICATION)
            .push(ProductScreen.HISTORY)

        assertEquals(ProductScreen.ACCOUNT, stack.pop().current)
    }

    @Test
    fun accountEndpointSettingsBackReturnsToAccount() {
        val stack = ProductNavigationStack.initial(ProductNavigationMode.AUTHENTICATION)
            .push(ProductScreen.ENDPOINT_SETTINGS)

        assertEquals(ProductScreen.ACCOUNT, stack.pop().current)
    }

    @Test
    fun profileEndpointSettingsBackReturnsToProfile() {
        val stack = ProductNavigationStack.initial(ProductNavigationMode.USER)
            .selectPrimary(ProductDestination.PROFILE)
            .push(ProductScreen.ENDPOINT_SETTINGS)

        assertEquals(ProductScreen.PROFILE, stack.pop().current)
    }

    @Test
    fun selectingPrimaryDestinationResetsSecondaryHistoryToThatRoot() {
        val stack = ProductNavigationStack.initial(ProductNavigationMode.AUTHENTICATION)
            .push(ProductScreen.ENDPOINT_SETTINGS)
            .selectPrimary(ProductDestination.FACE_TO_FACE)

        assertEquals(ProductScreen.FACE_TO_FACE_WORKBENCH, stack.current)
        assertFalse(stack.canPop)
    }

    @Test
    fun interpretationExitSelectsFaceToFaceRoot() {
        val stack = ProductNavigationStack.initial(ProductNavigationMode.USER)
            .selectPrimary(ProductDestination.INTERPRETATION)
            .selectPrimary(ProductDestination.FACE_TO_FACE)

        assertEquals(ProductScreen.FACE_TO_FACE_WORKBENCH, stack.current)
        assertFalse(stack.canPop)
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

    @Test
    fun endpointSettingsUiIsHiddenInReleaseBuilds() {
        assertFalse(EndpointSettingsAccessPolicy.endpointEditingEnabled(debugBuild = false))
        assertFalse(EndpointSettingsAccessPolicy.adminTestSettingsVisible(ProductNavigationMode.ADMIN_TEST, debugBuild = false))
        assertFalse(EndpointSettingsAccessPolicy.adminTestSettingsVisible(ProductNavigationMode.USER, debugBuild = false))
    }

    @Test
    fun endpointSettingsUiRemainsAvailableInDebugBuilds() {
        assertTrue(EndpointSettingsAccessPolicy.endpointEditingEnabled(debugBuild = true))
        assertTrue(EndpointSettingsAccessPolicy.adminTestSettingsVisible(ProductNavigationMode.ADMIN_TEST, debugBuild = true))
        assertFalse(EndpointSettingsAccessPolicy.adminTestSettingsVisible(ProductNavigationMode.USER, debugBuild = true))
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
