# Codefluence Android SDK

Referral URLs are accepted only as `https://HOST/t/{token}`. Pass incoming deep links to `handleOpenURL(uri)`. Pass `purchaseAccountToken()` as `obfuscatedAccountId` when launching the billing flow so the purchase links back to the referral. Inject Play Integrity through `AttestationProvider` when calling `heartbeat` (aggregate once per day).

minSdk 23, compileSdk 35의 로컬 Android 라이브러리입니다. Maven에 게시된 패키지가 아니며 공개 Codefluence artifact 좌표를 사용하지 않습니다. 공식 Play Install Referrer 클라이언트로 소개 토큰을 자동 복원하며 없으면 클립보드로 복원합니다. 권장 `autoConfirmIfAvailable`는 확인창 없이 `/track/confirm`을 호출하며 소개 코드 입력 UI는 없습니다. 기존 명시적 동의가 필요한 호스트는 `showConfirmationIfNeeded`를 사용할 수 있습니다.

## 빠른 시작 (권장)

```kotlin
// Application.onCreate (구매 SDK 설정 뒤). 사용자 ID를 아직 모르면 null을 돌려주면 됩니다.
val codefluence = Codefluence.start(this, "pk_...", "https://YOUR_CODEFLUENCE_HOST") { stableAppUserId }

// 첫 Intent와 onNewIntent에서. 소개 링크일 때만 true.
codefluence.handle(intent)

codefluence.resume()            // 사용자 ID가 뒤늦게 정해졌을 때
codefluence.purchaseCompleted() // 결제가 끝났을 때(어떤 구매 SDK든)
```

`start`는 `configure`를 부르고, 딥링크가 사용자 ID보다 먼저·나중에 와도 토큰을 보관했다가 ID가 정해지면 확인합니다. 시작 직후 소개 링크가 없다는 판단(귀속 없음 확정)은 1초 유예 뒤에 하며, Activity가 시작될 때마다 `resume()`을 자동으로 부릅니다. `purchaseCompleted()`는 소개로 온 설치에서만 결제를 보고합니다. 앱 URL 스킴 intent-filter는 호스트 앱이 등록하고 대시보드 제품 화면의 「앱 열기 스킴」에도 같은 값을 저장하세요. 아래 개별 호출은 세밀한 제어가 필요할 때만 씁니다.

## 설치

JitPack으로 배포합니다. 앱의 `settings.gradle.kts`에 저장소를 추가하고 의존성을 넣습니다. `kotlin-stdlib`과 Play Install Referrer는 전이 의존성으로 함께 들어옵니다.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.TaeyoungPark1005:codefluence-sdk-android:0.1.1")
}
```

minSdk 23, compileSdk 35, Kotlin 2.0.21·Android Gradle Plugin 8.7.3 기준입니다. 구매 조회는 호스트가 이미 쓰는 Play Billing(`billing` 라이브러리)을 사용하며 SDK는 이를 직접 가져오지 않습니다.

## 출시 전 설치 점검

```kotlin
if (BuildConfig.DEBUG) {
    codefluence.validate(urlScheme = "myapp") { checks ->
        // Logcat 태그 Codefluence에 ✓/✗ 항목: 안내가 남습니다.
    }
}
```

점검 항목: ① 설정(pk_ 키 모양·서버 주소, 비밀 키 sk_가 들어 있으면 실패) ② 서버와 키(실제로 heartbeat를 보내 키가 받아들여지는지, 플랫폼이 맞는지, 서버에 닿는지) ③ URL 스킴(`urlScheme`을 넘겼을 때 AndroidManifest의 intent-filter에 등록돼 있는지). 실패하면 무엇을 고쳐야 하는지가 `hint`에 나옵니다. 통과하면 서버에 첫 신호가 도착해 대시보드 제품 연동 화면의 「앱 SDK 신호」가 바로 바뀝니다. `start`도 하루에 한 번 같은 신호를 자동으로 보냅니다. 토큰·키 값은 출력하지 않습니다. 디버그 빌드에서만 호출하세요.

## 설정과 자동 귀속

```kotlin
import com.codefluence.sdk.Codefluence

val codefluence = Codefluence.configure(
    applicationContext, "pk_...", "https://YOUR_CODEFLUENCE_HOST"
)

