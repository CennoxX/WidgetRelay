package com.cennoxx.widgetrelay.widget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import com.cennoxx.widgetrelay.R
import com.cennoxx.widgetrelay.tasker.widgets.ActivityConfigWidgetUpdated
import com.cennoxx.widgetrelay.tasker.widgets.WidgetActionRuntime
import com.cennoxx.widgetrelay.tasker.widgets.WidgetUpdate
import com.joaomgcd.taskerpluginlibrary.condition.TaskerPluginRunnerCondition
import org.json.JSONObject

/**
 * Keeps the registered widgets permanently hosted so their updates can be
 * turned into Tasker events.
 *
 * The plugin *actions* re-create a widget for a moment and throw it away; an
 * event has no such moment - something has to stay alive and watch. This
 * service does that: every registered widget gets an invisible overlay window
 * (the same trick the actions use, see [WidgetActionRuntime]) that is never
 * taken down, so providers keep pushing updates to it and collection views
 * stay connected to their RemoteViewsService.
 *
 * Change detection is push-first: [NotifyingWidgetHostView] reports every
 * RemoteViews the provider delivers. Collection content is the exception - it
 * arrives through notifyAppWidgetViewDataChanged, which never reaches the host
 * view - so a slow safety re-capture runs on top of it.
 */
class WidgetMonitorService : Service() {

    /** Providers often push several RemoteViews in a row; capture once, after. */
    private val debounceMs = 400L

    /** Baseline capture: long enough for lists to have loaded their first rows. */
    private val baselineMs = 1500L

    /**
     * Collection updates don't reach the host view at all, so the tree is
     * re-read regularly regardless of push notifications.
     */
    private val recaptureMs = 15_000L

    /** What the monitor activity shows for one hosted widget. */
    data class Status(
        val entry: WidgetMonitorRegistry.Entry,
        val attached: Boolean,
        val elements: Int,
        val updates: Int,
        val lastChangeAt: Long,
        val error: String?
    )

    private class Monitor(
        val entry: WidgetMonitorRegistry.Entry,
        val hostView: NotifyingWidgetHostView
    ) {
        var attached = false
        var elements = 0
        var updates = 0
        var lastChangeAt = 0L
        var error: String? = null

        /** Flat path -> value map of the last capture, as the change fingerprint. */
        var lastValues: String? = null
        var lastTree: String? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val monitors = LinkedHashMap<Int, Monitor>()

    /** Stats stashed by [dropMonitor] for [attach] to carry over. */
    private val previousStats = HashMap<Int, Monitor>()
    private lateinit var windowManager: WindowManager
    private lateinit var extractor: WidgetExtractor
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        extractor = WidgetExtractor(this)
        WidgetHost.get(this).startListening()
        WidgetMonitorWatchdog.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        if (intent?.action == ACTION_REBUILD) {
            // Drop the affected monitors; sync() below builds them again. This
            // is a view rebuild (some action reclaimed the widget and gave it
            // back), not a real reconfiguration - the widget's already-known
            // content is still valid, so its stats and change baseline carry
            // over instead of every action run silently resetting them
            val id = intent.getIntExtra(EXTRA_APP_WIDGET_ID, -1)
            val targets = if (id == -1) monitors.keys.toList() else listOf(id)
            val entries = WidgetMonitorRegistry.entries(this).associateBy { it.appWidgetId }
            targets.forEach { target -> dropMonitor(target, keepStatsIfSameWidget = entries[target]) }
        }
        sync()
        // Restarted by the system if it kills us; onCreate/sync then rebuilds
        // everything from the registry
        return START_STICKY
    }

