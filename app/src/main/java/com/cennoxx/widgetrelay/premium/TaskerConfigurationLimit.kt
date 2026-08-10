package com.cennoxx.widgetrelay.premium

/**
 * Pure free-tier policy, kept independent from Android and Google Play Billing.
 *
 * Under the "last saved is active" model every save is allowed; the blocker at
 * configuration time is removed. At runtime, a configuration is active when the
 * user is on premium or when the configuration hash matches the most recently
 * saved free config.
 */
object TaskerConfigurationLimit {

    /**
     * Compares the configuration's content hash against the stored active
     * configuration hash. If the user is premium this always returns true.
     */
    fun isConfigActive(context: android.content.Context, isPremium: Boolean, input: com.cennoxx.widgetrelay.tasker.widgets.WidgetActionInput): Boolean {
        if (isPremium) return true
        val payload = com.cennoxx.widgetrelay.premium.PremiumConfigStore.serializeWidgetConfig(input)
        val currentHash = com.cennoxx.widgetrelay.premium.PremiumConfigStore.computeConfigHash(payload)
        val activeHash = com.cennoxx.widgetrelay.premium.PremiumConfigStore.getActiveConfigHash(context)
        return activeHash != null && activeHash == currentHash
    }
}

