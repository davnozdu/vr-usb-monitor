package com.davnozdu.vrapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.CheckBox
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.davnozdu.vrapp.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) warnNotificationsBlocked()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        setupToggles()
        setupDelaySlider()
        setupRestoreSlider()
        observeState()

        ensureNotificationPermission()
        maybeShowInputTip()
        maybeRequestBatteryOptimization()
    }

    /** targetSdk 35+ рисует контент под системными панелями — возвращаем отступы руками. */
    private fun applyWindowInsets() {
        val base = Insets.of(
            binding.root.paddingLeft, binding.root.paddingTop,
            binding.root.paddingRight, binding.root.paddingBottom,
        )
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(
                left   = base.left + bars.left,
                top    = base.top + bars.top,
                right  = base.right + bars.right,
                bottom = base.bottom + bars.bottom,
            )
            windowInsets
        }
    }

    // ── Состояние ────────────────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    VrState.status.collect { render(it) }
                }
                launch {
                    VrState.rootAvailable.collect { available ->
                        if (available == null) return@collect
                        binding.rootStatusText.text =
                            if (available) "Root: доступен"
                            else "Root: недоступен — выдайте в KernelSU → SuperUser"
                        binding.rootStatusText.setTextColor(
                            getColor(if (available) R.color.green else R.color.red),
                        )
                    }
                }
                launch {
                    VrState.log.collect { appendLog(it) }
                }
            }
        }
    }

    private fun render(status: VrState.Status) {
        when (status.phase) {
            VrState.Phase.IDLE -> {
                binding.statusDot.setBackgroundResource(R.drawable.circle_disconnected)
                binding.statusText.text    = "USB устройство не подключено"
                binding.statusSubtext.text = "Ожидание подключения..."
            }
            VrState.Phase.WAITING -> {
                binding.statusDot.setBackgroundResource(R.drawable.circle_waiting)
                binding.statusText.text    = "USB подключено"
                binding.statusSubtext.text =
                    if (status.secondsLeft > 0) "Блокировка через ${formatDelay(status.secondsLeft)}..."
                    else "Подготовка..."
            }
            VrState.Phase.BLOCKED -> {
                binding.statusDot.setBackgroundResource(R.drawable.circle_connected)
                binding.statusText.text    = "Активно — экран отключён"
                binding.statusSubtext.text = "USB, 4 нажатия питания или уведомление"
            }
            VrState.Phase.UNBLOCKED -> {
                binding.statusDot.setBackgroundResource(R.drawable.circle_waiting)
                binding.statusText.text    = "Разблокировано вручную"
                binding.statusSubtext.text = "USB всё ещё подключён"
            }
        }
    }

    private fun appendLog(message: String) {
        logLines.addFirst("[${timeFmt.format(Date())}] $message")
        while (logLines.size > 50) logLines.removeLast()
        binding.logText.text = logLines.joinToString("\n")
        binding.logScrollView.post { binding.logScrollView.scrollTo(0, 0) }
    }

    // ── Разрешения ───────────────────────────────────────────────────────────

    /**
     * Без POST_NOTIFICATIONS уведомление foreground-сервиса не публикуется —
     * а вместе с ним пропадает кнопка аварийного сброса, единственный выход
     * при погашенной подсветке кроме отключения USB.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return

        if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            AlertDialog.Builder(this)
                .setTitle("Нужны уведомления")
                .setMessage(
                    "В уведомлении живёт кнопка аварийного сброса. Без неё, " +
                        "когда подсветка погашена, снять блокировку можно только " +
                        "отключением USB или четырьмя нажатиями кнопки питания.",
                )
                .setPositiveButton("Разрешить") { _, _ ->
                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                .setNegativeButton("Позже", null)
                .show()
        } else {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun warnNotificationsBlocked() {
        AlertDialog.Builder(this)
            .setTitle("Уведомления отключены")
            .setMessage(
                "Кнопка аварийного сброса будет недоступна. Останутся: отключить USB " +
                    "или нажать кнопку питания 4 раза подряд.",
            )
            .setPositiveButton("Открыть настройки") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                )
            }
            .setNegativeButton("Понятно", null)
            .show()
    }

    private fun maybeShowInputTip() {
        val prefs = Prefs.get(this)
        if (prefs.getBoolean(Prefs.KEY_HIDE_INPUT_TIP, false)) return

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_input_tip, null)
        val checkbox = view.findViewById<CheckBox>(R.id.dontShowAgain)

        AlertDialog.Builder(this)
            .setTitle("Памятка")
            .setView(view)
            .setPositiveButton("Понятно") { dialog, _ ->
                if (checkbox.isChecked) {
                    prefs.edit().putBoolean(Prefs.KEY_HIDE_INPUT_TIP, true).apply()
                }
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun maybeRequestBatteryOptimization() {
        val prefs = Prefs.get(this)
        if (prefs.getBoolean(Prefs.KEY_BATTERY_OPT_ASKED, false)) return

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            prefs.edit().putBoolean(Prefs.KEY_BATTERY_OPT_ASKED, true).apply()
            return
        }

        try {
            @Suppress("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
        prefs.edit().putBoolean(Prefs.KEY_BATTERY_OPT_ASKED, true).apply()
    }

    // ── Настройки ────────────────────────────────────────────────────────────

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

        val enabled = prefs.getBoolean(Prefs.KEY_ENABLED, true)
        setActionTogglesEnabled(enabled)
        if (enabled) startMonitoring()
    }

    private fun setupDelaySlider() {
        val prefs = Prefs.get(this)
        val saved = prefs.getInt(Prefs.KEY_DELAY_SECONDS, Prefs.DEFAULT_DELAY)
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
        binding.switchScreenOff.isEnabled      = enabled
        binding.switchBlockTouch.isEnabled     = enabled
        binding.delaySlider.isEnabled          = enabled
        binding.restoreTimeoutSlider.isEnabled = enabled
    }

    private fun startMonitoring() {
        ContextCompat.startForegroundService(
            this, Intent(this, UsbMonitorService::class.java),
        )
    }

    private fun formatDelay(seconds: Int): String {
        if (seconds == 0) return "Сразу"
        val m = seconds / 60
        val s = seconds % 60
        return when {
            m == 0 -> "${s}с"
            s == 0 -> "$m мин"
            else   -> "$m:${"%02d".format(s)}"
        }
    }
}
