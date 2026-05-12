package com.davnozdu.vrapp

object RootUtils {

    fun checkRoot(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo ok"))
        val ok = p.inputStream.bufferedReader().readLine()?.trim() == "ok"
        p.waitFor()
        ok
    } catch (e: Exception) {
        false
    }

    fun execute(command: String): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        p.waitFor() == 0
    } catch (e: Exception) {
        false
    }

    fun executeForOutput(command: String): String = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out
    } catch (e: Exception) {
        ""
    }

    fun findTouchscreen(): String {
        val script = """
            for dev in /dev/input/event*; do
                if getevent -pl "${'$'}dev" 2>&1 | grep -q "ABS_MT_POSITION_X"; then
                    echo "${'$'}dev"
                    break
                fi
            done
        """.trimIndent()
        return executeForOutput(script)
    }

    // Returns list of gyroscope + accelerometer input devices.
    // On some devices (CHRE/IIO sensor stack) this list may be empty —
    // in that case sensor blocking via /dev/input is not supported.
    fun findMotionSensors(): List<String> {
        val script = """
            for dev in /dev/input/event*; do
                info=$(getevent -pl "${'$'}dev" 2>&1)
                # Gyroscope: has ABS_RX/RY/RZ
                if echo "${'$'}info" | grep -qE "ABS_RX|ABS_RY|ABS_RZ"; then
                    echo "${'$'}dev"
                    continue
                fi
                # Accelerometer: has ABS_X/Y/Z but NOT multi-touch (not the touchscreen)
                if echo "${'$'}info" | grep -q "ABS_X" && ! echo "${'$'}info" | grep -q "ABS_MT_POSITION_X"; then
                    echo "${'$'}dev"
                fi
            done
        """.trimIndent()
        return executeForOutput(script).lines().filter { it.startsWith("/dev/input/") }
    }

    fun chmodDevices(devices: List<String>, mode: String) {
        devices.forEach { dev -> execute("chmod $mode $dev") }
    }
}
