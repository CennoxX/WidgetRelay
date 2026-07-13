package com.cennoxx.widgetrelay.widget

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class WidgetHost private constructor(private val context: Context) {
    private val appWidgetManager = AppWidgetManager.getInstance(context)
    private val appWidgetHost = AppWidgetHost(context, WIDGET_HOST_ID)
    private var currentAppWidgetId: Int? = null
    private var currentProviderInfo: AppWidgetProviderInfo? = null
    private var currentHostView: AppWidgetHostView? = null

    private val _widgetNodes = MutableStateFlow<List<WidgetNode>>(emptyList())
    val widgetNodes = _widgetNodes.asStateFlow()

    fun startListening() {
        try {
            appWidgetHost.startListening()
        } catch (e: Exception) {
            // Host may already be listening
        }
    }

    /**
     * Allocates an ID and tries to bind the provider. Returns true if bound;
     * false if the user first has to grant permission via [getBindIntent].
     */
    fun bindWidget(providerInfo: AppWidgetProviderInfo): Boolean {
        return try {
            // Release any previously bound widget before binding a new one
            unbindWidget()

            val appWidgetId = appWidgetHost.allocateAppWidgetId()
            currentAppWidgetId = appWidgetId
            currentProviderInfo = providerInfo

            appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, providerInfo.provider)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getBindIntent(): Intent {
        return Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, currentAppWidgetId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, currentProviderInfo?.provider)
        }
    }

    fun getCurrentProviderInfo(): AppWidgetProviderInfo? = currentProviderInfo

    /** True if the widget declares a configuration activity that must run before first use. */
    fun needsConfiguration(): Boolean = currentProviderInfo?.configure != null

    /**
     * Launches the widget's configuration activity. Uses the AppWidgetHost API
     * so it also works for config activities that are not exported.
     * The result arrives in the activity's onActivityResult with [requestCode].
     */
    fun startConfigureActivity(activity: Activity, requestCode: Int): Boolean {
        val appWidgetId = currentAppWidgetId ?: return false
        val configure = currentProviderInfo?.configure ?: return false

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                appWidgetHost.startAppWidgetConfigureActivityForResult(
                    activity, appWidgetId, 0, requestCode, null
                )
            } else {
                val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                    component = configure
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                activity.startActivityForResult(intent, requestCode)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Creates the host view for the currently bound widget using the given
     * (activity) context so it can be shown on any page.
     */
    fun createHostView(activityContext: Context): AppWidgetHostView? {
        val appWidgetId = currentAppWidgetId ?: return null
        val providerInfo = currentProviderInfo ?: return null

        return try {
            val hostView = appWidgetHost.createView(activityContext, appWidgetId, providerInfo)
            currentHostView = hostView
            hostView
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun unbindWidget() {
        currentAppWidgetId?.let { appWidgetId ->
            try {
                appWidgetHost.deleteAppWidgetId(appWidgetId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        currentAppWidgetId = null
        currentProviderInfo = null
        currentHostView = null
        _widgetNodes.value = emptyList()
    }

    fun refreshExtractedData() {
        currentHostView?.let { hostView ->
            val extractor = WidgetExtractor(context)
            val nodes = extractor.extractFromRemoteViews(hostView)
            _widgetNodes.value = nodes
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
