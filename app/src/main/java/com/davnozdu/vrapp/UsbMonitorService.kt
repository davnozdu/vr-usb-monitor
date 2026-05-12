package com.davnozdu.vrapp

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class UsbMonitorService : Service() {

    companion object {
        const val ACTION_USB_CONNECTED    = "com.davnozdu.vrapp.USB_CONNECTED"
        const val ACTION_USB_DISCONNECTED = "com.davnozdu.vrapp.USB_DISCONNECTED"
        const val ACTION_LOG              = "com.davnozdu.vrapp.LOG"
        const val EXTRA_USB_ACTION        = "usb_action"

        private const val CHANNEL_ID      = "vrapp_channel"
        private const val NOTIFICATION_ID = 1
    }

    private var touchscreenDevice: String? = null
    private var isConnected = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Мониторинг активен"))
        touchscreenDevice = RootUtils.findTouchscreen()
        log("Сервис запущен. Тачскрин: ${touchscreenDevice?.ifEmpty { "не найден" } ?: "не найден"}")
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
        if (isConnected) onUsbDetached()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onUsbAttached() {
        if (isConnected) return
        isConnected = true
        log("USB подключено — блокирую тач и выключаю экран")

        touchscreenDevice?.takeIf { it.isNotEmpty() }?.let { dev ->
            RootUtils.execute("chmod 000 $dev")
        }
        RootUtils.execute("input keyevent 223")

        updateNotification("VR гарнитура подключена")
        broadcast(ACTION_USB_CONNECTED)
    }

    private fun onUsbDetached() {
        if (!isConnected) return
        isConnected = false
        log("USB отключено — восстанавливаю тач и включаю экран")

        RootUtils.execute("input keyevent 224")
        touchscreenDevice?.takeIf { it.isNotEmpty() }?.let { dev ->
            RootUtils.execute("chmod 664 $dev")
        }

        updateNotification("Мониторинг активен")
        broadcast(ACTION_USB_DISCONNECTED)
    }

    private fun log(message: String) {
        LocalBroadcastManager.getInstance(this)
            .sendBroadcast(Intent(ACTION_LOG).putExtra("message", message))
    }

    private fun broadcast(action: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(action))
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
