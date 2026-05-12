package com.davnozdu.vrapp

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val KEY_ENABLED             = "enabled"
    const val KEY_SCREEN_OFF          = "screen_off"
    const val KEY_BLOCK_TOUCH         = "block_touch"
    const val KEY_DELAY_SECONDS       = "delay_seconds"
    const val KEY_RESTORE_TIMEOUT     = "restore_timeout_sec"

    const val DEFAULT_DELAY           = 15
    const val DEFAULT_RESTORE_TIMEOUT = 15

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences("vrapp", Context.MODE_PRIVATE)
}
