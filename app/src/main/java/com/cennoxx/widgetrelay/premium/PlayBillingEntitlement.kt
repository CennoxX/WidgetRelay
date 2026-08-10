package com.cennoxx.widgetrelay.premium

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.cennoxx.widgetrelay.widget.WidgetMonitorRegistry

/**
 * Google Play-backed entitlement for WidgetRelay Premium. The SharedPreferences
 * value is a fast cache only; successful Play queries always replace it.
 */
class PlayBillingEntitlement private constructor(context: Context) : PremiumEntitlement, PurchasesUpdatedListener {
    data class Offer(val productDetails: ProductDetails, val formattedPrice: String, val offerToken: String? = null)

    sealed class PurchaseResult {
        data object Purchased : PurchaseResult()
        data object Cancelled : PurchaseResult()
        data class Unavailable(val message: String?) : PurchaseResult()
    }

    private val appContext = context.applicationContext
    private val entitlementState = PremiumEntitlementState(WidgetPremiumStateStore(appContext))
    private val client = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            com.android.billingclient.api.PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    private var connecting = false
    private val connectionCallbacks = mutableListOf<(Boolean) -> Unit>()
    private var productDetails: ProductDetails? = null
    private var pendingPurchaseCallback: ((PurchaseResult) -> Unit)? = null

    override fun isPremiumCached() = entitlementState.isPremium()

    override fun refresh(onComplete: (Result<Boolean>) -> Unit) {
        withConnection { connected ->
            if (!connected) {
                onComplete(Result.failure(IllegalStateException("Google Play billing is unavailable.")))
                return@withConnection
            }
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
            ) { result, purchases ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    onComplete(Result.failure(IllegalStateException(result.debugMessage)))
                    return@queryPurchasesAsync
                }
                val premiumPurchase = purchases.firstOrNull(::isPremiumPurchase)
                setCachedPremium(premiumPurchase != null)
                premiumPurchase?.let(::acknowledgeIfNeeded)
                onComplete(Result.success(premiumPurchase != null))
            }
        }
    }

    fun loadOffer(onComplete: (Result<Offer>) -> Unit) {
        withConnection { connected ->
            if (!connected) {
                onComplete(Result.failure(IllegalStateException("Google Play billing is unavailable.")))
                return@withConnection
            }
            val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
            client.queryProductDetailsAsync(
                QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
            ) { result, detailsResult ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    onComplete(Result.failure(IllegalStateException(result.debugMessage)))
                    return@queryProductDetailsAsync
                }
                val details = detailsResult.productDetailsList.firstOrNull()
                    ?: run {
                        onComplete(Result.failure(IllegalStateException("WidgetRelay Premium is not available.")))
                        return@queryProductDetailsAsync
                    }
                val oneTimeOffer = details.oneTimePurchaseOfferDetailsList?.firstOrNull()
                    ?: run {
                        onComplete(Result.failure(IllegalStateException("WidgetRelay Premium has no available purchase option.")))
                        return@queryProductDetailsAsync
                    }
                productDetails = details
                onComplete(Result.success(Offer(details, oneTimeOffer.formattedPrice ?: "", null)))
            }
        }
    }

    fun launchPurchase(activity: Activity, offer: Offer, onResult: (PurchaseResult) -> Unit) {
        pendingPurchaseCallback = onResult
        val productBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(offer.productDetails)
        if (!offer.offerToken.isNullOrBlank()) {
            productBuilder.setOfferToken(offer.offerToken)
        }
        val product = productBuilder.build()
        val result = client.launchBillingFlow(
            activity,
            BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(product)).build()
        )
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            pendingPurchaseCallback = null
            onResult(PurchaseResult.Unavailable(result.debugMessage))
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val premiumPurchase = purchases?.firstOrNull(::isPremiumPurchase)
                if (premiumPurchase != null) {
                    setCachedPremium(true)
                    acknowledgeIfNeeded(premiumPurchase)
                    pendingPurchaseCallback?.invoke(PurchaseResult.Purchased)
                } else {
                    pendingPurchaseCallback?.invoke(PurchaseResult.Unavailable("The premium purchase was not completed."))
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> pendingPurchaseCallback?.invoke(PurchaseResult.Cancelled)
            else -> pendingPurchaseCallback?.invoke(PurchaseResult.Unavailable(result.debugMessage))
        }
        pendingPurchaseCallback = null
    }

    private fun isPremiumPurchase(purchase: Purchase) =
        purchase.purchaseState == Purchase.PurchaseState.PURCHASED && PRODUCT_ID in purchase.products

    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        withConnection { connected ->
            if (connected) {
                client.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
                ) { /* Entitlement remains valid once Play reports PURCHASED. */ }
            }
        }
    }

    private fun setCachedPremium(value: Boolean) {
        entitlementState.updateFromPlay(value)
    }

    private fun withConnection(callback: (Boolean) -> Unit) {
        if (client.isReady) {
            callback(true)
            return
        }
        connectionCallbacks += callback
        if (connecting) return
        connecting = true
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                val callbacks = connectionCallbacks.toList()
                connectionCallbacks.clear()
                callbacks.forEach { it(result.responseCode == BillingClient.BillingResponseCode.OK) }
            }

            override fun onBillingServiceDisconnected() {
                connecting = false
                val callbacks = connectionCallbacks.toList()
                connectionCallbacks.clear()
                callbacks.forEach { it(false) }
            }
        })
    }

    companion object {
        const val PRODUCT_ID = "widgetrelay_premium"

        @Volatile private var instance: PlayBillingEntitlement? = null

        fun get(context: Context): PlayBillingEntitlement = instance ?: synchronized(this) {
            instance ?: PlayBillingEntitlement(context).also { instance = it }
        }
    }
}
