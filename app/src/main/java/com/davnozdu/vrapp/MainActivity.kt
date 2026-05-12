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
                UsbMonitorService.ACTION_USB_CONNECTED    -> setStatus(State.WAITING)
                UsbMonitorService.ACTION_USB_DISCONNECTED -> setStatus(State.IDLE)
                UsbMonitorService.ACTION_EMERGENCY_RESTORED -> setStatus(State.UNBLOCKED)
                UsbMonitorService.ACTION_COUNTDOWN -> {
                    val left = intent.getIntExtra(UsbMonitorService.EXTRA_SECONDS_LEFT, 0)
                    if (left == 0) setStatus(State.BLOCKED) else setStatus(State.WAITING, left)
                }
                UsbMonitorService.ACTION_LOG -> appendLog(intent.getStringExtra("message") ?: "")
            }
        }
    }

    private enum class State { IDLE, WAITING, BLOCKED, UNBLOCKED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToggles()
        setupDelaySlider()
        setupRestoreSlider()
        checkRoot()
        setStatus(State.IDLE)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(UsbMonitorService.ACTION_USB_CONNECTED)
            addAction(UsbMonitorService.ACTION_USB_DISCONNECTED)
            addAction(UsbMonitorService.ACTION_COUNTDOWN)
            addAction(UsbMonitorService.ACTION_EMERGENCY_RESTORED)
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

        binding.switchScreenOff.isChecked  = prefs.getBoolean(Prefs.KEY_SCREEN_OFF,  true)
        binding.switchBlockTouch.isChecked = prefs.getBoolean(Prefs.KEY_BLOCK_TOUCH, true)

        binding.switchScreenOff.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean(Prefs.KEY_SCREEN_OFF, v).apply()
        }
        binding.switchBlockTouch.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean(Prefs.KEY_BLOCK_TOUCH, v).apply()
        }

        setActionTogglesEnabled(prefs.getBoolean(Prefs.KEY_ENABLED, true))
        if (prefs.getBoolean(Prefs.KEY_ENABLED, true)) startMonitoring()
    }

    private fun setupDelaySlider() {
        val prefs = Prefs.get(this)
        val saved = prefs.getInt(Prefs.KEY_DELAY_SECONDS, Prefs.DEFAULT_DELAY)
        // Clamp to new valid range [0, 120] in case old saved value > 120
        val clamped = saved.coerceIn(0, 120).let { it - (it % 5) }
        binding.delaySlider.value = clamped.toFloat()
        binding.delayValueText.text = formatDelay(clamped)
        binding.delaySlider.addOnChangeListener { _, value, _ ->
            val sec = value.toInt()
            prefs.edit().putInt(Prefs.KEY_DELAY_SECONDS, sec).apply()
            binding.delayValueText.text = formatDelay(sec)
        }
    }

    private fun setupRestoreSlider() {
        val prefs = Prefs.get(this)
        val saved = prefs.getInt(Prefs.KEY_RESTORE_TIMEOUT, Prefs.DEFAULT_RESTORE_TIMEOUT)
        // Valid values: 15, 30, 45, 60
        val clamped = (saved.coerceIn(15, 60) / 15) * 15
        binding.restoreTimeoutSlider.value = clamped.toFloat()
        binding.restoreTimeoutValueText.text = formatDelay(clamped)
        binding.restoreTimeoutSlider.addOnChangeListener { _, value, _ ->
            val sec = value.toInt()
            prefs.edit().putInt(Prefs.KEY_RESTORE_TIMEOUT, sec).apply()
            binding.restoreTimeoutValueText.text = formatDelay(sec)
        }
    }

    private fun setActionTogglesEnabled(enabled: Boolean) {
        binding.switchScreenOff.isEnabled        = enabled
        binding.switchBlockTouch.isEnabled       = enabled
        binding.delaySlider.isEnabled            = enabled
        binding.restoreTimeoutSlider.isEnabled   = enabled
    }

    private fun checkRoot() {
        Thread {
            val hasRoot = RootUtils.checkRoot()
            runOnUiThread {
                binding.rootStatusText.text = if (hasRoot) "Root: доступен"
                    else "Root: недоступен — выдайте в KernelSU → SuperUser"
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
            State.WAITING -> {
                binding.statusDot.setBackgroundResource(R.drawable.circle_waiting)
                binding.statusText.text    = "USB подключено"
                binding.statusSubtext.text =
                    if (secondsLeft > 0) "Блокировка через ${formatDelay(secondsLeft)}..."
                    else "Подготовка..."
            }
            State.BLOCKED -> {
                binding.statusDot.setBackgroundResource(R.drawable.circle_connected)
                binding.statusText.text    = "Активно — экран отключён"
                binding.statusSubtext.text = "2× кнопка питания = аварийный сброс"
            }
            State.UNBLOCKED -> {
                binding.statusDot.setBackgroundResource(R.drawable.circle_waiting)
                binding.statusText.text    = "Разблокировано вручную"
                binding.statusSubtext.text = "USB всё ещё подключён"
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
