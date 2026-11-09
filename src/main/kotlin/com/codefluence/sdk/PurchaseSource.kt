package com.codefluence.sdk

import android.content.Context

/** One Google Play purchase the app owns. The server re-verifies it with the Play Developer API. */
data class OwnedPurchase(val purchaseToken: String, val productId: String, val subscription: Boolean)

/** Delivers a result at most once (Billing can call back after a disconnect too). */
class OnceResult<T>(private val onResult: (T) -> Unit) {
    private val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
    fun deliver(value: T) { if (delivered.compareAndSet(false, true)) onResult(value) }
}

/** Where the SDK reads the app's Google Play purchases from. Tests inject a fake. */
fun interface PurchaseSource {
    fun ownedPurchases(onResult: (List<OwnedPurchase>) -> Unit)
}

/**
 * Reads purchases with its own BillingClient, so it works no matter which purchase SDK
 * (Play Billing directly, RevenueCat, ...) made the purchase. Google returns only active
 * subscriptions and non-consumed one-time purchases, so apps must also call
 * `reportPurchases()` from their purchase-success callback for consumables.
 * The Play Billing library is a compileOnly dependency: apps that sell in-app products
 * already ship it; without it this source is not created.
 */
internal class PlayBillingPurchaseSource(private val context: Context) : PurchaseSource {
    override fun ownedPurchases(onResult: (List<OwnedPurchase>) -> Unit) {
        val once = OnceResult(onResult)
        val client = com.android.billingclient.api.BillingClient.newBuilder(context)
            .setListener { _, _ -> }
            .enablePendingPurchases(com.android.billingclient.api.PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .build()
        client.startConnection(object : com.android.billingclient.api.BillingClientStateListener {
            override fun onBillingSetupFinished(result: com.android.billingclient.api.BillingResult) {
                if (result.responseCode != com.android.billingclient.api.BillingClient.BillingResponseCode.OK) {
                    android.util.Log.w("Codefluence", "Play Billing setup failed: code=${result.responseCode} ${result.debugMessage}")
                    client.endConnection(); once.deliver(emptyList()); return
                }
                val out = mutableListOf<OwnedPurchase>()
                fun query(type: String, subscription: Boolean, next: () -> Unit) {
                    client.queryPurchasesAsync(com.android.billingclient.api.QueryPurchasesParams.newBuilder().setProductType(type).build()) { _, purchases ->
                        purchases.filter { it.purchaseState == com.android.billingclient.api.Purchase.PurchaseState.PURCHASED }
                            .forEach { purchase -> purchase.products.firstOrNull()?.let { out.add(OwnedPurchase(purchase.purchaseToken, it, subscription)) } }
                        next()
                    }
                }
                query(com.android.billingclient.api.BillingClient.ProductType.INAPP, false) {
                    query(com.android.billingclient.api.BillingClient.ProductType.SUBS, true) { client.endConnection(); once.deliver(out) }
                }
            }
            override fun onBillingServiceDisconnected() {
                android.util.Log.w("Codefluence", "Play Billing service disconnected before purchases were read")
                runCatching { client.endConnection() }; once.deliver(emptyList())
            }
        })
    }

    companion object {
        /** Play Billing 7.x+ only (PendingPurchasesParams). Older or missing Billing → no source. */
        fun createIfAvailable(context: Context?): PurchaseSource? = context?.takeIf {
            runCatching {
                Class.forName("com.android.billingclient.api.BillingClient")
                Class.forName("com.android.billingclient.api.PendingPurchasesParams")
            }.isSuccess
        }?.let { PlayBillingPurchaseSource(it) }
    }
}
