package dev.chargelog.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.chargelog.R
import dev.chargelog.core.Format
import dev.chargelog.core.Plug
import dev.chargelog.core.Sample
import dev.chargelog.core.SessionType
import dev.chargelog.data.Db
import dev.chargelog.data.Recorder
import dev.chargelog.ui.MainActivity
import java.time.ZoneId

/**
 * Keeps a runtime receiver for ACTION_BATTERY_CHANGED alive. That broadcast cannot be
 * declared in the manifest, so a foreground service is the only way to see every SOC step
 * and charger connect/disconnect while the app is not on screen.
 */
class MonitorService : Service() {

    private var lastText: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = handle(intent)
    }

    override fun onCreate() {
        super.onCreate()
        val nm = (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "배터리 기록", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            }
        )
        goForeground()
        // Sticky broadcast: returns the current battery state immediately.
        registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { handle(it) }
        Watchdog.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() call must be answered with startForeground(),
        // including calls that reach an already running service.
        goForeground()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away from recents must not end recording.
        Watchdog.schedule(this)
        super.onTaskRemoved(rootIntent)
    }

    private fun goForeground() {
        val n = build(lastText ?: "배터리 기록 중")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handle(intent: Intent) {
        val s = Recorder.record(this, intent) ?: return
        val text = describe(s)
        if (text != lastText) {
            lastText = text
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, build(text))
        }
    }

    private fun describe(s: Sample): String {
        val zone = ZoneId.systemDefault()
        val open = Db.get(this).latestSessions(1).firstOrNull()
        val head = "${s.level}% · " + if (s.charging) "충전 중(${Plug.labelKo(s.plugged)})" else "사용 중"
        if (open == null) return head
        val since = Format.time(open.startTs, zone)
        return if (open.type == SessionType.CHARGE) {
            "$head · $since ${open.startSoc}%에서 시작"
        } else {
            "$head · $since ${open.startSoc}%부터 ${Format.signed(open.delta)}%"
        }
    }

    private fun build(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        private const val CHANNEL = "monitor"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        }
    }
}
