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
    // On OnePlus 15: /sys/class/input/input7/inhibited
    // Write "1" to block, "0" to unblock — no chmod needed.
    fun findTouchInhibit(): String {
        // First pass: match by device name
        val byName = """
            for dir in /sys/class/input/input*; do
                [ -f "${'$'}dir/inhibited" ] || continue
                name=$(cat "${'$'}dir/name" 2>/dev/null | tr '[:upper:]' '[:lower:]')
                echo "${'$'}name" | grep -qE "touch|panel|screen|ts$" || continue
                echo "${'$'}dir/inhibited"
                break
            done
        """.trimIndent()
        val byNameResult = executeForOutput(byName)
        if (byNameResult.startsWith("/sys/")) return byNameResult

        // Fallback: any inhibited input that is not a key/button device
        val fallback = """
            for dir in /sys/class/input/input*; do
                [ -f "${'$'}dir/inhibited" ] || continue
                name=$(cat "${'$'}dir/name" 2>/dev/null | tr '[:upper:]' '[:lower:]')
                echo "${'$'}name" | grep -qE "power|key|button|volume|gpio" && continue
                echo "${'$'}dir/inhibited"
                break
            done
        """.trimIndent()
        return executeForOutput(fallback)
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

    fun findPowerButton(): String {
        val script = """
            for dev in /dev/input/event*; do
                getevent -pl "${'$'}dev" 2>&1 | grep -q "KEY_POWER" || continue
                echo "${'$'}dev"
                break
            done
        """.trimIndent()
        return executeForOutput(script)
    }
}
