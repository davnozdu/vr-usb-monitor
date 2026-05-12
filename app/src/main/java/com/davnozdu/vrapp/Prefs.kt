package com.davnozdu.vrapp

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val KEY_ENABLED       = "enabled"
    const val KEY_SCREEN_OFF    = "screen_off"
    const val KEY_BLOCK_TOUCH   = "block_touch"
    const val KEY_DELAY_SECONDS = "delay_seconds"

    const val DEFAULT_DELAY = 120  // 2 minutes

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences("vrapp", Context.MODE_PRIVATE)
}
