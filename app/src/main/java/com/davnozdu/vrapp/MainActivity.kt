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
                UsbMonitorService.ACTION_USB_CONNECTED    -> setStatus(State.CONNECTED_WAITING)
                UsbMonitorService.ACTION_USB_DISCONNECTED -> setStatus(State.IDLE)
                UsbMonitorService.ACTION_COUNTDOWN        -> {
                    val left = intent.getIntExtra(UsbMonitorService.EXTRA_SECONDS_LEFT, 0)
                    if (left == 0) setStatus(State.BLOCKED)
                    else setStatus(State.CONNECTED_WAITING, left)
                }
                UsbMonitorService.ACTION_LOG -> appendLog(intent.getStringExtra("message") ?: "")
            }
        }
    }

    private enum class State { IDLE, CONNECTED_WAITING, BLOCKED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToggles()
        setupDelaySlider()
        checkRoot()
        setStatus(State.IDLE)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(UsbMonitorService.ACTION_USB_CONNECTED)
            addAction(UsbMonitorService.ACTION_USB_DISCONNECTED)
            addAction(UsbMonitorService.ACTION_COUNTDOWN)
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

        binding.switchEnabled.isChecked = prefs.getBoolean(Prefs.KEY_ENABLED, true)
        binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.KEY_ENABLED, checked).apply()
            setActionTogglesEnabled(checked)
            if (checked) startMonitoring()
            else stopService(Intent(this, UsbMonitorService::class.java))
        }

        binding.switchScreenOff.isChecked    = prefs.getBoolean(Prefs.KEY_SCREEN_OFF,    true)
        binding.switchBlockTouch.isChecked   = prefs.getBoolean(Prefs.KEY_BLOCK_TOUCH,   true)
        binding.switchBlockSensors.isChecked = prefs.getBoolean(Prefs.KEY_BLOCK_SENSORS, false)

        binding.switchScreenOff.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean(Prefs.KEY_SCREEN_OFF, v).apply()
        }
        binding.switchBlockTouch.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean(Prefs.KEY_BLOCK_TOUCH, v).apply()
        }
        binding.switchBlockSensors.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean(Prefs.KEY_BLOCK_SENSORS, v).apply()
        }

        setActionTogglesEnabled(prefs.getBoolean(Prefs.KEY_ENABLED, true))
        if (prefs.getBoolean(Prefs.KEY_ENABLED, true)) startMonitoring()
    }

    private fun setupDelaySlider() {
        val prefs = Prefs.get(this)
        val saved = prefs.getInt(Prefs.KEY_DELAY_SECONDS, Prefs.DEFAULT_DELAY)

        binding.delaySlider.value = saved.toFloat()
        binding.delayValueText.text = formatDelay(saved)

        binding.delaySlider.addOnChangeListener { _, value, _ ->
            val sec = value.toInt()
            prefs.edit().putInt(Prefs.KEY_DELAY_SECONDS, sec).apply()
            binding.delayValueText.text = formatDelay(sec)
        }
    }

    private fun setActionTogglesEnabled(enabled: Boolean) {
        binding.switchScreenOff.isEnabled    = enabled
        binding.switchBlockTouch.isEnabled   = enabled
        binding.switchBlockSensors.isEnabled = enabled
        binding.delaySlider.isEnabled        = enabled
    }

    private fun checkRoot() {
        Thread {
            val hasRoot = RootUtils.checkRoot()
            runOnUiThread {
                binding.rootStatusText.text = if (hasRoot) "Root: доступен" else "Root: недоступен"
                binding.rootStatusText.setTextColor(
                    getColor(if (hasRoot) R.color.green else R.color.red)
                )
            }
        }.start()
    }

    private fun setStatus(state: State, secondsLeft: Int = 0) {
        when (state) {
            State.IDLE -> {
                binding.statusDot.setBackgroundResource(R.drawable.circle_disconnected)
                binding.statusText.text    = "USB устройство не подключено"
                binding.statusSubtext.text = "Ожидание подключения..."
            }
            State.CONNECTED_WAITING -> {
                binding.statusDot.setBackgroundResource(R.drawable.circle_waiting)
                binding.statusText.text    = "USB подключено"
                binding.statusSubtext.text =
                    if (secondsLeft > 0) "Блокировка через ${formatDelay(secondsLeft)}..."
                    else "Подготовка..."
            }
            State.BLOCKED -> {
                binding.statusDot.setBackgroundResource(R.drawable.circle_connected)
                binding.statusText.text    = "Активно — экран отключён"
                binding.statusSubtext.text = "Тач и подсветка заблокированы"
            }
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

    private fun formatDelay(seconds: Int): String {
        if (seconds == 0) return "Сразу"
        val m = seconds / 60
        val s = seconds % 60
        return if (m == 0) "${s}с" else if (s == 0) "${m} мин" else "${m}:${"%02d".format(s)}"
    }
}
