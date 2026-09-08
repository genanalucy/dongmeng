package com.verba.interpretation.brand

import androidx.compose.ui.graphics.toArgb
import com.verba.interpretation.ui.design.VerbaColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the programmatic system-bar matrix that BrandSystemBars applies after
 * an in-app theme switch, mirroring the Theme.Interpretation XML contract
 * (see ThemeInterpretationSystemBarsTest): dark themes keep the shell
 * background under both bars with light icons; light themes use the brand
 * light background with dark icons from API 27, and below API 27 the light
 * theme falls back to the dark shell navigation bar because the platform
 * defines no windowLightNavigationBar there and the XML floor keeps white nav
 * icons.
 */
class SystemBarAppearanceTest {

    private val shellBackground = VerbaColors.Background.toArgb()
    private val brandLightBackground = BrandLightColors.background.toArgb()
    private val supportedApiLevels = 26..36

    @Test
    fun darkThemeKeepsShellBackgroundUnderBothBarsOnEveryApiLevel() {
        supportedApiLevels.forEach { sdk ->
            val appearance = systemBarAppearance(darkTheme = true, sdkInt = sdk)
            assertEquals("status bar on API $sdk", shellBackground, appearance.statusBarColor.toArgb())
            assertEquals("navigation bar on API $sdk", shellBackground, appearance.navigationBarColor.toArgb())
            assertFalse("dark status icons on API $sdk", appearance.lightStatusBarIcons)
            assertFalse("dark nav icons on API $sdk", appearance.lightNavigationBarIcons)
        }
    }

    @Test
    fun lightThemeUsesLightBarsWithDarkIconsFromApi27() {
        (27..36).forEach { sdk ->
            val appearance = systemBarAppearance(darkTheme = false, sdkInt = sdk)
            assertEquals("status bar on API $sdk", brandLightBackground, appearance.statusBarColor.toArgb())
            assertEquals("navigation bar on API $sdk", brandLightBackground, appearance.navigationBarColor.toArgb())
            assertTrue("dark status icons on API $sdk", appearance.lightStatusBarIcons)
            assertTrue("dark nav icons on API $sdk", appearance.lightNavigationBarIcons)
        }
    }

    @Test
    fun lightThemeBelowApi27FallsBackToDarkShellNavigationBar() {
        val appearance = systemBarAppearance(darkTheme = false, sdkInt = 26)

        assertEquals(brandLightBackground, appearance.statusBarColor.toArgb())
        assertEquals("API 26 light navbar must fall back to the dark shell background", shellBackground, appearance.navigationBarColor.toArgb())
        assertTrue(appearance.lightStatusBarIcons)
        assertFalse("API 26 cannot request light nav-bar icons", appearance.lightNavigationBarIcons)
    }

    @Test
    fun barColorsAlignWithTheXmlThemeValues() {
        // 与 ThemeInterpretationSystemBarsTest 相同的十六进制契约，避免两侧漂移。
        assertEquals("#07111F", "#%06X".format(0xFFFFFF and shellBackground))
        assertEquals("#F5F7FA", "#%06X".format(0xFFFFFF and brandLightBackground))
        assertEquals("#07111F", "#%06X".format(0xFFFFFF and BrandDarkColors.background.toArgb()))
    }
}
