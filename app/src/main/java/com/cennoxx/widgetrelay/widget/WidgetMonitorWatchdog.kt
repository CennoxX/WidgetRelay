package com.cennoxx.widgetrelay.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/**
 * Restarts [WidgetMonitorService] whenever it could have been lost.
 *
 * A foreground service survives most things, but not everything: the system
 * kills it under memory pressure, an app update stops it, and a reboot clears
 * it entirely. Three triggers cover those:
 *
 * - `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` broadcasts
 * - a repeating alarm as a heartbeat, in case a kill went unnoticed
 * - the event runner itself, whenever Tasker queries the condition
 */
class WidgetMonitorWatchdog : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // A boot broadcast also means the alarm is gone - set it up again
        schedule(context)
        WidgetMonitorService.ensureRunning(context)
    }

    companion object {
        const val ACTION_WATCHDOG = "com.cennoxx.widgetrelay.WATCHDOG"

        private const val INTERVAL_MS = AlarmManager.INTERVAL_FIFTEEN_MINUTES

        private fun pendingIntent(context: Context): PendingIntent {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            return PendingIntent.getBroadcast(
                context.applicationContext,
                0,
                Intent(context.applicationContext, WidgetMonitorWatchdog::class.java)
                    .setAction(ACTION_WATCHDOG),
                flags
            )
        }

        /** Inexact on purpose: this is a safety net, not the update mechanism. */
        fun schedule(context: Context) {
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            alarms.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + INTERVAL_MS,
                INTERVAL_MS,
                pendingIntent(context)
            )
        }

        fun cancel(context: Context) {
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            alarms.cancel(pendingIntent(context))
        }

        /** Used after the task was swiped away, where an immediate restart is refused. */
        fun scheduleRestart(context: Context) {
            val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            alarms.set(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 2000,
                pendingIntent(context)
            )
        }
    }
}