// 앱 시작 또는 딥링크 수신 후 앱의 안정적인 사용자 ID를 전달합니다.
// 저장된 토큰이 없으면 SDK가 Play Install Referrer를 조회합니다.
codefluence.autoConfirmIfAvailable(
    appUserId = stableAppUserId
) { result ->
    result.onSuccess { leadId ->
        // null은 토큰 없음 또는 일반 시작, 문자열은 서버 leadId입니다.
    }.onFailure {
        // 토큰·고객 ID·비밀 값은 로그에 남기지 않습니다.
    }
}
```

`YOUR_CODEFLUENCE_HOST`는 실제 실행 중인 Codefluence 서버 주소로 바꿉니다. `configure`의 `apiHost`는 경로·query·fragment·사용자정보가 없는 명시적인 HTTP(S) origin과 호스트가 필수이며 기본 서버 주소는 없습니다. 공개 키는 `pk_`로 시작해야 하며, 키가 잘못되거나 host가 잘못되면 `configure`가 실패하고 네트워크 요청을 만들지 않습니다. `sk_live_`·`sk_test_`는 앱에 넣지 않습니다. 운영은 HTTPS를 사용합니다. Android Emulator에서 Mac의 로컬 서버에 접근하는 개발 주소는 일반적으로 `http://10.0.2.2:3001`이며 호스트 앱의 개발용 cleartext 설정이 필요합니다. 실제 기기 주소는 별도로 지정합니다.

자동 귀속은 화면이나 Activity 없이 호출할 수 있습니다. 저장·네트워크에는 application context를 사용하고 callback은 메인 스레드에서 전달됩니다. 기존 확인창 UX가 필요한 호스트는 `showConfirmationIfNeeded(activity, appUserId, ...)`를 사용할 수 있습니다.

`/track/confirm` 네트워크 요청에 필요한 `android.permission.INTERNET`은 SDK manifest에 포함되어 호스트 앱 manifest로 병합됩니다. 런타임 권한 요청은 필요하지 않습니다.

## Install Referrer와 URL scheme

`autoConfirmIfAvailable`는 저장된 토큰이 없으면 공식 Play Install Referrer API를 조회합니다. 2초 timeout·조회 실패·서비스 연결 해제·토큰 없음이면 클립보드로 복원하고 연결을 닫습니다. 호스트에서 이미 조회했다면 선택적인 `installReferrer` 인자로 넘길 수 있으며 이 경우 중복 조회하지 않습니다. `cf_click_token` 또는 `codefluence_click_token` query와 raw 서명 토큰을 읽습니다. 동기 `restoreReferralToken` 자체는 저장·전달 문자열·클립보드만 읽으며 자동 Play 조회는 자동 귀속 흐름에서 수행합니다.

URL scheme을 쓰면 호스트 앱의 Activity에 intent-filter를 등록합니다.

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="myapp" android:host="referral" />
</intent-filter>
```

앱의 첫 Intent와 `onNewIntent`에서 URL을 검증하고 토큰을 `autoConfirmIfAvailable`에 넘깁니다. SDK는 scheme 등록이나 Intent 수신을 자동 처리하지 않습니다.

```kotlin
val incoming = intent.data
val token = incoming?.takeIf {
    it.scheme == "myapp" && it.host == "referral"
}?.getQueryParameter("cf_click_token")

