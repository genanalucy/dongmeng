package com.verba.interpretation.brand

/**
 * In-app theme mode preference offered in account settings. The app theme
 * follows the system dark/light setting by default ([SYSTEM]); users can pin
 * the light or dark brand scheme instead. The stored values are the only
 * strings ever written by [ThemeModeStore]; anything else read back resolves
 * to [SYSTEM] via [ThemeMode.fromStored], so corrupted or legacy values can
 * never force an unknown appearance.
 */
enum class ThemeMode(val storedValue: String, val label: String) {
    SYSTEM("system", "跟随系统"),
    LIGHT("light", "浅色"),
    DARK("dark", "深色");

    companion object {
        /** Parses a persisted value; `null`, blank and unknown input fall back to [SYSTEM]. */
        fun fromStored(value: String?): ThemeMode =
            entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

/**
 * Resolves the dark/light brand scheme for [BrandTheme]. Only [ThemeMode.SYSTEM]
 * consults the system dark mode; the pinned modes ignore it.
 */
fun resolveDarkTheme(mode: ThemeMode, systemInDarkTheme: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemInDarkTheme
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Presentation policy for the account-settings theme picker. Every UI string,
 * ordering decision and semantics tag used by the settings rows flows through
 * this object so the presentation stays unit-testable without a device.
 */
object ThemeModePresentation {
    /** Settings-page order: the default (system-following) first, then the pinned modes. */
    val options: List<ThemeMode> = ThemeMode.entries.toList()

    fun label(mode: ThemeMode): String = mode.label

    /** Row announcement merged with the RadioButton semantics for screen readers. */
    fun optionAnnouncement(mode: ThemeMode): String = "主题模式，${mode.label}"

    fun optionStateDescription(selected: Boolean): String = if (selected) "已选择" else "未选择"

    fun optionTestTag(mode: ThemeMode): String = "theme-mode-${mode.storedValue}"
}
