package com.davnozdu.vrapp

import android.app.*
import android.content.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class UsbMonitorService : Service() {

    companion object {
        const val ACTION_USB_CONNECTED      = "com.davnozdu.vrapp.USB_CONNECTED"
        const val ACTION_USB_DISCONNECTED   = "com.davnozdu.vrapp.USB_DISCONNECTED"
        const val ACTION_COUNTDOWN          = "com.davnozdu.vrapp.COUNTDOWN"
        const val ACTION_LOG                = "com.davnozdu.vrapp.LOG"
        const val ACTION_EMERGENCY_RESTORED = "com.davnozdu.vrapp.EMERGENCY_RESTORED"
        const val EXTRA_USB_ACTION          = "usb_action"
        const val EXTRA_SECONDS_LEFT        = "seconds_left"
        const val EXTRA_SERVICE_ACTION      = "service_action"
        const val SERVICE_ACTION_EMERGENCY  = "emergency_reset"

        private const val CHANNEL_ID        = "vrapp_channel"
        private const val NOTIFICATION_ID   = 1
        private const val DOUBLE_PRESS_MIN  = 80L
        private const val DOUBLE_PRESS_MAX  = 600L

        // "Never sleep" value used by Macrodroid
        private const val TIMEOUT_NEVER     = "2147483647"
    }

    // Discovered hardware paths
    private var touchInhibitPath: String    = ""
    private var backlightPath: String       = ""
    private var powerButtonDevice: String   = ""

    // State
    @Volatile private var isConnected = false
    @Volatile private var isBlocked   = false

    // Saved values to restore on disconnect
    private var savedBacklight  = -1
    private var savedTimeout    = -1

    private val handler = Handler(Looper.getMainLooper())
    private var pendingBlock: Runnable? = null
    private var countdownTimer: CountDownTimer? = null
    private var powerMonitorProcess: java.lang.Process? = null

    // Re-darkens screen if it comes on while VR is active (mirrors Macrodroid "VR On 2")
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isBlocked) return
            handler.postDelayed({
                Thread { reapplyBacklight() }.start()
            }, 10_000L)
            log("Экран включился — повторное затемнение через 10с")
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Мониторинг активен", false))
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        Thread { discoverHardware() }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(EXTRA_USB_ACTION)) {
            "android.hardware.usb.action.USB_DEVICE_ATTACHED" -> onUsbAttached()
            "android.hardware.usb.action.USB_DEVICE_DETACHED" -> onUsbDetached()
        }
        if (intent?.getStringExtra(EXTRA_SERVICE_ACTION) == SERVICE_ACTION_EMERGENCY) {
            Thread { emergencyRestore() }.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(screenOnReceiver)
        cancelPending()
        stopPowerMonitor()
        if (isConnected || isBlocked) onUsbDetached()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Hardware discovery ───────────────────────────────────────────────────

    private fun discoverHardware() {
        touchInhibitPath  = RootUtils.findTouchInhibit()
        backlightPath     = RootUtils.findBacklightPath()
        powerButtonDevice = RootUtils.findPowerButton()

        log("Сервис запущен")
        log("Тачскрин: ${touchInhibitPath.ifEmpty { "не найден (нет root?)" }}")
        log("Подсветка: ${backlightPath.ifEmpty { "не найден (нет root?)" }}")
        log("Кнопка питания: ${powerButtonDevice.ifEmpty { "не найдена" }}")
        if (touchInhibitPath.isEmpty() || backlightPath.isEmpty()) {
            log("⚠ Выдайте root приложению в KernelSU Manager → SuperUser")
        }
    }

    // ── USB events ───────────────────────────────────────────────────────────

    private fun onUsbAttached() {
        if (isConnected) return
        isConnected = true
        broadcast(ACTION_USB_CONNECTED)

        val delaySec = Prefs.get(this).getInt(Prefs.KEY_DELAY_SECONDS, Prefs.DEFAULT_DELAY)
        if (delaySec == 0) {
            log("USB подключено — блокирую немедленно")
            Thread { applyBlocking() }.start()
            return
        }
        log("USB подключено — блокировка через ${formatTime(delaySec)}")
        startCountdown(delaySec)
        pendingBlock = Runnable { Thread { applyBlocking() }.start() }
        handler.postDelayed(pendingBlock!!, delaySec * 1000L)
    }

    private fun onUsbDetached() {
        cancelPending()
        stopPowerMonitor()
        isConnected = false
        log("USB отключено")
        if (isBlocked) {
            restoreAll()
            isBlocked = false
        }
        updateNotification("Мониторинг активен", false)
        broadcast(ACTION_USB_DISCONNECTED)
    }

    // ── Blocking ─────────────────────────────────────────────────────────────

    private fun applyBlocking() {
        val prefs      = Prefs.get(this)
        val screenOff  = prefs.getBoolean(Prefs.KEY_SCREEN_OFF,  true)
        val blockTouch = prefs.getBoolean(Prefs.KEY_BLOCK_TOUCH, true)

        // Prevent system auto-sleep (mirrors: settings put system screen_off_timeout 2147483647)
        savedTimeout = RootUtils.executeForOutput(
            "settings get system screen_off_timeout"
        ).toIntOrNull() ?: 30000
        RootUtils.execute("settings put system screen_off_timeout $TIMEOUT_NEVER")
        log("Таймаут экрана → ∞")

        if (screenOff) {
            if (backlightPath.isNotEmpty()) {
                savedBacklight = RootUtils.readBacklightValue(backlightPath)
                // Direct sysfs write keeps display signal alive for VR headset
                RootUtils.execute("echo 0 > $backlightPath")
                log("Подсветка выключена ($backlightPath)")
            } else {
                // Fallback via settings (less reliable, may not keep signal)
                savedBacklight = RootUtils.executeForOutput(
                    "settings get system screen_brightness"
                ).toIntOrNull() ?: 128
                RootUtils.execute("settings put system screen_brightness_mode 0")
                RootUtils.execute("settings put system screen_brightness 0")
                log("Подсветка выключена (fallback via settings)")
            }
        }

        if (blockTouch) {
            if (touchInhibitPath.isNotEmpty()) {
                RootUtils.execute("echo 1 > $touchInhibitPath")
                log("Тач заблокирован ($touchInhibitPath)")
            } else {
                log("Тач: путь не найден — нет root или не найден /sys/class/input")
            }
        }

        isBlocked = true
        updateNotification("VR — экран отключён", true)
        if (powerButtonDevice.isNotEmpty()) {
            log("Аварийный сброс: двойное нажатие кнопки питания")
            startPowerButtonMonitor()
        } else {
            log("Аварийный сброс: кнопка в уведомлении")
        }
    }

    private fun reapplyBacklight() {
        if (!isBlocked) return
        if (backlightPath.isNotEmpty()) {
            RootUtils.execute("echo 0 > $backlightPath")
        } else {
            RootUtils.execute("settings put system screen_brightness 0")
        }
    }

    private fun restoreAll() {
        val prefs      = Prefs.get(this)
        val screenOff  = prefs.getBoolean(Prefs.KEY_SCREEN_OFF,  true)
        val blockTouch = prefs.getBoolean(Prefs.KEY_BLOCK_TOUCH, true)

        // Restore screen timeout
        if (savedTimeout > 0) {
            RootUtils.execute("settings put system screen_off_timeout $savedTimeout")
            savedTimeout = -1
            log("Таймаут экрана восстановлен")
        }

        if (screenOff && savedBacklight >= 0) {
            if (backlightPath.isNotEmpty()) {
                RootUtils.execute("echo $savedBacklight > $backlightPath")
            } else {
                RootUtils.execute("settings put system screen_brightness $savedBacklight")
                RootUtils.execute("settings put system screen_brightness_mode 1")
            }
            savedBacklight = -1
            log("Подсветка восстановлена")
        }

        if (blockTouch && touchInhibitPath.isNotEmpty()) {
            RootUtils.execute("echo 0 > $touchInhibitPath")
            log("Тач восстановлен")
        }
    }

    // ── Emergency restore ─────────────────────────────────────────────────────

    private fun emergencyRestore() {
        if (!isBlocked) return
        log("Аварийный сброс")
        restoreAll()
        isBlocked = false
        stopPowerMonitor()
        updateNotification("Разблокировано вручную (USB подключён)", false)
        broadcast(ACTION_EMERGENCY_RESTORED)
    }

    // ── Power button monitor ──────────────────────────────────────────────────

    private fun startPowerButtonMonitor() {
        stopPowerMonitor()
        Thread {
            try {
                val process = Runtime.getRuntime()
                    .exec(arrayOf(RootUtils.executeForOutput("which su").ifEmpty { "su" }, "-c",
                        "getevent -l $powerButtonDevice"))
                powerMonitorProcess = process
                val reader = process.inputStream.bufferedReader()
                var lastPressMs = 0L
                var line: String?
                while (reader.readLine().also { line = it } != null && isBlocked) {
                    val l = line ?: continue
                    if (l.contains("KEY_POWER") && l.contains("DOWN")) {
                        val now = System.currentTimeMillis()
                        if (now - lastPressMs in DOUBLE_PRESS_MIN..DOUBLE_PRESS_MAX) {
                            emergencyRestore(); break
                        }
                        lastPressMs = now
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; start() }
    }

    private fun stopPowerMonitor() {
        powerMonitorProcess?.destroy()
        powerMonitorProcess = null
    }

    // ── Countdown ────────────────────────────────────────────────────────────

    private fun startCountdown(totalSeconds: Int) {
        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(totalSeconds * 1000L, 1000L) {
            override fun onTick(millis: Long) =
                broadcastCountdown(((millis + 999) / 1000).toInt())
            override fun onFinish() = broadcastCountdown(0)
        }.start()
    }

    private fun cancelPending() {
        countdownTimer?.cancel(); countdownTimer = null
        pendingBlock?.let { handler.removeCallbacks(it) }; pendingBlock = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun formatTime(s: Int) = "%d:%02d".format(s / 60, s % 60)

    private fun log(msg: String) = handler.post {
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent(ACTION_LOG).putExtra("message", msg))
    }

    private fun broadcast(action: String) = handler.post {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(action))
    }

    private fun broadcastCountdown(left: Int) = handler.post {
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent(ACTION_COUNTDOWN).putExtra(EXTRA_SECONDS_LEFT, left))
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "VR Monitor", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String, showReset: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VR Monitor").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage).setContentIntent(open)
        if (showReset) {
            val reset = PendingIntent.getService(
                this, 1,
                Intent(this, UsbMonitorService::class.java)
                    .putExtra(EXTRA_SERVICE_ACTION, SERVICE_ACTION_EMERGENCY),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Аварийный сброс", reset)
        }
        return builder.build()
    }

    private fun updateNotification(text: String, showReset: Boolean) = handler.post {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text, showReset))
    }
}
