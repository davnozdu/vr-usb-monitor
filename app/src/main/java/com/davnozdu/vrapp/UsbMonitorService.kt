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

        private const val CHANNEL_ID      = "vrapp_channel"
        private const val NOTIFICATION_ID = 1

        // "Never sleep" value used by Macrodroid
        private const val TIMEOUT_NEVER   = "2147483647"
    }

    // Discovered hardware paths (may be empty if root wasn't ready at startup)
    private var touchInhibitPath: String = ""
    private var backlightPath: String    = ""

    // State
    @Volatile private var isConnected = false
    @Volatile private var isBlocked   = false

    // Saved values to restore on disconnect
    private var savedBacklight        = -1
    private var savedSystemBrightness = -1
    private var savedBrightnessMode   = -1

    private val handler = Handler(Looper.getMainLooper())
    private var pendingBlock: Runnable? = null
    private var countdownTimer: CountDownTimer? = null

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
        if (isConnected || isBlocked) onUsbDetached()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Hardware discovery ───────────────────────────────────────────────────

    private fun discoverHardware() {
        touchInhibitPath = RootUtils.findTouchInhibit()
        backlightPath    = RootUtils.findBacklightPath()

        log("Сервис запущен")
        log("Тачскрин: ${touchInhibitPath.ifEmpty { "не найден (нет root?)" }}")
        log("Подсветка: ${backlightPath.ifEmpty { "не найден (нет root?)" }}")
        if (touchInhibitPath.isEmpty() || backlightPath.isEmpty()) {
            log("⚠ Выдайте root приложению в KernelSU Manager → SuperUser")
        }
    }

    // Retry discovery at block time in case root wasn't ready at startup
    private fun ensurePathsDiscovered() {
        if (touchInhibitPath.isEmpty()) {
            touchInhibitPath = RootUtils.findTouchInhibit()
            if (touchInhibitPath.isNotEmpty()) log("Тачскрин найден: $touchInhibitPath")
        }
        if (backlightPath.isEmpty()) {
            backlightPath = RootUtils.findBacklightPath()
            if (backlightPath.isNotEmpty()) log("Подсветка найдена: $backlightPath")
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
        // Retry path discovery in case root wasn't ready when service started
        ensurePathsDiscovered()

        val prefs      = Prefs.get(this)
        val screenOff  = prefs.getBoolean(Prefs.KEY_SCREEN_OFF,  true)
        val blockTouch = prefs.getBoolean(Prefs.KEY_BLOCK_TOUCH, true)

        // Prevent system auto-sleep
        RootUtils.execute("settings put system screen_off_timeout $TIMEOUT_NEVER")
        log("Таймаут экрана → ∞")

        if (screenOff) {
            savedBrightnessMode = RootUtils.executeForOutput(
                "settings get system screen_brightness_mode"
            ).toIntOrNull() ?: 1

            if (backlightPath.isNotEmpty()) {
                savedBacklight = RootUtils.readBacklightValue(backlightPath)
                savedSystemBrightness = RootUtils.executeForOutput(
                    "settings get system screen_brightness"
                ).toIntOrNull() ?: 128
                // Disable auto-brightness and zero via Android layer first,
                // then write sysfs — mirrors Macrodroid "VR On" sequence.
                RootUtils.execute("settings put system screen_brightness_mode 0")
                RootUtils.execute("settings put system screen_brightness 0")
                RootUtils.execute("echo 0 > $backlightPath")
                log("Подсветка выключена")
            } else {
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
        log("Аварийный сброс: кнопка в уведомлении или отключите USB")
    }

    private fun reapplyBacklight() {
        if (!isBlocked) return
        RootUtils.execute("settings put system screen_brightness_mode 0")
        RootUtils.execute("settings put system screen_brightness 0")
        if (backlightPath.isNotEmpty()) {
            RootUtils.execute("echo 0 > $backlightPath")
        }
    }

    private fun restoreAll() {
        val prefs      = Prefs.get(this)
        val screenOff  = prefs.getBoolean(Prefs.KEY_SCREEN_OFF,  true)
        val blockTouch = prefs.getBoolean(Prefs.KEY_BLOCK_TOUCH, true)

        // Set configured restore timeout (mirrors Macrodroid "VR Off")
        val restoreSec = prefs.getInt(Prefs.KEY_RESTORE_TIMEOUT, Prefs.DEFAULT_RESTORE_TIMEOUT)
        RootUtils.execute("settings put system screen_off_timeout ${restoreSec * 1000}")
        log("Таймаут экрана → ${formatTime(restoreSec)}")

        if (screenOff && savedBacklight >= 0) {
            if (backlightPath.isNotEmpty()) {
                RootUtils.execute("echo $savedBacklight > $backlightPath")
            }
            if (savedSystemBrightness >= 0) {
                RootUtils.execute("settings put system screen_brightness $savedSystemBrightness")
                savedSystemBrightness = -1
            }
            if (savedBrightnessMode >= 0) {
                RootUtils.execute("settings put system screen_brightness_mode $savedBrightnessMode")
                savedBrightnessMode = -1
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
        updateNotification("Разблокировано вручную (USB подключён)", false)
        broadcast(ACTION_EMERGENCY_RESTORED)
    }

    // ── Countdown ────────────────────────────────────────────────────────────

    private fun startCountdown(totalSeconds: Int) {
        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(totalSeconds * 1000L, 1000L) {
            override fun onTick(millis: Long) { broadcastCountdown(((millis + 999) / 1000).toInt()) }
            override fun onFinish() { broadcastCountdown(0) }
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
