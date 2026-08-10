package com.cennoxx.widgetrelay.premium

import android.content.Context
import com.cennoxx.widgetrelay.widget.WidgetMonitorRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Records Tasker configurations after they are saved so the app can keep track
 * of which saved configurations exist and which free-tier config is active.
 */
object TaskerConfigurationRegistry {
    enum class Type { WIDGET_UPDATED, GET_WIDGET_DATA, CLICK_WIDGET }

    private const val KEY_ENTRIES = "tasker_configurations"
    private const val KEY_MIGRATED_EVENTS = "tasker_configurations_migrated_events"
    private const val KEY_ACTIVE_FREEMIUM_APP_WIDGET_ID = "tasker_active_freemium_app_widget_id"

    data class Entry(val configKey: String, val type: Type) {
        fun toJson() = JSONObject().apply {
            put("key", configKey)
            put("type", type.name)
        }
    }

    fun entries(context: Context): List<Entry> {
        migrateLegacyEvents(context)
        val raw = WidgetMonitorRegistry.preferences(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { json ->
                    val configKey = json.optString("key")
                    val type = runCatching { Type.valueOf(json.optString("type")) }.getOrNull()
                    if (configKey.isBlank() || type == null) null else Entry(configKey, type)
                }
            }.distinctBy { it.configKey }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun count(context: Context) = entries(context).size

    fun record(context: Context, configKey: String, type: Type) {
        val current = entries(context).filterNot { it.configKey == configKey }
        store(context, current + Entry(configKey, type))
    }

    /**
     * Removes all [WidgetMonitorRegistry] entries whose appWidgetId is NOT the
     * currently active freemium widget. Called after saving a new free-tier
     * configuration so the monitor stops hosting widgets that no active event
     * cares about.
     *
     * Does nothing on premium (pass [isPremium] = true).
     */
    fun pruneInactiveFreemonitoredWidgets(context: Context, isPremium: Boolean) {
        if (isPremium) return
        val activeWidgetId = WidgetMonitorRegistry.preferences(context)
            .getInt(KEY_ACTIVE_FREEMIUM_APP_WIDGET_ID, -1)
        if (activeWidgetId == -1) return
        val toRemove = WidgetMonitorRegistry.entries(context)
            .filter { it.appWidgetId != activeWidgetId }
            .map { it.appWidgetId }
        toRemove.forEach { WidgetMonitorRegistry.remove(context, it) }

        val remaining = WidgetMonitorRegistry.entries(context)
        if (remaining.isEmpty()) {
            com.cennoxx.widgetrelay.widget.WidgetMonitorService.stop(context)
        } else {
            com.cennoxx.widgetrelay.widget.WidgetMonitorService.ensureRunning(context)
        }
    }

    // --- Migration / internal helpers ---

    private fun migrateLegacyEvents(context: Context) {
        val prefs = WidgetMonitorRegistry.preferences(context)
        if (prefs.getBoolean(KEY_MIGRATED_EVENTS, false)) return
        val migrated = WidgetMonitorRegistry.entries(context).map { entry ->
            Entry("legacy-event-${entry.appWidgetId}", Type.WIDGET_UPDATED)
        }
        val existing = readWithoutMigration(context).associateBy { it.configKey }
        store(context, (existing.values + migrated.filterNot { existing.containsKey(it.configKey) }))
        prefs.edit().putBoolean(KEY_MIGRATED_EVENTS, true).apply()
    }

    private fun readWithoutMigration(context: Context): List<Entry> {
        val raw = WidgetMonitorRegistry.preferences(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { json ->
                    val configKey = json.optString("key")
                    val type = runCatching { Type.valueOf(json.optString("type")) }.getOrNull()
                    if (configKey.isBlank() || type == null) null else Entry(configKey, type)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun store(context: Context, entries: Collection<Entry>) {
        val array = JSONArray()
        entries.distinctBy { it.configKey }.forEach { array.put(it.toJson()) }
        WidgetMonitorRegistry.preferences(context).edit().putString(KEY_ENTRIES, array.toString()).apply()
    }
}
