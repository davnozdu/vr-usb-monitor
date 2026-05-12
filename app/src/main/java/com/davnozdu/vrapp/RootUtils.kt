package com.davnozdu.vrapp

object RootUtils {

    // KernelSU and Magisk put su in different locations
    private val SU_CANDIDATES = listOf("/system/bin/su", "/sbin/su", "su")

    @Volatile private var suPath: String = ""

    private fun resolveSu(): String {
        if (suPath.isNotEmpty()) return suPath
        for (candidate in SU_CANDIDATES) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf(candidate, "-c", "echo ok"))
                val ok = p.inputStream.bufferedReader().readLine()?.trim() == "ok"
                p.waitFor()
                if (ok) { suPath = candidate; return candidate }
            } catch (_: Exception) {}
        }
        return ""
    }

    fun checkRoot(): Boolean = resolveSu().isNotEmpty()

    fun getSuPath(): String = resolveSu()

    fun execute(command: String): Boolean {
        val su = resolveSu().ifEmpty { return false }
        return try {
            val p = Runtime.getRuntime().exec(arrayOf(su, "-c", command))
            p.waitFor() == 0
        } catch (_: Exception) { false }
    }

    fun executeForOutput(command: String): String {
        val su = resolveSu().ifEmpty { return "" }
        return try {
            val p = Runtime.getRuntime().exec(arrayOf(su, "-c", command))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out
        } catch (_: Exception) { "" }
    }

    // Touchscreen via Linux input subsystem inhibit interface.
    // Uses grep to find the name file containing "touch" pattern,
    // then constructs the inhibited path — avoids multi-line su -c issues.
    fun findTouchInhibit(): String {
        for (pattern in listOf("touchpanel", "touchscreen", "touch_panel", "touch")) {
            val namePath = executeForOutput(
                "grep -rl '$pattern' /sys/class/input/*/name 2>/dev/null | head -1"
            ).trim()
            if (namePath.startsWith("/sys/")) {
                val inhibitedPath = namePath.removeSuffix("name") + "inhibited"
                val exists = executeForOutput("test -f $inhibitedPath && echo ok").trim()
                if (exists == "ok") return inhibitedPath
            }
        }
        return ""
    }

    // Backlight sysfs brightness node.
    // On OnePlus 15: /sys/class/backlight/panel0-backlight/brightness
    fun findBacklightPath(): String =
        executeForOutput("ls /sys/class/backlight/*/brightness 2>/dev/null | head -1")

    fun readBacklightValue(path: String): Int =
        executeForOutput("cat $path").toIntOrNull() ?: -1

    fun readBacklightMax(path: String): Int {
        val maxPath = path.replace("brightness", "max_brightness")
        return executeForOutput("cat $maxPath").toIntOrNull() ?: 255
    }

}
