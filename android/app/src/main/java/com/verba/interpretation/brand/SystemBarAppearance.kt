package com.verba.interpretation.brand

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * System-bar contract for the resolved app theme. It mirrors the
 * Theme.Interpretation XML matrix: dark themes keep the translation-shell
 * background (#07111F) under both bars with light icons; light themes pair the
 * brand light background (#F5F7FA) with dark icons. The platform defines
 * `android:windowLightNavigationBar` only from API 27 and the API 26 floor of
 * the XML theme already falls back to the dark shell navigation bar there, so
 * the programmatic path applies the identical fallback below
 * [Build.VERSION_CODES.O_MR1] — see ThemeInterpretationSystemBarsTest for the
 * resource-side contract.
 */
data class SystemBarAppearance(
    val statusBarColor: Color,
    val navigationBarColor: Color,
    val lightStatusBarIcons: Boolean,
    val lightNavigationBarIcons: Boolean,
)

/** Resolves the window system-bar values for a theme on a given API level. */
fun systemBarAppearance(darkTheme: Boolean, sdkInt: Int): SystemBarAppearance {
    val darkBar = BrandDarkColors.background
    val lightBar = BrandLightColors.background
    return when {
        darkTheme -> SystemBarAppearance(
            statusBarColor = darkBar,
            navigationBarColor = darkBar,
            lightStatusBarIcons = false,
            lightNavigationBarIcons = false,
        )
        sdkInt >= Build.VERSION_CODES.O_MR1 -> SystemBarAppearance(
            statusBarColor = lightBar,
            navigationBarColor = lightBar,
            lightStatusBarIcons = true,
            lightNavigationBarIcons = true,
        )
        else -> SystemBarAppearance(
            statusBarColor = lightBar,
            navigationBarColor = darkBar,
            lightStatusBarIcons = true,
            lightNavigationBarIcons = false,
        )
    }
}

/**
 * Applies [systemBarAppearance] for [darkTheme] to the host activity window so
 * a manual in-app switch restyles the status/navigation bars immediately,
 * without waiting for the next resource-night resolution. While the mode is
 * SYSTEM this only re-applies the values the XML theme already resolved
 * (idempotent).
 *
 * The window is looked up from [LocalView] and captured only inside the
 * [DisposableEffect] closures, never stored in composition state or in any
 * singleton, so the activity can never be leaked by this effect. On dispose
 * the window is restored to the resource-night (XML) appearance, guaranteeing
 * a disposed effect never leaves a stale override behind.
 */
@Composable
fun BrandSystemBars(darkTheme: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, darkTheme) {
        val window = view.context.activityWindow()
            ?: return@DisposableEffect onDispose { /* previews have no window */ }
        applySystemBarAppearance(window, view, systemBarAppearance(darkTheme, Build.VERSION.SDK_INT))
        onDispose {
            applySystemBarAppearance(window, view, systemBarAppearance(isResourceNight(view), Build.VERSION.SDK_INT))
        }
    }
}

@Suppress("DEPRECATION") // window.statusBarColor/navigationBarColor pair with the XML theme items.
private fun applySystemBarAppearance(window: Window, view: View, appearance: SystemBarAppearance) {
    window.statusBarColor = appearance.statusBarColor.toArgb()
    window.navigationBarColor = appearance.navigationBarColor.toArgb()
    val controller = WindowCompat.getInsetsController(window, view)
    controller.isAppearanceLightStatusBars = appearance.lightStatusBarIcons
    controller.isAppearanceLightNavigationBars = appearance.lightNavigationBarIcons
}

/** Night mode as the resource qualifier folders resolved it (the XML default). */
private fun isResourceNight(view: View): Boolean {
    val nightMode = view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return nightMode == Configuration.UI_MODE_NIGHT_YES
}

/** Unwraps ContextThemeWrapper chains; null in previews and non-activity hosts. */
private tailrec fun Context.activityWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.activityWindow()
    else -> null
}
