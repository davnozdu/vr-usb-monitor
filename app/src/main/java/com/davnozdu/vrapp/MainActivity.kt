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

        setupSwitch()
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

    private fun setupSwitch() {
        val prefs = getSharedPreferences("vrapp", MODE_PRIVATE)
        val enabled = prefs.getBoolean("enabled", true)
        binding.monitoringSwitch.isChecked = enabled

        if (enabled) startService()

        binding.monitoringSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("enabled", checked).apply()
            if (checked) startService() else stopService(Intent(this, UsbMonitorService::class.java))
        }
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
            binding.statusText.text = "USB устройство подключено"
            binding.statusSubtext.text = "Экран выключен, тач заблокирован"
        } else {
            binding.statusDot.setBackgroundResource(R.drawable.circle_disconnected)
            binding.statusText.text = "USB устройство не подключено"
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

    private fun startService() {
        ContextCompat.startForegroundService(this, Intent(this, UsbMonitorService::class.java))
    }
}
