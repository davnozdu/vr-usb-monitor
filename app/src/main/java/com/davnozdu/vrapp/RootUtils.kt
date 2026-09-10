package com.davnozdu.vrapp

/**
 * Поиск и чтение sysfs-узлов, которыми управляется экран и тачскрин.
 * Вся работа идёт через единственный [RootShell].
 */
object RootUtils {

    fun checkRoot(): Boolean = RootShell.isAvailable()

    /**
     * Тачскрин через inhibit-интерфейс input-подсистемы ядра.
     * Ищем в /sys/class/input/<N>/name устройство с "touch" в имени и проверяем,
     * что рядом лежит узел inhibited (на OnePlus 15 это input7 / "touchpanel").
     */
    fun findTouchInhibit(): String {
        for (pattern in listOf("touchpanel", "touchscreen", "touch_panel", "touch")) {
            val namePath = RootShell.out(
                "grep -rl '$pattern' /sys/class/input/*/name 2>/dev/null | head -1"
            )
            if (!namePath.startsWith("/sys/")) continue
            val inhibited = namePath.removeSuffix("name") + "inhibited"
            if (RootShell.ok("test -f \"$inhibited\"")) return inhibited
        }
        return ""
    }

    /** Узел яркости подсветки. На OnePlus 15: /sys/class/backlight/panel0-backlight/brightness */
    fun findBacklightPath(): String =
        RootShell.out("ls /sys/class/backlight/*/brightness 2>/dev/null | head -1")
            .lineSequence().firstOrNull { it.startsWith("/sys/") } ?: ""

    fun readBacklightValue(path: String): Int =
        RootShell.out("cat \"$path\"").toIntOrNull() ?: -1

    /**
     * Режим очков из общего файла модулей VR Headset Mode и VR Display Mode.
     *
     * В режиме гарнитуры очки нужны только как звук и микрофоны. Внешний
     * дисплей при этом всё равно включается — без успешного DisplayPort-линка
     * очки не поднимают аудиоусилитель, — но гасить из-за него экран телефона
     * нельзя: картинки в очках нет, и телефон гас бы впустую.
     *
     * Файла нет, значение другое или root не выдан — считаем, что режимом
     * никто не управляет, и ведём себя как раньше.
     */
    fun headsetMode(): Boolean =
        RootShell.out("cat /data/adb/vr_mode 2>/dev/null").trim() == "headset"

    /** Текущее значение system-настройки как Int, или [fallback] если прочитать не вышло. */
    fun getSystemInt(key: String, fallback: Int): Int =
        RootShell.out("settings get system $key").toIntOrNull() ?: fallback
}
