package com.verba.interpretation.brand

import androidx.compose.ui.graphics.toArgb
import com.verba.interpretation.ui.design.VerbaColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the Theme.Interpretation system-bar contract across all four resource
 * qualifier folders. Android resolves exactly one `<style>` per (API, night)
 * cell: styles never merge items across qualifier folders, and night mode has
 * higher qualifier precedence than the platform version, so values-night-v27
 * must exist alongside values-night, values-v27 and values -- otherwise night
 * API 27+ devices would fall through to values-night only if that folder were
 * complete, or to a light-theme folder if it were missing. Below API 27 the
 * platform defines no windowLightNavigationBar (SDK api-versions.xml:
 * since="27"), and API 26 always draws white nav icons, so the light theme's
 * API-26-floor folder (values) must fall back to the dark shell navigation
 * bar with the flag false; values-v27 restores the light bar with dark nav
 * icons. These are plain JVM tests that parse the theme XML directly; no
 * Android framework, device, or Gradle resource merging is involved.
 */
class ThemeInterpretationSystemBarsTest {

    private data class StyleFile(
        val folder: String,
        val isNight: Boolean,
        val isApi27Floor: Boolean,
    )

    private val styleFiles = listOf(
        StyleFile("values", isNight = false, isApi27Floor = false),
        StyleFile("values-v27", isNight = false, isApi27Floor = true),
        StyleFile("values-night", isNight = true, isApi27Floor = false),
        StyleFile("values-night-v27", isNight = true, isApi27Floor = true),
    )

    private val requiredItems = setOf(
        "android:fontFamily",
        "android:windowActionModeOverlay",
        "android:statusBarColor",
        "android:navigationBarColor",
        "android:windowLightStatusBar",
        "android:windowLightNavigationBar",
    )

