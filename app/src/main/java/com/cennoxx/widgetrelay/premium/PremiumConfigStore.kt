package com.cennoxx.widgetrelay.premium

import android.content.Context
import com.cennoxx.widgetrelay.widget.WidgetMonitorRegistry
import com.cennoxx.widgetrelay.tasker.widgets.WidgetActionInput
import java.security.MessageDigest

object PremiumConfigStore {
    private const val KEY_ACTIVE_CONFIG = "tasker_active_freemium_config_hash"
    private const val KEY_ACTIVE_CONFIG_PAYLOAD = "tasker_active_freemium_config_payload"
    private const val KEY_ACTIVE_CONFIG_APP_WIDGET_ID = "tasker_active_freemium_app_widget_id"

    // Compute deterministic SHA-256 hex of the config payload string
    fun computeConfigHash(payload: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    // Build a deterministic serialized representation of the WidgetActionInput
    // stable order and explicit empty values ensures identical content -> identical hash
    fun serializeWidgetConfig(input: WidgetActionInput): String {
        val provider = input.provider ?: ""
        val query = input.query ?: ""
        // appWidgetId is intentionally included: clones that copy the same widget id will match.
        // If you want clones to not inherit activity, include a task-unique field instead.
        return buildString {
            append("appWidgetId=").append(input.appWidgetId).append(";")
            append("provider=").append(provider).append(";")
            append("spanX=").append(input.spanX).append(";")
            append("spanY=").append(input.spanY).append(";")
            append("query=").append(query)
        }
    }

    fun setActiveConfig(context: Context, serializedConfig: String, appWidgetId: Int) {
        val prefs = WidgetMonitorRegistry.preferences(context)
        val hash = computeConfigHash(serializedConfig)
        prefs.edit()
            .putString(KEY_ACTIVE_CONFIG, hash)
            .putString(KEY_ACTIVE_CONFIG_PAYLOAD, serializedConfig)
            .putInt(KEY_ACTIVE_CONFIG_APP_WIDGET_ID, appWidgetId)
            .apply()
    }

    fun getActiveConfigHash(context: Context): String? {
        val prefs = WidgetMonitorRegistry.preferences(context)
        val existing = prefs.getString(KEY_ACTIVE_CONFIG, null)
        if (!existing.isNullOrBlank()) return existing
        return null
    }

    fun clearActiveConfig(context: Context) {
        val prefs = WidgetMonitorRegistry.preferences(context)
        prefs.edit()
            .remove(KEY_ACTIVE_CONFIG)
            .remove(KEY_ACTIVE_CONFIG_PAYLOAD)
            .remove(KEY_ACTIVE_CONFIG_APP_WIDGET_ID)
            .apply()
    }
}
