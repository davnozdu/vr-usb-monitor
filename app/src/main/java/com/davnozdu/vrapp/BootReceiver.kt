package com.davnozdu.vrapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.get(context).getBoolean(Prefs.KEY_ENABLED, true)) return

        ContextCompat.startForegroundService(
            context, Intent(context, UsbMonitorService::class.java),
        )
    }
}
