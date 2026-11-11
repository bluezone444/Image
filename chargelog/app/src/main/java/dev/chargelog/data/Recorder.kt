package dev.chargelog.data

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import dev.chargelog.core.Cadence
import dev.chargelog.core.Ocv
import dev.chargelog.core.RFit
import dev.chargelog.core.Sample
import dev.chargelog.core.Tracker
import java.util.Locale

/** Single entry point that turns ACTION_BATTERY_CHANGED intents into stored history. */
object Recorder {

    private var rfit: RFit? = null
    private var lastSavedCount = 0

    /** Internal-resistance fit, restored from preferences on first use. */
    fun rFit(context: Context): RFit = rfit ?: synchronized(this) {
        rfit ?: RFit(
            prefs(context).getString(R_KEY, "").orEmpty().split(',').mapNotNull { it.toDoubleOrNull() }
        ).also { rfit = it; lastSavedCount = it.count }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("data", Context.MODE_PRIVATE)

    /** Everything the system reports for one battery broadcast, plus the derived OCV. */
    fun parse(context: Context, intent: Intent, now: Long = System.currentTimeMillis()): Sample? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val current = Ocv.toMicroAmps(bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
        val counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            .takeIf { it > 0 && it != Int.MAX_VALUE } ?: 0
        val soc = Math.round(level * 100f / scale)

        val fit = rFit(context)
        fit.add(now, soc, plugged == 0, voltage, current)
        val direct = VoltageProbe.ocv(intent)
        val (ocv, src) = when {
            direct != null -> direct.mv to Ocv.SRC_DIRECT
            else -> Ocv.estimate(voltage, current, status == BatteryManager.BATTERY_STATUS_CHARGING, fit.ohms)
                ?: (0 to Ocv.SRC_NONE)
        }
        return Sample(
            ts = now,
            level = soc,
            plugged = plugged,
            status = status,
            temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
            voltage = voltage,
            ocv = ocv,
            currentUa = current,
            chargeUah = counter,
            health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, 0),
            maxChargeUa = intent.getIntExtra("max_charging_current", 0).coerceAtLeast(0),
            maxChargeUv = intent.getIntExtra("max_charging_voltage", 0).coerceAtLeast(0),
            cycles = intent.getIntExtra("android.os.extra.CYCLE_COUNT", -1),
            ocvSrc = src,
        )
    }

    /** Human-readable source of a reading's reference voltage, naming the key or method. */
    fun describeVoltage(context: Context, s: Sample?): String {
        if (s == null) return "-"
        return when (s.ocvSrc) {
            Ocv.SRC_DIRECT -> "OCV · ${VoltageProbe.directKey() ?: "fuel gauge"}"
            Ocv.SRC_REST -> "OCV 근사 · 휴지 전압 (|I| ${Math.abs(s.currentUa) / 1000}mA < 50mA)"
            Ocv.SRC_IR -> {
                val fit = rFit(context)
                val r = String.format(Locale.US, "%.3fΩ", fit.ohms)
                "OCV 추정 · V+I·R (R=$r" + (if (fit.count >= RFit.MIN_POINTS) ", 측정 ${fit.count}회)" else ", 기본값)")
            }
            else -> "부하 전압 · EXTRA_VOLTAGE \"voltage\""
        }
    }

    /** One-time replay of the stored history so earlier gaps get estimates as well. */
    fun rebuildOnce(context: Context) {
        val prefs = prefs(context)
        if (prefs.getBoolean(REBUILT_KEY, false)) return
        synchronized(this) {
            if (prefs.getBoolean(REBUILT_KEY, false)) return
            Db.get(context).rebuildWithEstimates()
            prefs.edit().putBoolean(REBUILT_KEY, true).apply()
        }
    }

    private const val REBUILT_KEY = "rebuilt_estimates_v1"
    private const val R_KEY = "r_fit_ohms"

    /** Stored reading interval chosen in the Data tab. */
    fun cadence(context: Context): Cadence = Cadence.of(prefs(context).getString(CADENCE_KEY, null))

    fun setCadence(context: Context, c: Cadence) {
        prefs(context).edit().putString(CADENCE_KEY, c.name).apply()
    }

    private const val CADENCE_KEY = "cadence"

    /**
     * @param periodic a scheduled reading, stored even when nothing changed
     * @return the parsed reading, or null if the intent carried no usable level.
     */
    @Synchronized
    fun record(context: Context, intent: Intent, periodic: Boolean = false): Sample? {
        val s = parse(context, intent) ?: return null
        Tracker(Db.get(context)).onReading(s, periodic)
        val fit = rFit(context)
        if (fit.count != lastSavedCount) {
            lastSavedCount = fit.count
            prefs(context).edit().putString(R_KEY, fit.samples.joinToString(",") {
                String.format(Locale.US, "%.4f", it)
            }).apply()
        }
        return s
    }
}
