package dev.chargelog.data

import android.content.Intent
import dev.chargelog.core.Volt
import java.io.File

/**
 * Looks for an open-circuit voltage (OCV) the device exposes directly. Android has no public
 * OCV API: some vendors put it in the battery broadcast, and fuel-gauge drivers expose it in
 * sysfs (generic `voltage_ocv`, Samsung `batt_vfocv`), which SELinux may or may not let apps
 * read. Every /sys/class/power_supply/<supply>/ directory is scanned once for those names.
 */
object VoltageProbe {

    data class Reading(val mv: Int, val key: String)

    private val NAMES = listOf("voltage_ocv", "batt_vfocv", "fg_vfocv", "vfocv", "ocv")
    private val FIXED = listOf(
        "/sys/class/power_supply/battery/batt_vfocv",
        "/sys/class/power_supply/battery/voltage_ocv",
        "/sys/class/power_supply/bms/voltage_ocv",
    )

    @Volatile private var workingFile: String? = null
    @Volatile private var probed = false
    @Volatile private var broadcastKey: String? = null

    /** Every candidate path: the fixed ones plus each supply directory × each known name. */
    fun candidates(): List<String> {
        val dirs = try { File("/sys/class/power_supply").listFiles()?.map { it.path }.orEmpty() } catch (e: Exception) { emptyList() }
        return (FIXED + dirs.flatMap { d -> NAMES.map { "$d/$it" } }).distinct()
    }

    /** Name of the key or file that supplied the last direct OCV. */
    fun directKey(): String? = broadcastKey?.let { "broadcast \"$it\"" } ?: workingFile

    /** Direct OCV from the broadcast or sysfs, or null when this device does not expose one. */
    fun ocv(intent: Intent?): Reading? {
        intent?.extras?.let { ex ->
            for (k in ex.keySet().sorted()) {
                if (!k.contains("ocv", ignoreCase = true)) continue
                @Suppress("DEPRECATION")
                val raw = (ex.get(k) as? Number)?.toLong() ?: continue
                Volt.toMv(raw)?.let { broadcastKey = k; return Reading(it, "broadcast \"$k\"") }
            }
        }
        workingFile?.let { f -> readFile(f)?.let { return Reading(it, f) } }
        if (!probed) {
            probed = true
            for (f in candidates()) readFile(f)?.let { workingFile = f; return Reading(it, f) }
        }
        return null
    }

    /** Every candidate with what was found, for the diagnostics screen and exports. */
    fun report(intent: Intent?): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val extras = intent?.extras
        val keys = extras?.keySet()?.filter { it.contains("ocv", ignoreCase = true) }.orEmpty()
        if (keys.isEmpty()) out.add("broadcast *ocv*" to "없음")
        @Suppress("DEPRECATION")
        for (k in keys) out.add("broadcast \"$k\"" to "${extras?.get(k)}")
        val dirs = try { File("/sys/class/power_supply").list()?.sorted()?.joinToString(", ") } catch (e: Exception) { null }
        out.add("/sys/class/power_supply" to (dirs ?: "목록 읽기 불가"))
        for (f in candidates()) {
            val file = File(f)
            if (!FIXED.contains(f) && !file.exists()) continue
            val v = try { file.readText().trim() } catch (e: Exception) { "읽기 불가 (${e.javaClass.simpleName})" }
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
