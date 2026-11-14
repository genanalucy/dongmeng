package com.verba.interpretation.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.foundation.background
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GlassRevealTest {
    @get:Rule val compose = createComposeRule()

    @Test fun firstFrameKeepsFinalHeightAndRecycledItemKeepsLayout() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LazyColumn(Modifier.height(140.dp).testTag("list")) {
                items((0..80).toList(), key = { it }) { index ->
                    GlassReveal(revealKey = index.toString()) {
                        Box(Modifier.size(100.dp, 60.dp).background(Color.Red).testTag("item-$index"))
                    }
                }
            }
        }
        compose.onNodeWithTag("item-0").assertHeightIsEqualTo(60.dp)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("list").performScrollToIndex(70)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("list").performScrollToIndex(0)
        compose.onNodeWithTag("item-0").assertHeightIsEqualTo(60.dp)
        // With the clock still paused, a recycled item must already be fully visible.
        val pixels = compose.onNodeWithTag("item-0").captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue("Recycled entry restarted its fade", center.red > 0.9f && center.green < 0.1f)
    }
}
