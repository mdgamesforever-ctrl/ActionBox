package com.futurepath.actionbox.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.futurepath.actionbox.BuildConfig
import com.futurepath.actionbox.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Wraps Google Play Billing for the Pro subscription. [SettingsRepository.isPro] is the single
 * source of truth every other part of the app reads (retention enforcement, the correction-
 * learning gate, the ad banner) — this class's whole job is keeping that flag in sync with
 * Play's actual entitlement state, never gating anything directly itself.
 *
 * There is no Play Console listing behind [PRO_SUBSCRIPTION_PRODUCT_ID] in this environment
 * (no device/Play Store access — see the project's standing testing constraints), so
 * `queryProductDetailsAsync`/purchases will simply come back empty here; [billingUnavailable]
 * surfaces that to the paywall UI as "try again later" rather than a silent dead button, which
 * is also the correct behavior for a real user with no network or a Play Store outage.
 */
class BillingRepository private constructor(context: Context) : PurchasesUpdatedListener {

    private val appContext = context.applicationContext
    private val settingsRepository = SettingsRepository.getInstance(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val billingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        // PendingPurchasesParams.Builder.build() throws IllegalArgumentException
        // ("Pending purchases for one-time products must be supported.") unless
        // enableOneTimeProducts() is called — true even for a subscription-only app like this
        // one with no INAPP products at all; the Billing Library 6/7 API requires this flag set
        // regardless. Confirmed via the real 7.1.1 AAR (javap), not just documentation.
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails.asStateFlow()

    /** True once billing setup has failed or come back without the Pro product — the paywall
     * shows this as "try again later" rather than leaving a Subscribe button that can never
     * do anything. */
    private val _billingUnavailable = MutableStateFlow(false)
    val billingUnavailable: StateFlow<Boolean> = _billingUnavailable.asStateFlow()

    /** Safe to call more than once — a no-op while already connected/connecting. */
    fun startConnection() {
        if (billingClient.isReady) return
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryProductDetails()
                    queryExistingPurchases()
                } else {
                    Log.w(TAG, "Billing setup failed: ${result.debugMessage} (${result.responseCode})")
                    _billingUnavailable.value = true
                }
            }

            // No automatic reconnect loop for v1 — the next explicit startConnection() call
            // (e.g. the paywall screen re-entering composition) retries from scratch, which is
            // enough for a purchase flow the user is actively driving.
            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected")
            }
        })
    }

    private fun queryProductDetails() {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRO_SUBSCRIPTION_PRODUCT_ID)
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
        billingClient.queryProductDetailsAsync(params) { result, productDetailsResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                // Billing Library 8 changed this callback's second parameter from a plain
                // List<ProductDetails> to QueryProductDetailsResult, which separates
                // successfully-fetched products from unfetchedProductList (e.g. a mistyped or
                // unpublished product id) — this app only has the one product, so the fetched
                // list alone is still enough to tell "found" from "not found".
                val details = productDetailsResult.productDetailsList.firstOrNull()
                _productDetails.value = details
                _billingUnavailable.value = details == null
            } else {
                Log.w(TAG, "queryProductDetailsAsync failed: ${result.debugMessage} (${result.responseCode})")
                _billingUnavailable.value = true
            }
        }
    }

    /**
     * Re-derives [SettingsRepository.isPro] from Play's own records — the equivalent of
     * "restore purchases", run automatically on every connection (app start, and whenever the
     * paywall re-triggers [startConnection]) rather than needing a dedicated button, per Play
     * Billing's own recommended integration pattern.
     *
     * In a debug build this must never downgrade [SettingsRepository.isPro] to false: there is
     * no real Play Console listing reachable from a dev/test environment (see this class's own
     * doc), so [hasActivePro] is unconditionally false here, and applying it unguarded used to
     * silently overwrite "Simulate Pro" back to off on every single app start — right after
     * [SettingsRepository.setPro] had correctly persisted the debug override — which is what made
     * the toggle look like it wasn't persisting at all, and also meant VIP escalation
     * (NotificationRepository.capture's `settingsRepository.isPro.first()` check) silently
     * stopped firing on the very next launch. A release build has no debug override to protect
     * and must still downgrade normally (an expired/cancelled real subscription has to actually
     * revoke Pro), so this guard is debug-only.
     */
    private fun queryExistingPurchases() {
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.w(TAG, "queryPurchasesAsync failed: ${result.debugMessage} (${result.responseCode})")
                return@queryPurchasesAsync
            }
            val hasActivePro = purchases.any {
                PRO_SUBSCRIPTION_PRODUCT_ID in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED
            }
            if (hasActivePro || !BuildConfig.DEBUG) {
                scope.launch { settingsRepository.setPro(hasActivePro) }
            }
            purchases.forEach(::acknowledgeIfNeeded)
        }
    }

    /** Launches Play's own purchase UI. No-ops if product details haven't loaded yet — the
     * paywall screen only enables its Subscribe button once [productDetails] is non-null. */
    fun launchPurchaseFlow(activity: Activity) {
        val details = _productDetails.value ?: return
        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return
        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(offerToken)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()
        billingClient.launchBillingFlow(activity, flowParams)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.forEach(::handlePurchase)
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> Log.w(TAG, "onPurchasesUpdated: ${result.debugMessage} (${result.responseCode})")
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            scope.launch { settingsRepository.setPro(true) }
            acknowledgeIfNeeded(purchase)
        }
    }

    /** Play requires every purchase to be acknowledged within 3 days or it's automatically
     * refunded — done immediately here since there's nothing else this app needs to do first
     * (no server-side entitlement grant to wait on). */
    private fun acknowledgeIfNeeded(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(params) { ackResult ->
                if (ackResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "acknowledgePurchase failed: ${ackResult.debugMessage} (${ackResult.responseCode})")
                }
            }
        }
    }

    companion object {
        private const val TAG = "BillingRepository"

        /** Matches whatever subscription product is configured in the Play Console listing —
         * not itself a secret, just an id. */
        const val PRO_SUBSCRIPTION_PRODUCT_ID = "actionbox_pro_subscription"

        @Volatile
        private var instance: BillingRepository? = null

        fun getInstance(context: Context): BillingRepository {
            return instance ?: synchronized(this) {
                instance ?: BillingRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