    /**
     * Swiping the app away must not stop monitoring - schedule an immediate
     * restart.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (WidgetMonitorRegistry.isEnabled(this)) {
            WidgetMonitorWatchdog.scheduleRestart(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        monitors.values.forEach { detach(it) }
        monitors.clear()
        releaseWakeLock()
        publishStatuses()
    }

    // --- Monitor lifecycle ---

    /** Brings the hosted widgets in line with the registry. */
    private fun sync() {
        if (!WidgetMonitorRegistry.isEnabled(this)) {
            stopSelf()
            return
        }
        val entries = WidgetMonitorRegistry.entries(this).associateBy { it.appWidgetId }

        monitors.keys.toList()
            .filter { id ->
                val monitor = monitors[id]
                val entry = entries[id]
                // Gone from the registry, or re-saved with a different size (a
                // different size can mean a different layout), or never came up
                // in the first place - a failed monitor must be retried, or
                // granting the overlay permission would never fix anything.
                // Deliberately *not* a full equality check - see
                // WidgetMonitorRegistry.Entry.isSameHostedWidget().
                // isSilentlyDead() makes opening this app's screen (which
                // syncs) recover a stuck widget immediately, rather than
                // waiting for the next recapture tick to notice.
                monitor == null || entry == null || !entry.isSameHostedWidget(monitor.entry) ||
                    !monitor.attached || isSilentlyDead(monitor)
            }
            .forEach { id -> dropMonitor(id, keepStatsIfSameWidget = entries[id]) }

        entries.values.forEach { entry ->
            if (!monitors.containsKey(entry.appWidgetId)) attach(entry)
        }

        applyWakeLock()
        updateNotification()
        publishStatuses()
    }

    private fun attach(entry: WidgetMonitorRegistry.Entry) {
        val carryOver = previousStats.remove(entry.appWidgetId)
            ?.takeIf { entry.isSameHostedWidget(it.entry) }

        if (!WidgetActionRuntime.hasOverlayPermission(this)) {
            monitors[entry.appWidgetId] = Monitor(entry, NotifyingWidgetHostView(this)).apply {
                error = getString(R.string.monitor_error_no_overlay)
            }
            return
        }
        val hostView = WidgetHost.get(this).createHostViewForId(this, entry.appWidgetId)
            as? NotifyingWidgetHostView
        if (hostView == null) {
            monitors[entry.appWidgetId] = Monitor(entry, NotifyingWidgetHostView(this)).apply {
                error = getString(R.string.monitor_error_not_bound)
            }
            return
        }

        val monitor = Monitor(entry, hostView)
        if (carryOver != null) {
            monitor.elements = carryOver.elements
            monitor.updates = carryOver.updates
            monitor.lastChangeAt = carryOver.lastChangeAt
            monitor.lastValues = carryOver.lastValues
            monitor.lastTree = carryOver.lastTree
        }
        monitors[entry.appWidgetId] = monitor
        try {
            WidgetActionRuntime.applySize(this, hostView, entry.spanX, entry.spanY)
            windowManager.addView(
                hostView,
                WidgetActionRuntime.invisibleOverlayParams(this, entry.spanX, entry.spanY)
            )
            monitor.attached = true
        } catch (e: Exception) {
            e.printStackTrace()
            monitor.error = e.message ?: getString(R.string.monitor_error_attach)
            return
        }

        hostView.onRemoteViewsUpdated = { scheduleCapture(monitor, debounceMs) }
        // A carried-over baseline is already known good - only a genuinely
        // fresh monitor needs the baseline-only first capture
        scheduleCapture(monitor, if (carryOver != null) debounceMs else baselineMs)
        scheduleRecapture(monitor)
    }

    /** Detaches and drops the monitor for [id], optionally preserving its stats for [attach]. */
    private fun dropMonitor(id: Int, keepStatsIfSameWidget: WidgetMonitorRegistry.Entry?) {
        val monitor = monitors.remove(id) ?: return
        if (keepStatsIfSameWidget != null && keepStatsIfSameWidget.isSameHostedWidget(monitor.entry)) {
            previousStats[id] = monitor
        }
        detach(monitor)
    }

