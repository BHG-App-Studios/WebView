package com.BHG.webapp

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * Persists the user's theme choice and maps it to an AppCompat night mode.
 *
 * The choice is applied through [AppCompatDelegate.setDefaultNightMode], which
 * is the single source of truth for light/dark across the whole app — combined
 * with android:forceDarkAllowed=false in the theme so Android 10's automatic
 * "force dark" can't override a chosen Light theme.
 */
object ThemePrefs {

    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"

    private const val PREFS = "app_prefs"
    private const val KEY_THEME = "theme_mode"

    fun getMode(context: Context): String =
        prefs(context).getString(KEY_THEME, SYSTEM) ?: SYSTEM

    fun setMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_THEME, mode).apply()
        apply(mode)
    }

    /** Applies the given (or stored) choice to AppCompat. */
    fun apply(mode: String) {
        AppCompatDelegate.setDefaultNightMode(toNightMode(mode))
    }

    fun applyStored(context: Context) = apply(getMode(context))

    fun toNightMode(mode: String): Int = when (mode) {
        LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
