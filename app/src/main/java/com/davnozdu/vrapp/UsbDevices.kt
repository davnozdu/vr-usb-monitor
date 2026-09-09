package com.davnozdu.vrapp

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import java.io.File

/**
 * Фильтр по конкретным очкам.
 *
 * Раньше сервис реагировал на любой внешний дисплей. Этого хватало, пока
 * гарнитура была одна, но монитор или телевизор по тому же USB-C выглядят
 * ровно так же — и телефон гас на них тоже.
 *
 * Идентификаторы не зашиты в код: очки можно заменить или иметь несколько,
 * и пересобирать APK ради этого не нужно. Пользователь выбирает нужные
 * устройства из списка реально подключённых.
 */
object UsbDevices {

    /** Одна запись списка: пара VID:PID и человекочитаемое имя. */
    data class GlassesId(val vendorId: Int, val productId: Int, val label: String) {

        /** Ключ хранения. Имя идёт последним — в нём могут быть любые символы. */
        fun serialize(): String = "%04x:%04x:%s".format(vendorId, productId, label)

        val idString: String get() = "%04x:%04x".format(vendorId, productId)

        override fun toString(): String = "$label ($idString)"

        companion object {
            fun parse(raw: String): GlassesId? {
                // limit = 3: имя может содержать двоеточия, дробить его нельзя.
                val parts = raw.split(":", limit = 3)
                if (parts.size < 2) return null
                val vid = parts[0].toIntOrNull(16) ?: return null
                val pid = parts[1].toIntOrNull(16) ?: return null
                val label = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "%04x:%04x".format(vid, pid)
                return GlassesId(vid, pid, label)
            }
        }
    }

    /** Имя устройства для показа в списке. Часть гарнитур не отдаёт product name. */
    private fun labelFor(d: UsbDevice): String {
        val product = d.productName?.takeIf { it.isNotBlank() }
        val vendor = d.manufacturerName?.takeIf { it.isNotBlank() }
        return when {
            product != null && vendor != null && !product.startsWith(vendor) -> "$vendor $product"
            product != null -> product
            vendor != null -> vendor
            else -> "USB-устройство %04x:%04x".format(d.vendorId, d.productId)
        }
    }

    /** Что воткнуто прямо сейчас. Дубликаты по VID:PID схлопываются. */
    fun connected(context: Context): List<GlassesId> = try {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        manager.deviceList.values
            .map { GlassesId(it.vendorId, it.productId, labelFor(it)) }
            .distinctBy { it.idString }
            .sortedBy { it.label.lowercase() }
    } catch (_: Exception) {
        emptyList()
    }

    fun saved(context: Context): List<GlassesId> =
        Prefs.get(context).getStringSet(Prefs.KEY_DEVICES, emptySet())
            .orEmpty()
            .mapNotNull { GlassesId.parse(it) }
            .sortedBy { it.label.lowercase() }

    fun save(context: Context, devices: Collection<GlassesId>) {
        // Копия множества обязательна: SharedPreferences не гарантирует
        // корректного чтения того же экземпляра Set, который в него положили.
        Prefs.get(context).edit()
            .putStringSet(Prefs.KEY_DEVICES, devices.map { it.serialize() }.toSet())
            .commit()
        exportForModule(context)
    }

    /**
     * Нужно ли реагировать на текущее подключение.
     *
     * Фильтр выключен или список пуст — работаем как раньше, на любой внешний
     * дисплей. Пустой список намеренно означает «любые», иначе пользователь,
     * включивший тумблер до выбора очков, остался бы с молчащим приложением.
     */
    fun shouldReact(context: Context): Boolean {
        val prefs = Prefs.get(context)
        if (!prefs.getBoolean(Prefs.KEY_FILTER_ENABLED, false)) return true
        val wanted = saved(context)
        if (wanted.isEmpty()) return true
        val present = connected(context).map { it.idString }.toSet()
        return wanted.any { it.idString in present }
    }

    /** Совпавшие очки — только для сообщения в журнале. */
    fun matched(context: Context): List<GlassesId> {
        val present = connected(context).map { it.idString }.toSet()
        return saved(context).filter { it.idString in present }
    }

    /**
     * Выгрузка настроек в файл для модуля VR Display Mode: он работает из
     * root-шелла и в SharedPreferences приложения лезть не должен — формат
     * приватный и может измениться. Файл читается только root.
     */
    fun exportForModule(context: Context) {
        try {
            val prefs = Prefs.get(context)
            val lines = buildString {
                append("enabled=").append(if (prefs.getBoolean(Prefs.KEY_ENABLED, true)) 1 else 0).append('\n')
                append("filter=").append(if (prefs.getBoolean(Prefs.KEY_FILTER_ENABLED, false)) 1 else 0).append('\n')
                saved(context).forEach { append("device=").append(it.idString).append('\n') }
            }
            File(context.filesDir, MODULE_CONFIG).writeText(lines)
        } catch (_: Exception) {
            // Файл — вспомогательный. Модуль без него просто работает по-старому.
        }
    }

    const val MODULE_CONFIG = "vr_config.txt"
}