    private fun detach(monitor: Monitor) {
        handler.removeCallbacksAndMessages(monitor)
        monitor.hostView.onRemoteViewsUpdated = null
        if (monitor.attached) {
            try {
                windowManager.removeViewImmediate(monitor.hostView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            monitor.attached = false
        }
    }

    // --- Capturing ---

    private fun scheduleCapture(monitor: Monitor, delayMs: Long) {
        handler.removeCallbacksAndMessages(monitor)
        handler.postAtTime({ capture(monitor) }, monitor, SystemClock.uptimeMillis() + delayMs)
    }

    private fun scheduleRecapture(monitor: Monitor) {
        handler.postDelayed({
            if (monitors[monitor.entry.appWidgetId] === monitor) {
                // healSilentlyDeadMonitor() re-attaches, which starts a fresh
                // chain - this one must not also continue, or the widget would
                // end up with two
                if (!healSilentlyDeadMonitor(monitor)) {
                    capture(monitor)
                    scheduleRecapture(monitor)
                }
            }
        }, recaptureMs)
    }

    /**
     * Detects a monitor that looks healthy but can no longer receive anything,
     * and re-creates it. Returns true if it healed (and thus replaced [monitor]).
     *
     * Two ways a monitor dies silently:
     *
     * - **Its view was displaced.** An AppWidgetHost delivers updates to one
     *   view per widget, so every config preview and every action run takes the
     *   id over. Both hand it back when they finish, but that handshake can be
     *   missed - a background service start refused under Android 12+ limits, a
     *   config activity killed before onDestroy, a crash in between. The orphan
     *   view stays in its window and keeps rendering its last state forever.
     * - **Its window went away** without us removing it.
     *
     * Neither shows up in [Monitor.attached], which only records that
     * addView() once succeeded - so without this check the event simply stops
     * firing for good, while the notification and this app's own screen keep
     * reporting the widget as watched.
     */
    private fun healSilentlyDeadMonitor(monitor: Monitor): Boolean {
        if (!isSilentlyDead(monitor)) return false
        val entry = monitor.entry
        dropMonitor(entry.appWidgetId, keepStatsIfSameWidget = entry)
        attach(entry)
        publishStatuses()
        return true
    }

    /** See [healSilentlyDeadMonitor] for what "silently dead" means here. */
    private fun isSilentlyDead(monitor: Monitor): Boolean {
        if (!monitor.attached) return false
        val displaced = !WidgetHost.get(this)
            .isCurrentViewForId(monitor.entry.appWidgetId, monitor.hostView)
        return displaced || !monitor.hostView.isAttachedToWindow
    }

    private fun capture(monitor: Monitor) {
        if (!monitor.attached) return
        val nodes = try {
            extractor.extractFromRemoteViews(monitor.hostView)
        } catch (e: Exception) {
            e.printStackTrace()
            monitor.error = e.message
            publishStatuses()
            return
        }
        // Nothing rendered yet - keep the previous baseline instead of
        // reporting an empty widget as a change
        if (nodes.isEmpty()) return

        val values = nodes.toValueMap().toString()
        val previous = monitor.lastValues
        monitor.elements = nodes.size
        monitor.error = null
        monitor.lastValues = values
        monitor.lastTree = nodes.toJsonTree()?.toString()

        if (previous == null || previous == values) {
            publishStatuses()
            return
        }

        monitor.updates++
        monitor.lastChangeAt = System.currentTimeMillis()
        publishStatuses()
        fireEvent(monitor, previous, values)
    }

    /**
     * Asks Tasker to re-query the "Widget Updated" event. Tasker then runs
     * every enabled instance of it, each deciding for itself whether this
     * widget - and, if configured, this one element - is the one it watches.
     */
    private fun fireEvent(monitor: Monitor, previousValues: String, values: String) {
        try {
            TaskerPluginRunnerCondition.requestQuery(
                this,
                ActivityConfigWidgetUpdated::class.java,
                WidgetUpdate(
                    appWidgetId = monitor.entry.appWidgetId,
                    json = monitor.lastTree?.takeIf { it.length <= MAX_PASSTHROUGH_CHARS },
                    values = values,
                    previousValues = previousValues
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- Staying alive ---

    private fun applyWakeLock() {
        if (WidgetMonitorRegistry.usesWakeLock(this) && monitors.isNotEmpty()) {
            if (wakeLock?.isHeld == true) return
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        } else {
            releaseWakeLock()
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        wakeLock = null
    }

    private fun startForegroundWithNotification() {
        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.monitor_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.monitor_channel_description)
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ActivityWidgetMonitor::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags
        )
        val active = monitors.values.count { it.attached }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(getString(R.string.monitor_notification_title))
            .setContentText(resources.getQuantityString(R.plurals.monitor_notification_text, active, active))
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) createChannel()
        val manager = getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- Status for the monitor activity ---

    private fun publishStatuses() {
        statuses = monitors.values.map {
            Status(
                entry = it.entry,
                attached = it.attached,
                elements = it.elements,
                updates = it.updates,
                lastChangeAt = it.lastChangeAt,
                error = it.error
            )
        }
        onStatusesChanged?.invoke()
    }

    companion object {
        private const val CHANNEL_ID = "widget_monitor"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "WidgetRelay:monitor"

        /** Bundles crossing to Tasker are limited; drop oversized trees. */
        private const val MAX_PASSTHROUGH_CHARS = 200_000

        const val ACTION_SYNC = "com.cennoxx.widgetrelay.SYNC_MONITORS"
        private const val ACTION_REBUILD = "com.cennoxx.widgetrelay.REBUILD_MONITOR"
        private const val EXTRA_APP_WIDGET_ID = "appWidgetId"

        @Volatile
        var statuses: List<Status> = emptyList()
            private set

        /** Set by the monitor activity while it is visible. */
        @Volatile
        var onStatusesChanged: (() -> Unit)? = null

        /**
         * Starts (or re-syncs) monitoring if anything is registered. Called
         * from every path that could have lost the service: boot, app update,
         * the watchdog alarm and the monitor activity. The event runner calls
         * it too, but that one cannot be relied on - see
         * [com.cennoxx.widgetrelay.tasker.widgets.WidgetUpdatedRunner].
         */
        fun ensureRunning(context: Context) = start(context, ACTION_SYNC, -1)

        /**
         * Re-creates the hosted view for [appWidgetId] (-1 for all of them).
         *
         * An [android.appwidget.AppWidgetHost] keeps exactly one view per
         * appWidgetId: creating a second one for the same id - which every
         * plugin action and every config preview does - silently replaces the
         * monitor's view in that map, and the replaced view stops receiving
         * updates for good. So anything that creates a view for a monitored
         * widget has to hand it back afterwards.
         */
        fun rebuild(context: Context, appWidgetId: Int) {
            val app = context.applicationContext
            val monitored = WidgetMonitorRegistry.entries(app)
                .any { appWidgetId == -1 || it.appWidgetId == appWidgetId }
            if (!monitored) return
            start(app, ACTION_REBUILD, appWidgetId)
        }

        private fun start(context: Context, action: String, appWidgetId: Int) {
            val app = context.applicationContext
            if (!WidgetMonitorRegistry.isEnabled(app)) return
            if (WidgetMonitorRegistry.entries(app).isEmpty()) return
            val intent = Intent(app, WidgetMonitorService::class.java)
                .setAction(action)
                .putExtra(EXTRA_APP_WIDGET_ID, appWidgetId)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(intent)
                } else {
                    app.startService(intent)
                }
            } catch (e: Exception) {
                // Background start restrictions can refuse this; the watchdog
                // alarm and the next condition query try again
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            app.stopService(Intent(app, WidgetMonitorService::class.java))
            statuses = emptyList()
        }
    }
}