codefluence.autoConfirmIfAvailable(
    appUserId = stableAppUserId,
    installReferrer = token ?: playInstallReferrer
) { result -> /* 서버 확인 결과 */ }
```

클립보드 접근·Install Referrer 수신은 OS와 배포 환경에 영향을 받습니다. 복원이 항상 성공한다는 보장은 없습니다. `autoConfirmIfAvailable`는 첫 복원에 토큰이 없으면 귀속 없음 결정을 저장하고 `success(null)`을 반환하며 서버 요청은 없습니다. 이후 토큰이 생겨도 같은 설치에서 새 귀속을 만들지 않습니다. 기존 `showConfirmationIfNeeded`를 사용하는 호스트는 예·아니오 결정을 저장합니다. 늦게 끝난 복원은 이미 저장한 자동 귀속·대기 고객 ID·토큰을 덮어쓰지 않습니다.

기존 `showConfirmationIfNeeded` 확인창은 기기 기본 locale의 언어가 `ko`이면 한국어, 그 외에는 영어입니다. 영어 문구는 **Start with this referral? / Use this referral link to attribute your purchases. / Yes / No**입니다. 권장 자동 귀속 경로에는 확인창이 없습니다.

## 키, 동의, 고객 ID

앱에는 제품의 `pk_...`만 넣습니다. `sk_test`·`sk_live`는 제품 서버에서만 사용합니다. `pk` 자체는 테스트 키가 아니며 `/track/confirm`은 서명된 클릭의 sandbox를 계승합니다.

자동 귀속은 복원된 유효 토큰으로 결정을 저장하고 확인 요청을 보냅니다. 토큰이 없으면 `success(null)`이며 서버 요청을 보내지 않습니다. 기존 명시적 경로에서는 **예**에서만 확인 요청을 보내고 **아니오**는 거절로 저장합니다. 결제 기록은 스토어 서버 알림 또는 제품 서버에서 전달합니다. 앱 구매 성공 콜백 자체로 sale을 만들지 않습니다.

확인 ID(`appUserId`)는 앱이 쓰는 안정적인 사용자 ID로 유지하고 로그인·계정 전환 후에도 같은 기준을 씁니다. 임의의 이메일·기기 ID를 대신 사용하지 않습니다. SANDBOX 거래는 운영 청구·지급에서 제외됩니다.

## 자동 재시도와 멱등 (A3)

자동 귀속과 명시적 예 경로는 네트워크 요청 전에 결정을 저장합니다. 동의된(자동 귀속 포함) 미완료 confirm은 영속 큐(SharedPreferences)에 남아, **앱 재실행(`configure` 호출) 시점**과 **네트워크 복구(`ConnectivityManager` NetworkCallback)** 때 지수 backoff로 자동 재전송됩니다. backoff는 기본 2초 시작·2배·상한 1시간·full jitter이며 최대 시도 횟수 상한(기본 10회)에서 멈춥니다. 요청마다 첫 큐잉 때 생성·저장한 동일 `Idempotency-Key` 헤더를 보내 서버가 `/track/confirm`에서 멱등 처리합니다. 네트워크 복구 감시를 위해 SDK manifest는 `android.permission.ACCESS_NETWORK_STATE`(런타임 요청 불필요)를 추가로 병합합니다.

```kotlin
codefluence.retryPendingConfirmation { result ->
    // 메인 스레드 callback. 수동 즉시 재시도이며 backoff 상태를 초기화합니다.
}
```

재시도하지 않는 경우: 사용자가 **거절**한 경우, 이미 **완료(confirmed)**된 경우, **무귀속(no-attribution)** 확정, 서버가 돌려준 **영구 오류(400/401/403/404/409/422)**. 이때 큐를 비우고 더 보내지 않습니다. **408/429/5xx·IO(네트워크) 오류**만 backoff로 재시도합니다. 연결·읽기 timeout은 각각 5초이며 요청 종료 시 연결을 해제합니다. 유효한 비어 있지 않은 leadId를 받은 뒤 완료로 저장합니다. 진행 중 요청과 표시 중 확인 창은 여러 SDK 인스턴스에서도 중복 시작하지 않고, 저장된 토큰·고객 ID를 바꿔 동의를 우회하지 않습니다. 취소는 아니오로 저장되며 이미 확정된 예를 덮어쓰지 않습니다. 이전 버전의 결정에는 예·아니오 구분이 없으므로 동의 증거 없이 retry 대상으로 승격하지 않습니다. 실제 기기의 네트워크 복구·설치 복원·결제 연결 QA는 남아 있습니다.

## 구조화 에러 (B6)

실패 결과는 공개 `sealed class CodefluenceError`입니다. 기존 숫자 코드는 `code` 속성으로 유지하고, 기존 메시지 기반 `Result.failure` 콜백과 `catch (e: IllegalStateException)` 블록이 그대로 동작하도록 `IllegalStateException`을 상속합니다. `isRetryable`은 호스트가 재시도 가능 여부를 판단할 때 씁니다(현재 `RequestFailed`만 `true`). 코드 번호는 iOS SDK와 동일합니다.

| code | case | 의미 | 재시도 |
| --- | --- | --- | --- |
| 1 | `RequestFailed(message)` | 네트워크/HTTP 요청 실패 또는 응답 무효 | 가능 |
| 2 | `MissingReferralInput` | 토큰·고객 ID 누락 | 불가 |
| 3 | `Declined` | 저장된 "아니오" | 불가 |
| 4 | `AttributionForDifferentCustomer` | 다른 고객의 귀속 | 불가 |
| 5 | `RetryIdentityMismatch` | 재시도가 토큰·고객 ID를 바꿈 | 불가 |
| 6 | `NoPendingConfirmation` | 재시도할 대기 요청 없음 | 불가 |
| 7 | `RequestInProgress` | 요청 진행 중 | 불가 |
| 8 | `AlreadyPresenting` | 확인창/귀속 이미 진행 | 불가 |
| 9 | `PresenterOrCustomerRequired(message)` | foreground Activity/고객 ID 필요 | 불가 |
| 10 | `RestorationFailed(message)` | 복원/표시 실패 | 불가 |
| 11 | `NotConfigured` | 미설정(pk_ 키) | 불가 |
| 12 | `NoSubscriptionReference` | 저장된 lead/purchaseAccountToken 없음 | 불가 |
| 13 | `SubscriptionNotFound` | 서버에 구독 기록 없음(404) | 불가 |

```kotlin
result.onFailure { error ->
    if (error is CodefluenceError && error.isRetryable) { /* 나중에 다시 */ }
}
```

## 구독 상태 읽기 (C3, 읽기 전용)

`subscriptionStatus(onResult)`는 서버가 어댑터 이벤트를 정규화한 구독 상태를 읽습니다. **읽기 전용 view이며 entitlement 판정이 아닙니다.** 유료 기능 접근 제어에는 사용하지 말고, 접근 권한은 결제 제공자의 entitlement/영수증 검사로 판단하세요.

```kotlin
codefluence.subscriptionStatus { result ->
    result.onSuccess { status ->
        // status.state: NONE|ACTIVE|GRACE|CANCELLED|EXPIRED|REFUNDED
        // status.sandbox, status.updatedAt, status.events
    }.onFailure { error ->
        // NoSubscriptionReference(저장값 없음) 또는 SubscriptionNotFound(404)
    }
}
```

`/track/confirm`에서 저장한 `leadId`와 `purchaseAccountToken`으로 `POST {apiHost}/track/subscription`(`Authorization: Bearer pk_...`)을 호출합니다. 저장값이 없으면 `NoSubscriptionReference`, 서버 404는 `SubscriptionNotFound`를 반환합니다. 저장된 confirm 응답 토큰이 없으면 로컬 `purchaseAccountToken`으로 대체합니다. 알 수 없는 event type은 `kind`가 `null`이고 `rawType`에 원문을 보존합니다.

## 빌드

JDK 17 또는 21, Android SDK Platform 35 및 Build Tools 35가 필요합니다. `JAVA_HOME`과 `ANDROID_HOME`을 해당 설치 경로로 설정하거나 `local.properties`에 `sdk.dir`을 지정합니다. 로컬 검증은 Android Studio의 JDK 21로 수행했습니다.

```sh
./gradlew testReleaseUnitTest assembleRelease lintRelease --no-daemon
```

Gradle 8.9 wrapper는 공식 배포의 SHA-256을 확인합니다. 산출물은 `build/outputs/aar/codefluence-sdk-android-release.aar`입니다.

로컬 AAR 컴파일과 lint를 검증했습니다. 실제 Android 기기의 확인 시트, 붙여넣기·Install Referrer 복원, 네트워크 확인, 스토어 구매는 아직 검증하지 않았습니다.

JVM 단위 테스트는 실제 SDK가 사용하는 저장 결정 로직에 메모리 SharedPreferences를 주입하여 토큰 없음 결정의 재생성 후 유지, 늦은 복원·동의 경합·고객 ID 보존과 한·영 문구를 확인합니다. 공유 admission gate의 동시 호출·해제도 검증합니다. A3는 주입한 transport·scheduler·network monitor·executor로 backoff 계산·지터 범위·상태 분류, 재실행/네트워크 복구 재전송, 영구오류 큐 비움, 동일 `Idempotency-Key` 유지, 시도 상한, 수동 재시도의 backoff 초기화를 확인합니다. B6는 code 1~13 매핑·`IllegalStateException` 호환·`isRetryable`을, C3는 구독 응답 파싱·알 수 없는 event type 보존·404 및 저장값 없음 에러·로컬 토큰 대체를 확인합니다. Android UI, 실제 디스크 저장, Google Play 서비스, 실제 HTTP 요청을 실행하는 테스트는 아닙니다.

## 결제 보고 (`reportPurchases`)

RevenueCat 등 어떤 결제 SDK로 결제했든, 결제 성공 콜백에서 `codefluence.reportPurchases()`를 부르면 SDK가 자체 `BillingClient`로 INAPP·SUBS 구매를 읽어 아직 보고하지 않은 구매 토큰을 확인된 소개 정보(`leadId`, 설치 토큰)와 함께 `/track/purchase`로 보냅니다. 서버는 Google Play Developer API로 구매를 검증한 뒤 연결합니다. 앱 시작과 소개 확인 직후에도 자동으로 돕니다.

- Play Billing 라이브러리는 `compileOnly`이며 **7.x 이상**을 지원합니다. 인앱 결제를 파는 앱은 이미 포함하고 있으며, 없거나 6.x 이하면 보고를 건너뜁니다(앱을 멈추지 않습니다).
- Google은 활성 구독과 소모하지 않은 일회성 구매만 돌려줍니다. 소모성 상품은 결제 성공 콜백에서 반드시 호출하세요.
- iOS와 달리 서명된 계정 값이 없어, "이 기기의 구매"라는 연결은 SDK 보고에 기댑니다(SPEC §25.2 `reported_token`).
- 근거 문서: https://developer.android.com/reference/com/android/billingclient/api/BillingClient#queryPurchasesAsync(com.android.billingclient.api.QueryPurchasesParams,com.android.billingclient.api.PurchasesResponseListener)
