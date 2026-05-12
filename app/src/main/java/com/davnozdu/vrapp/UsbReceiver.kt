package com.davnozdu.vrapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class UsbReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("vrapp", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", true)) return

        val serviceIntent = Intent(context, UsbMonitorService::class.java)
            .putExtra(UsbMonitorService.EXTRA_USB_ACTION, intent.action)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
