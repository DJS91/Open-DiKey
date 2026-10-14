package com.sphy.airconcontroller.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.sphy.airconcontroller.R
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.storage.ThemeMode
import com.sphy.airconcontroller.storage.ThemeStyle

/**
 * Applies the saved theme choices. [ThemeMode] (dark/light/system) goes through AppCompat; [ThemeStyle]
 * (classic/modern) selects which theme each screen is created with.
 */
object ThemeManager {
    fun themeFor(style: ThemeStyle): Int = when (style) {
        ThemeStyle.CLASSIC -> R.style.Theme_OpenDiKey
        ThemeStyle.MODERN -> R.style.Theme_OpenDiKey_Modern
    }

    /** Call once at process start, before any activity is created. */
    fun applySaved(context: Context) = apply(AppSettings(context).themeMode)

    /** Switching mode recreates any open activities so they pick up the new palette. */
    fun apply(mode: ThemeMode) {
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }
}
