package com.BHG.webapp

import android.app.Application

/**
 * Applies the saved theme (light / dark / system) before any activity is
 * created, so the choice survives process restart and takes effect app-wide
 * on the very first frame — the reliable place to set the night mode.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemePrefs.applyStored(this)
    }
}
