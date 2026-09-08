package com.davnozdu.vrapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.SystemClock
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class UsbMonitorService : Service() {

    companion object {
        const val EXTRA_USB_ACTION         = "usb_action"
        const val EXTRA_SERVICE_ACTION     = "service_action"
        const val SERVICE_ACTION_EMERGENCY = "emergency_reset"

        private const val CHANNEL_ID      = "vrapp_channel"
        private const val NOTIFICATION_ID = 1

        /** Максимальный int — экран не гасится сам, пока гарнитура подключена. */
        private const val TIMEOUT_NEVER = "2147483647"

        /** Display manager перебивает запись в sysfs — повторяем через паузу. */
        private const val REAPPLY_DELAY_MS = 2_000L

        /** USB-события приходят чуть раньше, чем обновляется список устройств. */
        private const val SETTLE_DELAY_MS = 400L

        /** Аварийный выход: столько событий вкл/выкл экрана за окно = паника. */
        private const val PANIC_EVENTS    = 4
        private const val PANIC_WINDOW_MS = 3_000L

        private const val WATCHDOG_SCRIPT = "vr_watchdog.sh"

        /** Наличие этого файла означает штатную остановку: сторож выходит молча. */
        private const val WATCHDOG_STOP_FILE = "vr_watchdog_stop"
    }

    private val job   = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    /** Сериализует любые записи в железо, чтобы блокировка и откат не пересекались. */
    private val hwMutex = Mutex()

    private var touchInhibitPath = ""
    private var backlightPath    = ""

    @Volatile private var isConnected = false
    @Volatile private var isBlocked   = false

    private var blockJob: Job? = null

    private val prefs: SharedPreferences by lazy { Prefs.get(this) }
    private val displayManager: DisplayManager by lazy {
        getSystemService(DISPLAY_SERVICE) as DisplayManager
    }

    /**
     * Гасить экран имеет смысл только когда картинка реально ушла в очки.
     * USB-подключение этого не гарантирует: гарнитура поднимает свои USB-
     * устройства сразу, а DisplayPort может не подняться вовсе — тогда экран
     * погас бы впустую, без изображения в очках. Появление внешнего дисплея
     * — точный признак.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = onDisplayEvent("внешний дисплей подключён")
        override fun onDisplayRemoved(displayId: Int) = onDisplayEvent("внешний дисплей отключён")
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    /** Метки последних вкл/выкл экрана — по ним ловим серию нажатий питания. */
    private val screenEvents = ArrayDeque<Long>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isBlocked) return

            val now = SystemClock.elapsedRealtime()
            synchronized(screenEvents) {
                screenEvents.addLast(now)
                while (screenEvents.isNotEmpty() && now - screenEvents.first() > PANIC_WINDOW_MS) {
                    screenEvents.removeFirst()
                }
                if (screenEvents.size >= PANIC_EVENTS) {
                    screenEvents.clear()
                    log("Серия нажатий питания — аварийный сброс")
                    scope.launch { emergencyRestore() }
                    return
                }
            }

            if (intent.action == Intent.ACTION_SCREEN_ON) {
                log("Экран включился — повторное затемнение через 10 с")
                scope.launch {
                    delay(10_000L)
                    if (isBlocked) hwMutex.withLock { reapplyBacklight() }
                }
            }
        }
    }

    // ── Жизненный цикл ───────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Мониторинг активен", false))

        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // null — события приходят на главный поток; вся работа всё равно
        // уходит в корутину, так что блокировать его нечем.
        displayManager.registerDisplayListener(displayListener, null)

        scope.launch {
            val hasRoot = RootUtils.checkRoot()
            VrState.setRootAvailable(hasRoot)
            discoverHardware()
            recoverAfterRestart()
            // Сервис мог стартовать при уже подключённых очках: события
            // onDisplayAdded тогда не будет, дисплей появился раньше нас.
            if (!isBlocked) evaluate("очки уже подключены")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(EXTRA_USB_ACTION)) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED,
            UsbManager.ACTION_USB_DEVICE_DETACHED -> onUsbEvent()
        }
        if (intent?.getStringExtra(EXTRA_SERVICE_ACTION) == SERVICE_ACTION_EMERGENCY) {
            scope.launch { emergencyRestore() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try { displayManager.unregisterDisplayListener(displayListener) } catch (_: Exception) {}
        blockJob?.cancel()

        // Единственное место, где ждём железо синхронно: после возврата из
        // onDestroy процесс может быть убит, и откатывать станет некому.
        // restore() не suspend, поэтому таймаут ставим на настоящем потоке —
        // withTimeoutOrNull здесь нечего было бы прерывать. Если не уложимся,
        // блокировку всё равно снимет root-сторож.
        if (isBlocked) {
            val t = Thread { restore("сервис остановлен") }
            t.start()
            t.join(5_000L)
        }
        scope.cancel()
        RootShell.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Поиск узлов ──────────────────────────────────────────────────────────

    private fun discoverHardware() {
        touchInhibitPath = RootUtils.findTouchInhibit()
        backlightPath    = RootUtils.findBacklightPath()

        log("Сервис запущен")
        log("Тачскрин: ${touchInhibitPath.ifEmpty { "не найден (нет root?)" }}")
        log("Подсветка: ${backlightPath.ifEmpty { "не найден (нет root?)" }}")
        if (touchInhibitPath.isEmpty() || backlightPath.isEmpty()) {
            log("⚠ Выдайте root приложению в KernelSU Manager → SuperUser")
        }
    }

    private fun ensurePathsDiscovered() {
        if (touchInhibitPath.isEmpty()) {
            touchInhibitPath = RootUtils.findTouchInhibit()
            if (touchInhibitPath.isNotEmpty()) log("Тачскрин найден: $touchInhibitPath")
        }
        if (backlightPath.isEmpty()) {
            backlightPath = RootUtils.findBacklightPath()
            if (backlightPath.isNotEmpty()) log("Подсветка найдена: $backlightPath")
        }
    }

    // ── Внешний дисплей ──────────────────────────────────────────────────────

    /**
     * Единственный признак, по которому включается блокировка: есть ли
     * включённый внешний дисплей. USB намеренно не участвует — воткнутая
     * флешка, клавиатура или хаб не должны гасить экран, а вынутая флешка
     * не должна снимать блокировку, пока очки на голове.
     */
    private fun externalDisplayPresent(): Boolean = try {
        displayManager.displays.any { it.displayId != Display.DEFAULT_DISPLAY && it.isValid }
    } catch (_: Exception) {
        false
    }

    private fun onDisplayEvent(reason: String) {
        scope.launch {
            delay(SETTLE_DELAY_MS)
            evaluate(reason)
        }
    }

    /**
     * USB-броадкаст решения не принимает: он лишь будит сервис, если система
     * его выгрузила, после чего состояние всё равно пересчитывается по дисплею.
     */
    private fun onUsbEvent() {
        scope.launch {
            delay(SETTLE_DELAY_MS)
            evaluate("проверка после USB-события")
        }
    }

    private suspend fun evaluate(reason: String) {
        val present = externalDisplayPresent()
        when {
            present && !isConnected -> { log(reason); handleAttach() }
            !present && isConnected -> { log(reason); handleDetach() }
        }
    }

    private fun handleAttach() {
        isConnected = true
        VrState.setPhase(VrState.Phase.WAITING)

        val delaySec = prefs.getInt(Prefs.KEY_DELAY_SECONDS, Prefs.DEFAULT_DELAY)
            .coerceIn(0, 120)

        blockJob?.cancel()
        blockJob = scope.launch {
            if (delaySec == 0) {
                log("Блокирую экран")
            } else {
                log("Блокировка через ${formatTime(delaySec)}")
                for (left in delaySec downTo 1) {
                    VrState.setPhase(VrState.Phase.WAITING, left)
                    delay(1_000L)
                }
            }

            hwMutex.withLock { applyBlocking() }

            // Пауза вне мьютекса: отключение USB в этот момент должно уметь
            // немедленно откатить всё, не дожидаясь повторной записи.
            delay(REAPPLY_DELAY_MS)
            if (isActive && isBlocked && isConnected) {
                hwMutex.withLock { reapplyBacklight() }
                log("Подсветка выключена")
            }
        }
    }

    private suspend fun handleDetach() {
        blockJob?.cancel()
        blockJob = null
        isConnected = false
        if (isBlocked) hwMutex.withLock { restore("внешний дисплей отключён") }
        VrState.setPhase(VrState.Phase.IDLE)
        updateNotification("Мониторинг активен", false)
    }

    // ── Блокировка ───────────────────────────────────────────────────────────

    private fun applyBlocking() {
        ensurePathsDiscovered()

        val screenOff  = prefs.getBoolean(Prefs.KEY_SCREEN_OFF,  true)
        val blockTouch = prefs.getBoolean(Prefs.KEY_BLOCK_TOUCH, true)

        val savedBacklight =
            if (screenOff && backlightPath.isNotEmpty()) RootUtils.readBacklightValue(backlightPath)
            else -1

        // Снимок пишется ДО первой записи в железо. Если процесс умрёт прямо
        // сейчас, поднявшийся заново сервис (или root-сторож) будет знать,
        // что откатывать и к каким значениям.
        saveAppliedSnapshot(screenOff, blockTouch, savedBacklight)
        isBlocked = true
        startWatchdog(savedBacklight)

        RootShell.exec("settings put system screen_off_timeout $TIMEOUT_NEVER")
        log("Таймаут экрана → ∞")

        if (screenOff) {
            RootShell.exec("settings put system screen_brightness_mode 0")
            RootShell.exec("settings put system screen_brightness 0")
            if (backlightPath.isNotEmpty()) {
                RootShell.exec("echo 0 > \"$backlightPath\"")
                log("Подсветка → 0, повтор через 5 с...")
            } else {
                log("Подсветка выключена (только через settings — sysfs не найден)")
            }
        }

        if (blockTouch) {
            if (touchInhibitPath.isNotEmpty()) {
                RootShell.exec("echo 1 > \"$touchInhibitPath\"")
                log("Тач заблокирован ($touchInhibitPath)")
            } else {
                log("Тач: узел inhibited не найден — нет root или устройство не поддерживает")
            }
        }

        VrState.setPhase(VrState.Phase.BLOCKED)
        updateNotification("VR — экран отключён", true)
        log("Выход: отключите очки, 4 нажатия питания или кнопка в уведомлении")
    }

    private fun reapplyBacklight() {
        if (!isBlocked) return
        RootShell.exec("settings put system screen_brightness_mode 0")
        RootShell.exec("settings put system screen_brightness 0")
        if (backlightPath.isNotEmpty()) RootShell.exec("echo 0 > \"$backlightPath\"")
    }

    // ── Восстановление ───────────────────────────────────────────────────────

    /**
     * Откат идёт строго по снимку [saveAppliedSnapshot], а не по текущим
     * настройкам: иначе выключенный на ходу тумблер "блокировать тачскрин"
     * оставил бы тач навсегда заинхибиченным.
     */
    private fun restore(reason: String) {
        if (!prefs.getBoolean(Prefs.KEY_APPLIED, false)) {
            isBlocked = false
            return
        }

        val touchPath      = prefs.getString(Prefs.KEY_APPLIED_TOUCH_PATH, "").orEmpty()
        val blPath         = prefs.getString(Prefs.KEY_APPLIED_BL_PATH, "").orEmpty()
        val blValue        = prefs.getInt(Prefs.KEY_APPLIED_BL_VALUE, -1)
        val didScreenOff   = prefs.getBoolean(Prefs.KEY_APPLIED_SCREEN_OFF, false)
        val didBlockTouch  = prefs.getBoolean(Prefs.KEY_APPLIED_BLOCK_TOUCH, false)

        val restoreSec = prefs.getInt(Prefs.KEY_RESTORE_TIMEOUT, Prefs.DEFAULT_RESTORE_TIMEOUT)
            .coerceIn(15, 60)
        RootShell.exec("settings put system screen_off_timeout ${restoreSec * 1000}")

        if (didScreenOff) {
            if (blPath.isNotEmpty() && blValue >= 0) {
                RootShell.exec("echo $blValue > \"$blPath\"")
            }
            // Абсолютное значение screen_brightness намеренно не возвращаем:
            // шкала зависит от устройства (на OnePlus 15 это 0..4095, а не 0..255),
            // и авто-режим всё равно выставит корректную яркость сам.
            RootShell.exec("settings put system screen_brightness_mode 1")
            log("Подсветка восстановлена (авто)")
        }

        if (didBlockTouch && touchPath.isNotEmpty()) {
            RootShell.exec("echo 0 > \"$touchPath\"")
            log("Тач восстановлен")
        }

        clearAppliedSnapshot()
        stopWatchdog()
        isBlocked = false
        log("Восстановлено: $reason")
    }

    private suspend fun emergencyRestore() {
        if (!isBlocked) return
        hwMutex.withLock { restore("аварийный сброс") }
        VrState.setPhase(VrState.Phase.UNBLOCKED)
        updateNotification("Разблокировано вручную (USB подключён)", false)
    }

    /**
     * Сервис мог быть убит системой в заблокированном состоянии: START_STICKY
     * поднимет его заново с пустым состоянием, а железо останется погашенным.
     */
    private suspend fun recoverAfterRestart() {
        if (!prefs.getBoolean(Prefs.KEY_APPLIED, false)) return

        // Снимок мог пережить откат, сделанный root-сторожем: он снимает
        // блокировку, но до prefs приложения не дотягивается. Верить снимку
        // можно только если железо и правда всё ещё заблокировано.
        if (!hardwareStillBlocked()) {
            log("Снимок блокировки устарел — железо уже разблокировано")
            clearAppliedSnapshot()
            VrState.setPhase(VrState.Phase.IDLE)
            return
        }

        if (externalDisplayPresent()) {
            // Гарнитура на месте — не зажигаем экран человеку посреди просмотра,
            // а просто подхватываем состояние обратно.
            isConnected = true
            isBlocked   = true
            touchInhibitPath = prefs.getString(Prefs.KEY_APPLIED_TOUCH_PATH, "").orEmpty()
            backlightPath    = prefs.getString(Prefs.KEY_APPLIED_BL_PATH, "").orEmpty()
            startWatchdog(prefs.getInt(Prefs.KEY_APPLIED_BL_VALUE, -1))
            VrState.setPhase(VrState.Phase.BLOCKED)
            updateNotification("VR — экран отключён", true)
            log("Сервис перезапущен — состояние блокировки восстановлено")
        } else {
            log("Сервис перезапущен, внешнего дисплея нет — снимаю блокировку")
            hwMutex.withLock { restore("перезапуск сервиса") }
            VrState.setPhase(VrState.Phase.IDLE)
        }
    }

    /** Читает sysfs и говорит, действительно ли блокировка из снимка ещё в силе. */
    private fun hardwareStillBlocked(): Boolean {
        val touchPath = prefs.getString(Prefs.KEY_APPLIED_TOUCH_PATH, "").orEmpty()
        val blPath    = prefs.getString(Prefs.KEY_APPLIED_BL_PATH, "").orEmpty()

        if (prefs.getBoolean(Prefs.KEY_APPLIED_BLOCK_TOUCH, false) && touchPath.isNotEmpty()) {
            return RootShell.out("cat \"$touchPath\"") == "1"
        }
        if (prefs.getBoolean(Prefs.KEY_APPLIED_SCREEN_OFF, false) && blPath.isNotEmpty()) {
            return RootShell.out("cat \"$blPath\"") == "0"
        }
        // Нечего проверить (нет root или обе опции выключены) — считаем снимок валидным.
        return true
    }

    // ── Снимок применённого состояния ────────────────────────────────────────

    private fun saveAppliedSnapshot(screenOff: Boolean, blockTouch: Boolean, backlight: Int) {
        prefs.edit()
            .putBoolean(Prefs.KEY_APPLIED, true)
            .putString(Prefs.KEY_APPLIED_TOUCH_PATH, touchInhibitPath)
            .putString(Prefs.KEY_APPLIED_BL_PATH, backlightPath)
            .putInt(Prefs.KEY_APPLIED_BL_VALUE, backlight)
            .putBoolean(Prefs.KEY_APPLIED_SCREEN_OFF, screenOff)
            .putBoolean(Prefs.KEY_APPLIED_BLOCK_TOUCH, blockTouch)
            .commit()   // commit, а не apply: процесс может не дожить до сброса на диск
    }

    private fun clearAppliedSnapshot() {
        prefs.edit().putBoolean(Prefs.KEY_APPLIED, false).commit()
    }

    // ── Root-сторож ("мёртвая рука") ─────────────────────────────────────────

    private fun stopFile() = File(filesDir, WATCHDOG_STOP_FILE)

    /**
     * Сторож живёт в root-шелле и следит за /proc/<pid> приложения. Если процесс
     * исчезнет (OOM-киллер, краш), он сам вернёт яркость и снимет блокировку —
     * иначе телефон остался бы с чёрным экраном и мёртвым тачем без выхода.
     *
     * Слежение идёт именно за процессом, а не за файлом-пульсом от приложения:
     * пульс останавливался, как только устройство уходило в Doze (там система
     * перестаёт уважать PARTIAL_WAKE_LOCK), и сторож снимал блокировку с живым
     * приложением. Наличие /proc/<pid> от таймеров приложения не зависит вовсе.
     */
    private fun startWatchdog(savedBacklight: Int) {
        val script = File(filesDir, WATCHDOG_SCRIPT)
        script.writeText(WATCHDOG_SH)
        stopFile().delete()

        val restoreSec = prefs.getInt(Prefs.KEY_RESTORE_TIMEOUT, Prefs.DEFAULT_RESTORE_TIMEOUT)
            .coerceIn(15, 60)

        val args = listOf(
            android.os.Process.myPid().toString(),
            stopFile().absolutePath,
            touchInhibitPath,
            backlightPath,
            savedBacklight.toString(),
            (restoreSec * 1000).toString(),
        ).joinToString(" ") { "\"$it\"" }

        RootShell.exec("nohup sh \"${script.absolutePath}\" $args >/dev/null 2>&1 &")
    }

    /** Штатная остановка: сторож увидит файл и выйдет, ничего не восстанавливая. */
    private fun stopWatchdog() {
        try { stopFile().writeText("stop") } catch (_: Exception) {}
    }

    // ── Прочее ───────────────────────────────────────────────────────────────

    private fun formatTime(s: Int) = "%d:%02d".format(s / 60, s % 60)

    private fun log(msg: String) = VrState.log(msg)

    private fun createNotificationChannel() {
        // IMPORTANCE_MIN: иконки в статус-баре нет, уведомление видно только
        // в развёрнутой шторке — там же лежит кнопка аварийного сброса.
        val channel = NotificationChannel(
            CHANNEL_ID, "VR Monitor", NotificationManager.IMPORTANCE_MIN,
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, showReset: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VR Monitor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSilent(true)
            .setShowWhen(false)
            .setOngoing(true)
        if (showReset) {
            val reset = PendingIntent.getService(
                this, 1,
                Intent(this, UsbMonitorService::class.java)
                    .putExtra(EXTRA_SERVICE_ACTION, SERVICE_ACTION_EMERGENCY),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel, "Аварийный сброс", reset,
            )
        }
        return builder.build()
    }

    private fun updateNotification(text: String, showReset: Boolean) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text, showReset))
        } catch (_: Exception) {
            // POST_NOTIFICATIONS могли не выдать — сервис при этом работает дальше
        }
    }

}

