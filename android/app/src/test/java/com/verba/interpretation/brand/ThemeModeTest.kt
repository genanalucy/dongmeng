package com.verba.interpretation.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the ThemeMode contract: persisted-value parsing (default / invalid
 * fallback), the dark-theme resolution matrix, and the account-settings UI
 * presentation policy exposed through [ThemeModePresentation].
 */
class ThemeModeTest {

    @Test
    fun missingStoredValueFallsBackToSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStored(""))
    }

    @Test
    fun unknownStoredValuesFallBackToSystem() {
        listOf("DARK", "dark ", " midnight", "midnight", "system\n", "0", "true").forEach { value ->
            assertEquals("fromStored(\"$value\") must fall back to SYSTEM", ThemeMode.SYSTEM, ThemeMode.fromStored(value))
        }
    }

    @Test
    fun storedValuesRoundTrip() {
        ThemeMode.entries.forEach { mode ->
            assertEquals(mode, ThemeMode.fromStored(mode.storedValue))
        }
    }

    @Test
    fun storedValuesAreUnique() {
        val storedValues = ThemeMode.entries.map { it.storedValue }
        assertEquals("one stored value per mode", ThemeMode.entries.size, storedValues.toSet().size)
    }

    @Test
    fun resolveDarkThemeFollowsSystemOnlyForSystemMode() {
        assertEquals(true, resolveDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.SYSTEM, systemInDarkTheme = false))
        // 固定模式忽略系统深色设置。
        assertEquals(false, resolveDarkTheme(ThemeMode.LIGHT, systemInDarkTheme = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.LIGHT, systemInDarkTheme = false))
        assertEquals(true, resolveDarkTheme(ThemeMode.DARK, systemInDarkTheme = true))
        assertEquals(true, resolveDarkTheme(ThemeMode.DARK, systemInDarkTheme = false))
    }

    @Test
    fun presentationOffersThreeOptionsInDefaultFirstOrder() {
        assertEquals(listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK), ThemeModePresentation.options)
    }

    @Test
    fun presentationLabelsMatchTheConfirmedThreeWayChoice() {
        assertEquals("跟随系统", ThemeModePresentation.label(ThemeMode.SYSTEM))
        assertEquals("浅色", ThemeModePresentation.label(ThemeMode.LIGHT))
        assertEquals("深色", ThemeModePresentation.label(ThemeMode.DARK))
    }

    @Test
    fun presentationAccessibilityStringsAreDistinctPerOption() {
        val announcements = ThemeModePresentation.options.map { ThemeModePresentation.optionAnnouncement(it) }
        assertEquals(ThemeModePresentation.options.size, announcements.toSet().size)
        announcements.forEach { announcement ->
            assertTrue("announcement must be non-blank", announcement.isNotBlank())
        }
        val tags = ThemeModePresentation.options.map { ThemeModePresentation.optionTestTag(it) }
        assertEquals(ThemeModePresentation.options.size, tags.toSet().size)
        assertEquals("已选择", ThemeModePresentation.optionStateDescription(selected = true))
        assertEquals("未选择", ThemeModePresentation.optionStateDescription(selected = false))
        assertFalse(ThemeModePresentation.optionStateDescription(true) == ThemeModePresentation.optionStateDescription(false))
    }
}
