package dev.chargelog.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts recording after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Watchdog.schedule(context)
                try {
                    MonitorService.start(context)
                } catch (e: RuntimeException) {
                    // Background start refused by the OS; the watchdog keeps retrying.
                }
            }
        }
    }
}
