package com.davnozdu.vrapp

import android.content.*
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.davnozdu.vrapp.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val logLines = ArrayDeque<String>(50)
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbMonitorService.ACTION_USB_CONNECTED    -> setStatus(connected = true)
                UsbMonitorService.ACTION_USB_DISCONNECTED -> setStatus(connected = false)
                UsbMonitorService.ACTION_LOG -> appendLog(intent.getStringExtra("message") ?: "")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToggles()
        checkRoot()
        setStatus(connected = false)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(UsbMonitorService.ACTION_USB_CONNECTED)
            addAction(UsbMonitorService.ACTION_USB_DISCONNECTED)
            addAction(UsbMonitorService.ACTION_LOG)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(eventReceiver, filter)
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(eventReceiver)
    }

    private fun setupToggles() {
        val prefs = Prefs.get(this)

        // Master switch
        binding.switchEnabled.isChecked = prefs.getBoolean(Prefs.KEY_ENABLED, true)
        binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.KEY_ENABLED, checked).apply()
            updateActionTogglesEnabled(checked)
            if (checked) startMonitoring() else stopService(Intent(this, UsbMonitorService::class.java))
        }

        // Action toggles
        binding.switchScreenOff.isChecked   = prefs.getBoolean(Prefs.KEY_SCREEN_OFF,    true)
        binding.switchBlockTouch.isChecked  = prefs.getBoolean(Prefs.KEY_BLOCK_TOUCH,   true)
        binding.switchBlockSensors.isChecked= prefs.getBoolean(Prefs.KEY_BLOCK_SENSORS, false)

        binding.switchScreenOff.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean(Prefs.KEY_SCREEN_OFF, v).apply()
        }
        binding.switchBlockTouch.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean(Prefs.KEY_BLOCK_TOUCH, v).apply()
        }
        binding.switchBlockSensors.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean(Prefs.KEY_BLOCK_SENSORS, v).apply()
        }

        updateActionTogglesEnabled(prefs.getBoolean(Prefs.KEY_ENABLED, true))

        if (prefs.getBoolean(Prefs.KEY_ENABLED, true)) startMonitoring()
    }

    private fun updateActionTogglesEnabled(enabled: Boolean) {
        binding.switchScreenOff.isEnabled    = enabled
        binding.switchBlockTouch.isEnabled   = enabled
        binding.switchBlockSensors.isEnabled = enabled
    }

    private fun checkRoot() {
        Thread {
            val hasRoot = RootUtils.checkRoot()
            runOnUiThread {
                if (hasRoot) {
                    binding.rootStatusText.text = "Root: доступен"
                    binding.rootStatusText.setTextColor(getColor(R.color.green))
                } else {
                    binding.rootStatusText.text = "Root: недоступен — функции отключены"
                    binding.rootStatusText.setTextColor(getColor(R.color.red))
                }
            }
        }.start()
    }

    private fun setStatus(connected: Boolean) {
        if (connected) {
            binding.statusDot.setBackgroundResource(R.drawable.circle_connected)
            binding.statusText.text    = "USB устройство подключено"
            binding.statusSubtext.text = "Активные блокировки применены"
        } else {
            binding.statusDot.setBackgroundResource(R.drawable.circle_disconnected)
            binding.statusText.text    = "USB устройство не подключено"
            binding.statusSubtext.text = "Ожидание подключения..."
        }
    }

    private fun appendLog(message: String) {
        val line = "[${timeFmt.format(Date())}] $message"
        if (logLines.size >= 50) logLines.removeLast()
        logLines.addFirst(line)
        binding.logText.text = logLines.joinToString("\n")
        binding.logScrollView.post { binding.logScrollView.scrollTo(0, 0) }
    }

    private fun startMonitoring() {
        ContextCompat.startForegroundService(this, Intent(this, UsbMonitorService::class.java))
    }
}
