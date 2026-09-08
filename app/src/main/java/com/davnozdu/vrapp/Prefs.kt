package com.davnozdu.vrapp

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    // Пользовательские настройки
    const val KEY_ENABLED             = "enabled"
    const val KEY_SCREEN_OFF          = "screen_off"
    const val KEY_BLOCK_TOUCH         = "block_touch"
    const val KEY_DELAY_SECONDS       = "delay_seconds"
    const val KEY_RESTORE_TIMEOUT     = "restore_timeout_sec"
    const val KEY_HIDE_INPUT_TIP      = "hide_input_tip"
    const val KEY_BATTERY_OPT_ASKED   = "battery_opt_asked"

    // Снимок того, что реально применено к железу. Нужен, чтобы откатить
    // именно применённое (а не текущие настройки), и чтобы пережить смерть
    // процесса: сервис, поднятый заново, должен знать, что экран погашен им.
    const val KEY_APPLIED             = "applied"
    const val KEY_APPLIED_TOUCH_PATH  = "applied_touch_path"
    const val KEY_APPLIED_BL_PATH     = "applied_backlight_path"
    const val KEY_APPLIED_BL_VALUE    = "applied_backlight_value"
    const val KEY_APPLIED_SCREEN_OFF  = "applied_screen_off"
    const val KEY_APPLIED_BLOCK_TOUCH = "applied_block_touch"

    // Раньше задержка была нужна, чтобы успеть нажать кнопку каста руками.
    // Модуль VR Display Mode включает дисплей сам, ждать больше нечего.
    const val DEFAULT_DELAY           = 0
    const val DEFAULT_RESTORE_TIMEOUT = 15

    const val NAME = "vrapp"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
