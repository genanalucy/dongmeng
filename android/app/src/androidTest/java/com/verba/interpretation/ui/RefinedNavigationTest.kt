package com.verba.interpretation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import kotlin.math.abs
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.verba.interpretation.brand.BrandTheme
import com.verba.interpretation.ui.navigation.ProductBottomBar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RefinedNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pressedNavigationFeedbackStaysInsideCapsuleInBothThemes() {
        var dark by androidx.compose.runtime.mutableStateOf(false)
        var selected: ProductDestination? = null
        compose.setContent {
            BrandTheme(darkTheme = dark) {
                Box(Modifier.width(320.dp)) {
                    ProductBottomBar(
                        destinations = listOf(ProductDestination.FACE_TO_FACE, ProductDestination.INTERPRETATION, ProductDestination.PROFILE),
                        selected = ProductDestination.FACE_TO_FACE,
                        onSelect = { selected = it },
                    )
                }
            }
        }
        listOf(false, true).forEach { darkTheme ->
            compose.runOnIdle { dark = darkTheme }
            compose.waitForIdle()
            val tab = compose.onNodeWithContentDescription("同传页")
            val before = tab.captureToImage().toPixelMap()
            tab.performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(300)
            val pressed = tab.captureToImage().toPixelMap()
            fun distance(a: Color, b: Color): Float = abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)
            // A point well outside the 25dp corner but inside the rectangular hit bounds.
            val x = (before.width * 0.02f).toInt()
            val y = (before.height * 0.03f).toInt()
            val cornerChange = distance(before[x, y], pressed[x, y])
            // Inside the capsule, away from its centered icon and label.
            val innerX = (before.width * 0.12f).toInt()
            val innerY = before.height / 2
            val innerChange = distance(before[innerX, innerY], pressed[innerX, innerY])
            tab.performTouchInput { up() }
            compose.runOnIdle {
                assertTrue("Ripple painted rectangular corner (dark=$darkTheme): $cornerChange", cornerChange < 0.025f)
                assertTrue("Press feedback disappeared (dark=$darkTheme)", innerChange > 0.005f)
                assertEquals(ProductDestination.INTERPRETATION, selected)
            }
        }
    }

    @Test fun narrowNavigationSupportsLargeTextAndKeepsAllDestinationsReachable() {
        var selected: ProductDestination? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                BrandTheme(darkTheme = false) {
                    Box(Modifier.width(320.dp)) {
                        ProductBottomBar(
                            destinations = listOf(ProductDestination.FACE_TO_FACE, ProductDestination.INTERPRETATION, ProductDestination.PROFILE),
                            selected = ProductDestination.FACE_TO_FACE,
                            onSelect = { selected = it },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("对话").assertIsDisplayed()
        compose.onNodeWithText("我的").assertIsDisplayed()
        compose.onNodeWithText("同传").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(ProductDestination.INTERPRETATION, selected) }
    }
}
