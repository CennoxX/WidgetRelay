package com.cennoxx.widgetrelay.widget

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent

/**
 * App-wide singleton around [AppWidgetHost]. Widgets are bound by a persistent
 * appWidgetId (stored in the Tasker configuration) so they can be recreated and
 * read whenever the plugin action fires.
 */
class WidgetHost private constructor(private val context: Context) {
    private val appWidgetManager = AppWidgetManager.getInstance(context)
    private val appWidgetHost = AppWidgetHost(context, WIDGET_HOST_ID)

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
    }

    /** Provider info of a bound id, or null if the id is not (or no longer) bound. */
    fun getProviderInfoForId(appWidgetId: Int): AppWidgetProviderInfo? {
        return try {
            appWidgetManager.getAppWidgetInfo(appWidgetId)
        } catch (e: Exception) {
            null
        }
    }

    fun createHostViewForId(activityContext: Context, appWidgetId: Int): AppWidgetHostView? {
        val providerInfo = getProviderInfoForId(appWidgetId) ?: return null
        return try {
            appWidgetHost.createView(activityContext, appWidgetId, providerInfo)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

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
