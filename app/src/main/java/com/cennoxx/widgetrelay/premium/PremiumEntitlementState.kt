package com.cennoxx.widgetrelay.premium

import android.content.Context
import com.cennoxx.widgetrelay.widget.WidgetMonitorRegistry

/** Persistent cache boundary, separated so entitlement persistence is testable. */
interface PremiumStateStore {
    fun load(): Boolean
    fun save(premium: Boolean)
}

class PremiumEntitlementState(private val store: PremiumStateStore) {
    private var premium = store.load()

    /**
     * Honor the "full" build variant: when built as full, treat the app as
     * premium (no freemium restrictions). Otherwise return the cached value.
     */
    fun isPremium(): Boolean {
        // BuildConfig is generated into the app package; reference the flag
        try {
            if (com.cennoxx.widgetrelay.BuildConfig.IS_FULL_BUILD) return true
        } catch (_: Exception) {
            // defensive: if BuildConfig isn't available in some test env, ignore
        }
        return premium
    }

    fun updateFromPlay(value: Boolean) {
        premium = value
        store.save(value)
    }
}

class WidgetPremiumStateStore(context: Context) : PremiumStateStore {
    private val prefs = WidgetMonitorRegistry.preferences(context)

    override fun load() = prefs.getBoolean(KEY_PREMIUM, false)

    override fun save(premium: Boolean) {
        prefs.edit().putBoolean(KEY_PREMIUM, premium).apply()
    }

    private companion object {
        const val KEY_PREMIUM = "premium_cached"
    }
}
