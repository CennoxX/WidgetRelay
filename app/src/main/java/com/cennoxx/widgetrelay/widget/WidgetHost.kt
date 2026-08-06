package com.cennoxx.widgetrelay.widget

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * An [AppWidgetHostView] that reports every RemoteViews the provider pushes.
 *
 * There is no "widget changed" callback in the AppWidget APIs - the only
 * moment a host learns about an update is when the system hands it new
 * RemoteViews to render. Overriding [updateAppWidget] turns that into the
 * push signal [WidgetMonitorService] listens on.
 */
class NotifyingWidgetHostView(context: Context) : AppWidgetHostView(context) {
    var onRemoteViewsUpdated: (() -> Unit)? = null

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        super.updateAppWidget(remoteViews)
        onRemoteViewsUpdated?.invoke()
    }
}

/**
 * App-wide singleton around [AppWidgetHost]. Widgets are bound by a persistent
 * appWidgetId (stored in the Tasker configuration) so they can be recreated and
 * read whenever the plugin action fires.
 */
class WidgetHost private constructor(private val context: Context) {
    private val appWidgetManager = AppWidgetManager.getInstance(context)
    private val appWidgetHost = object : AppWidgetHost(context, WIDGET_HOST_ID) {
        override fun onCreateView(
            context: Context,
            appWidgetId: Int,
            appWidget: AppWidgetProviderInfo?
        ): AppWidgetHostView = NotifyingWidgetHostView(context)
    }

    fun startListening() {
        try {
            appWidgetHost.startListening()
        } catch (e: Exception) {
            // Host may already be listening
        }
    }

    fun allocateId(): Int = appWidgetHost.allocateAppWidgetId()

    fun bindId(appWidgetId: Int, providerInfo: AppWidgetProviderInfo): Boolean {
        return try {
            appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, providerInfo.provider)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getBindIntentForId(appWidgetId: Int, providerInfo: AppWidgetProviderInfo): Intent {
        return Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, providerInfo.provider)
        }
    }

    fun deleteId(appWidgetId: Int) {
        try {
            appWidgetHost.deleteAppWidgetId(appWidgetId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        synchronized(currentViews) { currentViews.remove(appWidgetId) }
    }

    /** Provider info of a bound id, or null if the id is not (or no longer) bound. */
    fun getProviderInfoForId(appWidgetId: Int): AppWidgetProviderInfo? {
        return try {
            appWidgetManager.getAppWidgetInfo(appWidgetId)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The most recently created view per appWidgetId.
     *
     * [AppWidgetHost] keeps exactly one view per id and delivers updates only
     * to that one, so creating a second view for the same widget silently cuts
     * the first one off - it keeps rendering its last state forever with no
     * error anywhere. Tracking the current view here lets a long-lived holder
     * (the monitor) detect that it was displaced instead of assuming it still
     * owns the id. Weak, so a view kept here can still be collected.
     */
    private val currentViews = HashMap<Int, java.lang.ref.WeakReference<AppWidgetHostView>>()

    fun createHostViewForId(activityContext: Context, appWidgetId: Int): AppWidgetHostView? {
        val providerInfo = getProviderInfoForId(appWidgetId) ?: return null
        return try {
            appWidgetHost.createView(activityContext, appWidgetId, providerInfo).also { view ->
                synchronized(currentViews) {
                    currentViews[appWidgetId] = java.lang.ref.WeakReference(view)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Whether [view] is still the view this host routes updates for
     * [appWidgetId] to, i.e. nothing else has created a view for that widget
     * since. False means [view] is an orphan and has to be re-created.
     */
    fun isCurrentViewForId(appWidgetId: Int, view: AppWidgetHostView): Boolean =
        synchronized(currentViews) { currentViews[appWidgetId]?.get() === view }

    /**
     * Launches the widget's configuration activity. Uses the AppWidgetHost API
     * so it also works for config activities that are not exported.
     * The result arrives in the activity's onActivityResult with [requestCode].
     */
    fun startConfigureForId(activity: Activity, appWidgetId: Int, requestCode: Int): Boolean {
        getProviderInfoForId(appWidgetId)?.configure ?: return false

        return try {
            appWidgetHost.startAppWidgetConfigureActivityForResult(
                activity, appWidgetId, 0, requestCode, null
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getAvailableWidgetProviders(): List<AppWidgetProviderInfo> {
        return try {
            val providers = appWidgetManager.installedProviders
            providers?.sortedBy { "${it.provider.packageName}/${it.provider.className}" }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val WIDGET_HOST_ID = 1

        @Volatile
        private var instance: WidgetHost? = null

        fun get(context: Context): WidgetHost {
            return instance ?: synchronized(this) {
                instance ?: WidgetHost(context.applicationContext).also { instance = it }
            }
        }
    }
}
