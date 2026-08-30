// ---------------------------------------------------------------------
// Copyright (c) 2026 Qualcomm Technologies, Inc. and/or its subsidiaries.
// SPDX-License-Identifier: BSD-3-Clause
// ---------------------------------------------------------------------
package com.geniex.demo.utils

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/**
 * Night-mode preference for the app.
 *
 * The dark palette itself lives in `res/values-night/colors.xml` and is a
 * true-black AMOLED variant; this only decides *when* it is used.
 *
 * Two things had to line up for the manual override to work:
 *  - the theme must be a DayNight theme (`Theme.GenieXDemo` now inherits
 *    `Theme.MaterialComponents.DayNight.NoActionBar`);
 *  - the activities must extend `AppCompatActivity`, because
 *    [AppCompatDelegate.setDefaultNightMode] is driven from there. A plain
 *    `FragmentActivity` would follow the system but ignore this setting.
 */
object ThemeSettings {
    private const val PREFS_NAME = "theme_settings"
    private const val KEY_MODE = "night_mode"

    /** Follow the system setting. */
    const val MODE_FOLLOW_SYSTEM = 0

    /** Force the light palette. */
    const val MODE_LIGHT = 1

    /** Force the dark (AMOLED black) palette. */
    const val MODE_DARK = 2

    @Volatile
    var mode: Int = MODE_FOLLOW_SYSTEM
        private set

    fun init(context: Context) {
        mode =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_MODE, MODE_FOLLOW_SYSTEM)
        apply(mode)
    }

    fun setMode(
        context: Context,
        value: Int,
    ) {
        val normalized =
            when (value) {
                MODE_LIGHT, MODE_DARK -> value
                else -> MODE_FOLLOW_SYSTEM
            }
        mode = normalized
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MODE, normalized).apply()
        apply(normalized)
    }

    /** Cycles follow-system -> light -> dark, for a single-button toggle. */
    fun nextMode(): Int =
        when (mode) {
            MODE_FOLLOW_SYSTEM -> MODE_LIGHT
            MODE_LIGHT -> MODE_DARK
            else -> MODE_FOLLOW_SYSTEM
        }

    private fun apply(value: Int) {
        AppCompatDelegate.setDefaultNightMode(
            when (value) {
                MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }

    /**
     * True when the resources currently resolved are the `-night` ones.
     *
     * Read from the configuration rather than from [mode], because under
     * follow-system the effective theme is whatever the system decided.
     */
    fun isNight(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
}
