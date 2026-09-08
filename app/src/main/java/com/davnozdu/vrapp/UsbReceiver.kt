package com.davnozdu.vrapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat

class UsbReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Ресивер экспортирован (иначе система не доставит USB-события), поэтому
        // чужой интент с любым другим действием отбрасываем. Само решение
        // блокировать или нет сервис принимает по UsbManager.deviceList,
        // а не по факту получения этого броадкаста.
        val action = intent.action
        if (action != UsbManager.ACTION_USB_DEVICE_ATTACHED &&
            action != UsbManager.ACTION_USB_DEVICE_DETACHED
        ) return

        if (!Prefs.get(context).getBoolean(Prefs.KEY_ENABLED, true)) return

        ContextCompat.startForegroundService(
            context,
            Intent(context, UsbMonitorService::class.java)
                .putExtra(UsbMonitorService.EXTRA_USB_ACTION, action),
        )
    }
}
