package com.verba.interpretation.brand

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Persists the in-app [ThemeMode] in a private [SharedPreferences] file, in the
 * same style as the other local settings stores. Reads go through
 * [ThemeMode.fromStored], so a missing key or an unrecognized stored value both
 * resolve to the follow-system default.
 */
class ThemeModeStore internal constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    fun load(): ThemeMode = ThemeMode.fromStored(preferences.getString(MODE_KEY, null))

    fun save(mode: ThemeMode) {
        preferences.edit().putString(MODE_KEY, mode.storedValue).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "theme_mode"
        const val MODE_KEY = "mode"
    }
}

/**
 * Compose-observable holder for the selected [ThemeMode]. [select] updates the
 * observable [mode] first (so the root [BrandTheme] and system bars restyle in
 * the same frame) and then commits the choice through [onCommit] for
 * persistence. Re-selecting the current mode is a no-op.
 */
@Stable
class ThemeModeState(
    initialMode: ThemeMode,
    private val onCommit: (ThemeMode) -> Unit,
) {
    var mode: ThemeMode by mutableStateOf(initialMode)
        private set

    fun select(mode: ThemeMode) {
        if (mode == this.mode) return
        this.mode = mode
        onCommit(mode)
    }
}

/** Loads the persisted mode once per store and keeps the selection observable. */
@Composable
fun rememberThemeModeState(store: ThemeModeStore): ThemeModeState =
    remember(store) { ThemeModeState(initialMode = store.load(), onCommit = store::save) }