    private fun resThemesFile(folder: String): File {
        // Gradle unit tests run with the app module as working directory; the
        // android/ fallback covers running from the repository root.
        val candidates = listOf(
            File("src/main/res/$folder/themes.xml"),
            File("app/src/main/res/$folder/themes.xml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("themes.xml not found for $folder (tried ${candidates.map { it.path }})")
    }

    private fun parseStyle(folder: String): Element {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(resThemesFile(folder))
        val styles = document.getElementsByTagName("style")
        assertEquals("exactly one style in $folder/themes.xml", 1L, styles.length.toLong())
        val style = styles.item(0) as Element
        assertEquals("style name in $folder/themes.xml", "Theme.Interpretation", style.getAttribute("name"))
        assertEquals(
            "style parent in $folder/themes.xml",
            "android:style/Theme.Material.NoActionBar",
            style.getAttribute("parent"),
        )
        return style
    }

    private fun items(folder: String): Map<String, String> {
        val style = parseStyle(folder)
        val result = mutableMapOf<String, String>()
        val itemNodes = style.getElementsByTagName("item")
        for (i in 0 until itemNodes.length) {
            val item = itemNodes.item(i) as Element
            result[item.getAttribute("name")] = item.textContent.trim()
        }
        return result
    }

    private fun navigationBarToolsTargetApi(folder: String): String? {
        val itemNodes = parseStyle(folder).getElementsByTagName("item")
        for (i in 0 until itemNodes.length) {
            val item = itemNodes.item(i) as Element
            if (item.getAttribute("name") == "android:windowLightNavigationBar") {
                // getAttributeNS returns "" when the attribute is absent.
                val targetApi = item.getAttributeNS("http://schemas.android.com/tools", "targetApi")
                return targetApi.ifEmpty { null }
            }
        }
        return null
    }

    private fun shellBackgroundHex(): String =
        "#%06X".format(0xFFFFFF and VerbaColors.Background.toArgb())

    private fun brandLightBackgroundHex(): String =
        "#%06X".format(0xFFFFFF and BrandLightColors.background.toArgb())

    private fun expectedBarHex(isNight: Boolean): String =
        if (isNight) {
            shellBackgroundHex()
        } else {
            // Light bars must align with BrandLightColors.background so the
            // system bars continue the Compose light canvas.
            brandLightBackgroundHex()
        }

    // windowLightNavigationBar exists only from API 27 (platform
    // api-versions.xml: since="27"), and API 26 always draws white nav icons,
    // so the light theme's API-26-floor folder must fall back to the dark
    // shell bar with the flag false; only the v27 floor may show the light
    // bar with dark nav icons.
    private fun expectedNavigationBarHex(file: StyleFile): String =
        if (file.isNight || !file.isApi27Floor) shellBackgroundHex() else brandLightBackgroundHex()

    private fun expectedLightNavigationBar(file: StyleFile): Boolean =
        !file.isNight && file.isApi27Floor

    @Test
    fun everyQualifierFolderDefinesTheCompleteItemSet() {
        styleFiles.forEach { file ->
            val items = items(file.folder)
            assertEquals(
                "item set in ${file.folder}/themes.xml must define exactly the required attributes",
                requiredItems,
                items.keys,
            )
        }
    }

    @Test
    fun nonColorAttributesAreIdenticalAcrossFolders() {
        val reference = items(styleFiles.first().folder)
        val nonColorItems = listOf("android:fontFamily", "android:windowActionModeOverlay")
        styleFiles.drop(1).forEach { file ->
            nonColorItems.forEach { name ->
                assertEquals(
                    "$name must stay identical in ${file.folder}/themes.xml",
                    reference.getValue(name),
                    items(file.folder).getValue(name),
                )
            }
        }
        assertEquals("font family", "sans", reference.getValue("android:fontFamily"))
        assertEquals("action mode overlay", "true", reference.getValue("android:windowActionModeOverlay"))
    }

    @Test
    fun systemBarColorsAndIconAppearanceFollowNightMode() {
        styleFiles.forEach { file ->
            val items = items(file.folder)
            val expectedBar = expectedBarHex(file.isNight)
            assertEquals(
                "statusBarColor in ${file.folder}/themes.xml",
                expectedBar,
                items.getValue("android:statusBarColor"),
            )
            assertEquals(
                "navigationBarColor in ${file.folder}/themes.xml",
                expectedNavigationBarHex(file),
                items.getValue("android:navigationBarColor"),
            )
            assertEquals(
                "windowLightStatusBar in ${file.folder}/themes.xml",
                (!file.isNight).toString(),
                items.getValue("android:windowLightStatusBar"),
            )
            assertEquals(
                "windowLightNavigationBar in ${file.folder}/themes.xml",
                expectedLightNavigationBar(file).toString(),
                items.getValue("android:windowLightNavigationBar"),
            )
        }
    }

    @Test
    fun lightBarColorAlignsWithBrandLightBackground() {
        val lightBar = items("values").getValue("android:statusBarColor")
        assertEquals("light status bar must match BrandLightColors.background", brandLightBackgroundHex(), lightBar)
    }

    @Test
    fun lightNavigationBarFallsBackToDarkShellBelowApi27() {
        // API 26 defines no windowLightNavigationBar and always draws white
        // nav icons, so the light theme's API-26-floor folder must not paint
        // the bar with the light brand background.
        assertEquals(
            "API 26-floor light navbar must fall back to the dark shell background",
            shellBackgroundHex(),
            items("values").getValue("android:navigationBarColor"),
        )
        assertEquals(
            "API 27+ light navbar keeps the brand light background",
            brandLightBackgroundHex(),
            items("values-v27").getValue("android:navigationBarColor"),
        )
    }

    @Test
    fun darkBarColorPreservesTranslationShellBackground() {
        val darkBar = items("values-night").getValue("android:statusBarColor")
        assertEquals("dark system bars keep the shell background", shellBackgroundHex(), darkBar)
    }

    @Test
    fun windowLightNavigationToolsTargetApiMatchesFolderFloor() {
        styleFiles.forEach { file ->
            val targetApi = navigationBarToolsTargetApi(file.folder)
            if (file.isApi27Floor) {
                assertNull(
                    "v27 floor folders must not need tools:targetApi on windowLightNavigationBar",
                    targetApi,
                )
            } else {
                assertTrue(
                    "API 26 folders must suppress lint via tools:targetApi=27 on windowLightNavigationBar",
                    targetApi == "27",
                )
            }
        }
    }
}
