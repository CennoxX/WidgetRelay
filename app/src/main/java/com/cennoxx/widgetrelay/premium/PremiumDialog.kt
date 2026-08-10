package com.cennoxx.widgetrelay.premium

import android.app.Activity
import android.app.AlertDialog
import com.cennoxx.widgetrelay.R

/** Reusable purchase UI used by Tasker configuration and the About screen. */
object PremiumDialog {
    fun show(activity: Activity, onUnlocked: () -> Unit = {}) {
        val billing = PlayBillingEntitlement.get(activity)
        if (billing.isPremiumCached()) {
            onUnlocked()
            return
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.premium_title)
            .setMessage(R.string.premium_loading)
            .setNegativeButton(R.string.premium_cancel, null)
            .setNeutralButton(R.string.premium_restore, null)
            .setPositiveButton(R.string.premium_purchase, null)
            .create()
        dialog.setOnShowListener {
            val purchase = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            purchase?.isEnabled = false
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                billing.refresh { result ->
                    activity.runOnUiThread {
                        if (result.getOrNull() == true) {
                            dialog.dismiss()
                            onUnlocked()
                        } else {
                            dialog.setMessage(activity.getString(R.string.premium_restore_not_found))
                        }
                    }
                }
            }
            fun loadOffer() = billing.loadOffer { result ->
                activity.runOnUiThread {
                    result.onSuccess { offer ->
                        dialog.setMessage(activity.getString(R.string.premium_message, offer.formattedPrice))
                        purchase?.isEnabled = true
                        purchase?.setOnClickListener {
                            purchase.isEnabled = false
                            billing.launchPurchase(activity, offer) { purchaseResult ->
                                activity.runOnUiThread {
                                    when (purchaseResult) {
                                        PlayBillingEntitlement.PurchaseResult.Purchased -> {
                                            dialog.dismiss()
                                            onUnlocked()
                                        }
                                        PlayBillingEntitlement.PurchaseResult.Cancelled -> {
                                            purchase.isEnabled = true
                                        }
                                        is PlayBillingEntitlement.PurchaseResult.Unavailable -> {
                                            dialog.setMessage(
                                                purchaseResult.message ?: activity.getString(R.string.premium_unavailable)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }.onFailure {
                        dialog.setMessage(activity.getString(R.string.premium_unavailable))
                    }
                }
            }
            // A cache can have been lost on reinstall, so restore from Play
            // before inviting the user to buy the same non-consumable again.
            billing.refresh { result ->
                activity.runOnUiThread {
                    if (result.getOrNull() == true) {
                        dialog.dismiss()
                        onUnlocked()
                    } else {
                        loadOffer()
                    }
                }
            }
        }
        dialog.show()
    }
}
