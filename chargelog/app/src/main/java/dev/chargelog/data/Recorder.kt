package dev.chargelog.data

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import dev.chargelog.core.Sample
import dev.chargelog.core.Tracker

/** Single entry point that turns ACTION_BATTERY_CHANGED intents into stored history. */
object Recorder {

    fun parse(intent: Intent, now: Long = System.currentTimeMillis()): Sample? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        return Sample(
            ts = now,
            level = Math.round(level * 100f / scale),
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
            temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
            voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
            ocv = VoltageProbe.ocv(intent)?.mv ?: 0,
        )
    }

    /** One-time replay of the stored history so earlier gaps get estimates as well. */
    fun rebuildOnce(context: Context) {
        val prefs = context.getSharedPreferences("data", Context.MODE_PRIVATE)
        if (prefs.getBoolean(REBUILT_KEY, false)) return
        synchronized(this) {
            if (prefs.getBoolean(REBUILT_KEY, false)) return
            Db.get(context).rebuildWithEstimates()
            prefs.edit().putBoolean(REBUILT_KEY, true).apply()
        }
    }

    private const val REBUILT_KEY = "rebuilt_estimates_v1"

    /** @return the parsed reading, or null if the intent carried no usable level. */
    @Synchronized
    fun record(context: Context, intent: Intent): Sample? {
        val s = parse(intent) ?: return null
        Tracker(Db.get(context)).onReading(s)
        return s
    }
}
