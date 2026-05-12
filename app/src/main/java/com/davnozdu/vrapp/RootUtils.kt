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

    fun findMotionSensors(): List<String> {
        val script = """
            for dev in /dev/input/event*; do
                info=$(getevent -pl "${'$'}dev" 2>&1)
                if echo "${'$'}info" | grep -qE "ABS_RX|ABS_RY|ABS_RZ"; then
                    echo "${'$'}dev"
                    continue
                fi
                if echo "${'$'}info" | grep -q "ABS_X" && ! echo "${'$'}info" | grep -q "ABS_MT_POSITION_X"; then
                    echo "${'$'}dev"
                fi
            done
        """.trimIndent()
        return executeForOutput(script).lines().filter { it.startsWith("/dev/input/") }
    }

    fun findPowerButton(): String {
        val script = """
            for dev in /dev/input/event*; do
                if getevent -pl "${'$'}dev" 2>&1 | grep -q "KEY_POWER"; then
                    echo "${'$'}dev"
                    break
                fi
            done
        """.trimIndent()
        return executeForOutput(script)
    }

    fun chmodDevices(devices: List<String>, mode: String) {
        devices.forEach { dev -> execute("chmod $mode $dev") }
    }
}
