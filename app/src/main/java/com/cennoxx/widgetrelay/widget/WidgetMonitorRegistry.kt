package com.cennoxx.widgetrelay.widget

import android.content.ComponentName
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The set of widgets [WidgetMonitorService] keeps alive, persisted in
 * SharedPreferences.
 *
 * Tasker never tells a plugin which of its events are currently in use, so the
 * registry is filled from the other side: configuring a "Widget Updated" event
 * writes its widget here, and the monitor service hosts everything it finds.
 * Entries are keyed by appWidgetId, so two events on the same widget share one
 * hosted instance.
 */
object WidgetMonitorRegistry {
    private const val PREFS = "widget_monitor"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_WAKE_LOCK = "wake_lock"

    data class Entry(
        val appWidgetId: Int,
        val provider: String?,
        val appName: String?,
        val widgetLabel: String?,
        val spanX: Int,
        val spanY: Int
    ) {
        val providerComponent: ComponentName?
            get() = provider?.let { ComponentName.unflattenFromString(it) }

        /**
         * Whether [other] describes the same *hosted* widget - same id and
         * size, which is what actually changes what gets attached. [appName]
         * and [widgetLabel] are display-only and deliberately excluded: they
         * are round-tripped through Tasker's storage on every single query
         * (including the one Tasker sends back in response to our own fired
         * event), and any drift there - e.g. a null coming back as "" - must
         * never be treated as "this widget needs to be re-hosted". Doing so
         * would drop [WidgetMonitorService]'s change baseline on every fire,
         * so the event would only ever catch every second change.
         */
        fun isSameHostedWidget(other: Entry) =
            appWidgetId == other.appWidgetId && spanX == other.spanX && spanY == other.spanY

        fun toJson(): JSONObject = JSONObject().apply {
            put("appWidgetId", appWidgetId)
            putOpt("provider", provider)
            putOpt("appName", appName)
            putOpt("widgetLabel", widgetLabel)
            put("spanX", spanX)
            put("spanY", spanY)
        }

        companion object {
            fun fromJson(json: JSONObject) = Entry(
                appWidgetId = json.optInt("appWidgetId", -1),
                provider = json.optString("provider", null),
                appName = json.optString("appName", null),
                widgetLabel = json.optString("widgetLabel", null),
                spanX = json.optInt("spanX", 2),
                spanY = json.optInt("spanY", 2)
            )
        }
    }

    /** Shared application preferences, also used by premium and Tasker integration state. */
    internal fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun prefs(context: Context) = preferences(context)

    fun entries(context: Context): List<Entry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length())
                .mapNotNull { array.optJSONObject(it) }
                .map { Entry.fromJson(it) }
                .filter { it.appWidgetId != -1 }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun store(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    /** Adds the widget, or replaces an existing entry with the same id. */
    fun upsert(context: Context, entry: Entry) {
        store(context, entries(context).filter { it.appWidgetId != entry.appWidgetId } + entry)
    }

    fun remove(context: Context, appWidgetId: Int) {
        store(context, entries(context).filter { it.appWidgetId != appWidgetId })
    }

    /** Master switch, so monitoring can be paused without losing the list. */
    fun isEnabled(context: Context) = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Opt-in partial wake lock. Without it the monitor sleeps along with the
     * CPU in Doze, so updates are only noticed when the device wakes up on its
     * own; with it, updates are caught immediately at a real battery cost.
     */
    fun usesWakeLock(context: Context) = prefs(context).getBoolean(KEY_WAKE_LOCK, false)

    fun setUsesWakeLock(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WAKE_LOCK, enabled).apply()
    }
}
