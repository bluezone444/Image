package dev.chargelog.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import dev.chargelog.data.Recorder

/**
 * Backstop for when the system or a vendor battery manager kills the recorder: every ~15
 * minutes (also during Doze) record the current battery state directly and restart the
 * foreground service. Starting it from the background is allowed while the app is exempt
 * from battery optimization.
 */
object Watchdog {
    private const val INTERVAL_MS = 15 * 60_000L

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, WatchdogReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Same PendingIntent replaces any pending alarm, so calling this often is harmless.
        am.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + INTERVAL_MS, pi
        )
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // Null receiver: just reads the current sticky battery state.
        app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let { Recorder.record(app, it) }
        try {
            MonitorService.start(app)
        } catch (e: RuntimeException) {
            // Background start refused (app not exempt); the reading above still fills the gap.
        }
        Watchdog.schedule(app)
    }
}
