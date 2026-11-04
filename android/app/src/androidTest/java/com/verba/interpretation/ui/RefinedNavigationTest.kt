package com.verba.interpretation.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
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
