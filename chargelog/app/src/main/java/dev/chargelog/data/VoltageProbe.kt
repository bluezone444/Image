package dev.chargelog.data

import android.content.Intent
import android.os.BatteryManager
import dev.chargelog.core.Volt
import java.io.File

/**
 * Finds an open-circuit voltage (OCV) source on this device. Android has no public OCV API:
 * some vendors add an extra to the battery broadcast, and some kernels expose it in sysfs,
 * which may or may not be readable by apps. When nothing is found, the terminal voltage from
 * the broadcast key "voltage" (EXTRA_VOLTAGE) is the reference.
 */
object VoltageProbe {

    data class Reading(val mv: Int, val key: String, val isOcv: Boolean)

    /** sysfs files known to carry OCV on common fuel gauges (Samsung, Qualcomm BMS, generic). */
    val SYSFS = listOf(
        "/sys/class/power_supply/battery/voltage_ocv",
        "/sys/class/power_supply/battery/batt_vfocv",
        "/sys/class/power_supply/bms/voltage_ocv",
        "/sys/class/power_supply/max77705-fuelgauge/voltage_ocv",
        "/sys/class/power_supply/sec-fuelgauge/voltage_ocv",
    )

    @Volatile private var workingFile: String? = null
    @Volatile private var filesProbed = false

    /** OCV from the broadcast or sysfs, or null when this device does not expose one. */
    fun ocv(intent: Intent?): Reading? {
        intent?.extras?.let { ex ->
            for (k in ex.keySet().sorted()) {
                if (!k.contains("ocv", ignoreCase = true)) continue
                @Suppress("DEPRECATION")
                val raw = (ex.get(k) as? Number)?.toLong() ?: continue
                Volt.toMv(raw)?.let { return Reading(it, "broadcast \"$k\"", true) }
            }
        }
        val known = workingFile
        if (known != null) readFile(known)?.let { return Reading(it, known.substringAfterLast('/'), true) }
        if (!filesProbed) {
            filesProbed = true
            for (f in SYSFS) readFile(f)?.let { workingFile = f; return Reading(it, f.substringAfterLast('/'), true) }
        }
        return null
    }

    /** The value shown as "전압(참조값)": OCV when available, otherwise the terminal voltage. */
    fun reference(intent: Intent?): Reading? = ocv(intent) ?: intent
        ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.takeIf { it > 0 }
        ?.let { Reading(it, "EXTRA_VOLTAGE \"voltage\"", false) }

    /** Every candidate with what was found, for the diagnostics screen. */
    fun report(intent: Intent?): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val extras = intent?.extras
        val ocvKeys = extras?.keySet()?.filter { it.contains("ocv", ignoreCase = true) }.orEmpty()
        if (ocvKeys.isEmpty()) out.add("broadcast *ocv*" to "없음")
        @Suppress("DEPRECATION")
        for (k in ocvKeys) out.add("broadcast \"$k\"" to "${extras?.get(k)}")
        for (f in SYSFS) {
            val v = try { File(f).readText().trim() } catch (e: Exception) { "읽기 불가 (${e.javaClass.simpleName})" }
            out.add(f to v)
        }
        return out
    }

    private fun readFile(path: String): Int? = try {
        File(path).readText().trim().toLongOrNull()?.let { Volt.toMv(it) }
    } catch (e: Exception) {
        null // missing, or blocked by SELinux for apps
    }
}
