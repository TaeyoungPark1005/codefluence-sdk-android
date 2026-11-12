# 변경 기록

## 0.2.2

- **미전송 구매 영속 큐.** 구매 보고를 기기에 저장하고 서버가 2xx로 받을 때까지 다시 보냅니다(네트워크 오류·5xx·429·409 `credential_missing`은 재시도, 401·403은 하루 간격으로 보존, 그 밖의 4xx는 제거). 위치는 `noBackupFilesDir/codefluence-purchase-queue.json`, 상한 100건·90일, 구매 토큰으로 중복 방지. 소개로 온 설치만 저장합니다. 소비(consume)돼 `queryPurchasesAsync`에서 사라진 구매도 다시 보내집니다.
- **결제 직후 구매를 직접 넘기는 호출.** `purchaseCompleted(purchaseToken, productId, isSubscription, orderId)`와 Play Billing `Purchase`용 확장 함수 `purchaseCompleted(purchase, isSubscription)`. 다른 결제 SDK(RevenueCat 등)를 쓰는 앱은 결제 완료 콜백에서 이 호출을 써야 소모성 결제가 빠지지 않습니다. 인자 없는 `purchaseCompleted()`는 그대로이며 같은 큐를 씁니다.
- **단건 `reportPurchase(purchase:)` 수정.** 스토어 목록 보고와 같은 본문(확인된 `leadId`·`purchaseAccountToken` 포함)을 보내 서버의 `reported_token` 연결이 되게 했고, 같은 큐를 거칩니다.
- `build` 필드, 서버 플래그 꺼짐 시 동작 등 0.2.1의 규칙은 그대로입니다.

예제는 README 「미전송 구매 큐 (0.2.2)」.
