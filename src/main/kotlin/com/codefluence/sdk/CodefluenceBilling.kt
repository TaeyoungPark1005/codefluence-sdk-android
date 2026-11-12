@file:JvmName("CodefluenceBilling")

package com.codefluence.sdk

/**
 * Play Billing `Purchase`를 그대로 넘기는 [Codefluence.purchaseCompleted](0.2.2). `onPurchasesUpdated`에서 소비(consume)하기 전에 부르세요.
 * Play Billing은 compileOnly 의존성이라, 이 함수는 별도 클래스(`CodefluenceBilling`)에 있어 Billing이 없는 앱에서도 SDK의 다른 클래스를 깨지 않습니다.
 * 상품이 여러 개인 구매는 첫 상품 ID로 보냅니다(서버가 Google에서 다시 확인합니다).
 */
@JvmOverloads
fun Codefluence.purchaseCompleted(purchase: com.android.billingclient.api.Purchase, isSubscription: Boolean, onResult: (Result<Int>) -> Unit = {}) {
    val productId = purchase.products.firstOrNull() ?: run { onResult(Result.success(0)); return }
    purchaseCompleted(purchase.purchaseToken, productId, isSubscription, purchase.orderId, onResult)
}