/**
 * Сторож живёт в root-шелле и переживает смерть приложения.
 * Аргументы: pid приложения, файл штатной остановки, узел inhibited,
 * узел brightness, сохранённая яркость, таймаут экрана в мс.
 */
private val WATCHDOG_SH = """
#!/system/bin/sh
PID="${'$'}1"; STOP="${'$'}2"; TOUCH="${'$'}3"; BL="${'$'}4"; BLVAL="${'$'}5"; TIMEOUT="${'$'}6"

while [ -d "/proc/${'$'}PID" ]; do
  # Приложение сняло блокировку само — уходим, ничего не трогая.
  [ -f "${'$'}STOP" ] && exit 0
  # Тот ли это процесс: pid мог быть переиспользован после смерти приложения.
  grep -q vrapp "/proc/${'$'}PID/cmdline" 2>/dev/null || break
  sleep 2
done

# Ещё одна проверка на случай гонки: приложение успело завершиться штатно.
[ -f "${'$'}STOP" ] && exit 0

# Процесс приложения исчез — снимаем блокировку сами.
[ -n "${'$'}TOUCH" ] && echo 0 > "${'$'}TOUCH" 2>/dev/null
if [ -n "${'$'}BL" ] && [ "${'$'}BLVAL" -ge 0 ] 2>/dev/null; then
  echo "${'$'}BLVAL" > "${'$'}BL" 2>/dev/null
fi
settings put system screen_brightness_mode 1
settings put system screen_off_timeout "${'$'}TIMEOUT"
""".trimIndent()
