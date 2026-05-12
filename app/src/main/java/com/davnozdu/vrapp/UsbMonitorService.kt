package com.davnozdu.vrapp

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class UsbMonitorService : Service() {

    companion object {
        const val ACTION_USB_CONNECTED    = "com.davnozdu.vrapp.USB_CONNECTED"
        const val ACTION_USB_DISCONNECTED = "com.davnozdu.vrapp.USB_DISCONNECTED"
        const val ACTION_COUNTDOWN        = "com.davnozdu.vrapp.COUNTDOWN"
        const val ACTION_LOG              = "com.davnozdu.vrapp.LOG"
        const val EXTRA_USB_ACTION        = "usb_action"
        const val EXTRA_SECONDS_LEFT      = "seconds_left"

        private const val CHANNEL_ID      = "vrapp_channel"
        private const val NOTIFICATION_ID = 1
    }

    private var touchscreenDevice: String = ""
    private var sensorDevices: List<String> = emptyList()

    private var isConnected = false
    private var isBlocked   = false

    private var savedBrightness     = -1
    private var savedBrightnessMode = -1

    private val handler = Handler(Looper.getMainLooper())
    private var pendingBlock: Runnable? = null
    private var countdownTimer: CountDownTimer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Мониторинг активен"))
        Thread {
            touchscreenDevice = RootUtils.findTouchscreen()
            sensorDevices = RootUtils.findMotionSensors()
            log("Сервис запущен")
            log("Тачскрин: ${touchscreenDevice.ifEmpty { "не найден" }}")
            log("Датчики: ${if (sensorDevices.isEmpty()) "не найдены (HAL/CHRE)" else sensorDevices.joinToString()}")
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(EXTRA_USB_ACTION)) {
            "android.hardware.usb.action.USB_DEVICE_ATTACHED" -> onUsbAttached()
            "android.hardware.usb.action.USB_DEVICE_DETACHED" -> onUsbDetached()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelPending()
        if (isConnected || isBlocked) onUsbDetached()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── USB events ─────────────────────────────────────────────────────────

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

        updateNotification("Мониторинг активен")
        broadcast(ACTION_USB_DISCONNECTED)
    }

    // ── Blocking ────────────────────────────────────────────────────────────

    private fun applyBlocking() {
        val prefs        = Prefs.get(this)
        val screenOff    = prefs.getBoolean(Prefs.KEY_SCREEN_OFF,    true)
        val blockTouch   = prefs.getBoolean(Prefs.KEY_BLOCK_TOUCH,   true)
        val blockSensors = prefs.getBoolean(Prefs.KEY_BLOCK_SENSORS, false)

        if (screenOff) {
            // Save current brightness and mode, then set backlight to 0.
            // Using brightness=0 keeps the display signal alive for the VR headset
            // while the phone screen goes dark (unlike KEYCODE_SLEEP which cuts the signal).
            savedBrightnessMode = RootUtils.executeForOutput(
                "settings get system screen_brightness_mode"
            ).toIntOrNull() ?: 1
            savedBrightness = RootUtils.executeForOutput(
                "settings get system screen_brightness"
            ).toIntOrNull() ?: 128

            RootUtils.execute("settings put system screen_brightness_mode 0")
            RootUtils.execute("settings put system screen_brightness 0")
            log("Подсветка выключена (яркость → 0, дисплей продолжает работать)")
        }

        if (blockTouch && touchscreenDevice.isNotEmpty()) {
            RootUtils.execute("chmod 000 $touchscreenDevice")
            log("Тач заблокирован")
        }

        if (blockSensors) {
            if (sensorDevices.isNotEmpty()) {
                RootUtils.chmodDevices(sensorDevices, "000")
                log("Датчики движения заблокированы (${sensorDevices.size} устр.)")
            } else {
                log("Датчики: блокировка через /dev/input недоступна на этом устройстве")
            }
        }

        isBlocked = true
        updateNotification("VR гарнитура подключена — экран отключён")
    }

    private fun restoreAll() {
        val prefs        = Prefs.get(this)
        val screenOff    = prefs.getBoolean(Prefs.KEY_SCREEN_OFF,    true)
        val blockTouch   = prefs.getBoolean(Prefs.KEY_BLOCK_TOUCH,   true)
        val blockSensors = prefs.getBoolean(Prefs.KEY_BLOCK_SENSORS, false)

        if (screenOff && savedBrightness >= 0) {
            RootUtils.execute("settings put system screen_brightness $savedBrightness")
            RootUtils.execute("settings put system screen_brightness_mode $savedBrightnessMode")
            savedBrightness = -1
            log("Подсветка восстановлена")
        }
        if (blockTouch && touchscreenDevice.isNotEmpty()) {
            RootUtils.execute("chmod 664 $touchscreenDevice")
            log("Тач восстановлен")
        }
        if (blockSensors && sensorDevices.isNotEmpty()) {
            RootUtils.chmodDevices(sensorDevices, "664")
            log("Датчики движения восстановлены")
        }
    }

    // ── Countdown ───────────────────────────────────────────────────────────

    private fun startCountdown(totalSeconds: Int) {
        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(totalSeconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                broadcastCountdown(((millisUntilFinished + 999) / 1000).toInt())
            }
            override fun onFinish() = broadcastCountdown(0)
        }.start()
    }

    private fun cancelPending() {
        countdownTimer?.cancel()
        countdownTimer = null
        pendingBlock?.let { handler.removeCallbacks(it) }
        pendingBlock = null
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun formatTime(seconds: Int): String {
        val m = seconds / 60
        val s = seconds % 60
        return "%d:%02d".format(m, s)
    }

    private fun log(msg: String) {
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent(ACTION_LOG).putExtra("message", msg))
    }

    private fun broadcast(action: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(action))
    }

    private fun broadcastCountdown(secondsLeft: Int) {
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent(ACTION_COUNTDOWN).putExtra(EXTRA_SECONDS_LEFT, secondsLeft))
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "VR Monitor", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VR Monitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
