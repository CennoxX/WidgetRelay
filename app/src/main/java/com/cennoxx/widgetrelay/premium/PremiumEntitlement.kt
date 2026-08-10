package com.cennoxx.widgetrelay.premium

/** Small abstraction so the configuration limit never depends on Google Play. */
interface PremiumEntitlement {
    fun isPremiumCached(): Boolean
    fun refresh(onComplete: (Result<Boolean>) -> Unit)
}
