package com.cennoxx.widgetrelay.tasker.widgets

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.cennoxx.widgetrelay.R
import com.cennoxx.widgetrelay.widget.ActivityWidgetMonitor

/**
 * Tells the user when a plugin action ran but its widget binding was lost
 * (app reinstalled with a different signature, widget's app uninstalled and
 * reinstalled, etc.) - see [ActivityConfigWidgetActionBase.rebindLostWidget]
 * for the same recovery on the configuration side.
 *
 * A runner can detect *that* the binding is gone, but not repair it: rebinding
 * needs the config activity's UI (a size, and either the click walk-up or the
 * element list has to be re-derived), and that only takes effect once Tasker
 * itself saves the edited action - a runner has no way to write back into
 * Tasker's task storage. So this can only point the user at the fix, not
 * perform it: it notifies once per widget and, since there is no API to jump
 * straight to editing one specific action, opens Tasker itself so the next
 * step is "open the action, tap Save" rather than "figure out what broke".
 */
object WidgetRebindNotifier {
    private const val CHANNEL_ID = "widget_rebind"

    /** Tasker's package across its distributions - see TaskerIntent.TASKER_PACKAGE*. */
    private val TASKER_PACKAGES = listOf(
        "net.dinglisch.android.taskerm",
        "net.dinglisch.android.tasker"
    )

    fun notifyNotBound(context: Context, appWidgetId: Int, widgetLabel: String?, appName: String?) {
        if (appWidgetId == -1) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        createChannel(context)
        val name = widgetLabel ?: context.getString(R.string.notification_rebind_fallback_name)
        val notification = Notification.Builder(context, CHANNEL_ID).apply {
            setSmallIcon(android.R.drawable.stat_notify_error)
            setContentTitle(context.getString(R.string.notification_rebind_title))
            setContentText(context.getString(R.string.notification_rebind_text, name, appName ?: ""))
            style = Notification.BigTextStyle().bigText(
                context.getString(R.string.notification_rebind_text, name, appName ?: "")
            )
            setContentIntent(reopenIntent(context))
            setAutoCancel(true)
        }.build()

        try {
            // One notification per widget: a repeated failure (e.g. every run
            // of a task on a schedule) replaces it instead of piling up
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIFICATION_ID_BASE + appWidgetId, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_rebind_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_rebind_channel_description)
            }
        )
    }

    /**
     * Tasker has no API to open one specific action's edit screen, so the best
     * available next step is Tasker itself; if it isn't installed (or its
     * package changed again) this falls back to WidgetRelay's own front page,
     * which at least explains what to do.
     */
    private fun reopenIntent(context: Context): PendingIntent {
        val target = TASKER_PACKAGES
            .mapNotNull { context.packageManager.getLaunchIntentForPackage(it) }
            .firstOrNull()
            ?: Intent(context, ActivityWidgetMonitor::class.java)
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(context, 0, target, flags)
    }

    private const val NOTIFICATION_ID_BASE = 100_000
}
