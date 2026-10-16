package com.anas.meeqat.ui.theme

import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

object ThemePreferences {
    private const val PREFERENCES = "meeqat_theme"
    private const val KEY_DARK_THEME = "dark_theme"
    private const val DEFAULT_DARK_THEME = false

    fun isDark(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK_THEME, DEFAULT_DARK_THEME)

    fun setDark(context: Context, dark: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DARK_THEME, dark)
            .apply()
    }

    fun darkThemeFlow(context: Context): Flow<Boolean> = callbackFlow {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == KEY_DARK_THEME) {
                trySend(changed.getBoolean(KEY_DARK_THEME, DEFAULT_DARK_THEME))
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(preferences.getBoolean(KEY_DARK_THEME, DEFAULT_DARK_THEME))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
}
