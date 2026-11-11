package dev.chargelog.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import dev.chargelog.data.Recorder

/**
 * Screen-off ticker and backstop. Every [dev.chargelog.core.Cadence.sleepMs] (5 min by default),
 * also during Doze, store a reading and make sure the recorder service is running. An exact
 * allow-while-idle alarm keeps the interval regular; in deep Doze the system may still stretch
 * it. Each wake-up only reads the battery and writes one row.
 */
object Watchdog {

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(context, WatchdogReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val at = SystemClock.elapsedRealtime() + Recorder.cadence(context).sleepMs
        // Same PendingIntent replaces any pending alarm, so calling this often is harmless.
        val exactAllowed = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exactAllowed) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // Null receiver: just reads the current sticky battery state.
        app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let { Recorder.record(app, it, periodic = true) }
        try {
            MonitorService.start(app)
        } catch (e: RuntimeException) {
            // Background start refused (app not exempt); the reading above still fills the gap.
        }
        Watchdog.schedule(app)
    }
}
