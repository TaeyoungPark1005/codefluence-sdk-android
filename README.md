# Codefluence Android SDK

Referral URLs are accepted only as `https://HOST/t/{token}`. Pass incoming deep links to `handleOpenURL(uri)`. Pass `purchaseAccountToken()` as `obfuscatedAccountId` when launching the billing flow so the purchase links back to the referral. Inject Play Integrity through `AttestationProvider` when calling `heartbeat` (aggregate once per day).

minSdk 23, compileSdk 35의 Android 라이브러리입니다. JitPack으로 게시되어 있습니다(아래 「설치」). 공식 Play Install Referrer 클라이언트로 소개 토큰을 자동 복원하며 없으면 클립보드로 복원합니다. 권장 `autoConfirmIfAvailable`는 확인창 없이 `/track/confirm`을 호출하며 소개 코드 입력 UI는 없습니다. 기존 명시적 동의가 필요한 호스트는 `showConfirmationIfNeeded`를 사용할 수 있습니다.

## 빠른 시작 (권장)

```kotlin
// Application.onCreate (구매 SDK 설정 뒤). 사용자 ID를 아직 모르면 null을 돌려주면 됩니다.
val codefluence = Codefluence.start(this, "pk_...", "https://YOUR_CODEFLUENCE_HOST") { stableAppUserId }

// 첫 Intent와 onNewIntent에서. 소개 링크일 때만 true.
codefluence.handle(intent)

codefluence.resume()            // 사용자 ID가 뒤늦게 정해졌을 때
codefluence.purchaseCompleted() // 결제가 끝났을 때(어떤 구매 SDK든)

// 0.2.2 권장: 결제 직후 구매를 직접 넘깁니다(소비된 소모성 결제가 빠지지 않습니다. 아래 「미전송 구매 큐」).
codefluence.purchaseCompleted(purchase, isSubscription = false)   // Play Billing `Purchase`
codefluence.purchaseCompleted(tx.purchaseToken, tx.productIds.first(), tx.type == ProductType.SUBS, tx.orderId) // RevenueCat
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
    implementation("com.github.TaeyoungPark1005:codefluence-sdk-android:0.2.2")
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

점검 항목: ① 설정(pk_ 키 모양·서버 주소, 비밀 키 sk_가 들어 있으면 실패) ② 서버와 키(실제로 heartbeat를 보내 키가 받아들여지는지, 플랫폼이 맞는지, 서버에 닿는지) ③ 빌드(안내 항목, 0.2.1) ④ URL 스킴(`urlScheme`을 넘겼을 때 AndroidManifest의 intent-filter에 등록돼 있는지). 실패하면 무엇을 고쳐야 하는지가 `hint`에 나옵니다. 통과하면 서버에 첫 신호가 도착해 대시보드 제품 연동 화면의 「앱 SDK 신호」가 바로 바뀝니다. `start`도 하루에 한 번 같은 신호를 자동으로 보냅니다. 토큰·키 값은 출력하지 않습니다. 디버그 빌드에서만 호출하세요.

**개발 빌드 표시(0.2.1).** SDK는 모든 서버 요청에 `build`(`"debug"` 또는 `"release"`)를 싣습니다. 앱이 디버그 가능 빌드(`ApplicationInfo.FLAG_DEBUGGABLE`, 보통 debug 빌드 타입)면 `"debug"`입니다(`codefluence.build`). 에뮬레이터 여부는 보지 않습니다. 서버는 개발 빌드의 heartbeat를 대시보드에 「개발 빌드에서 확인됨」으로만 보여 주고 SDK 무결성 판정·confirm 비율에서 빼며, 확인·재열기·활동 신호·광고 수익·결제 보고는 sandbox로 기록해 보상과 집계에서 뺍니다. 출시(release) 빌드는 지금처럼 집계됩니다. Play 테스트 구매는 이 값과 관계없이 Play 응답으로 sandbox가 됩니다.

Development builds (0.2.1): every request carries `build` (`"debug"` when the host app is debuggable). The server shows those heartbeats as "seen in a development build" and keeps them, and the confirmations, signals and purchases they send, out of production totals and rewards (sandbox).

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

클립보드 접근·Install Referrer 수신은 OS와 배포 환경에 영향을 받습니다. 복원이 항상 성공한다는 보장은 없습니다. `autoConfirmIfAvailable`는 첫 복원에 토큰이 없으면 소프트 상태를 저장하고 `success(null)`을 반환하며 서버 요청은 없다
이후 명시적 링크 열기는 아래 옵션 규칙으로 처리한다 기존 `showConfirmationIfNeeded`를 사용하는 호스트는 예·아니오 결정을 저장합니다. 늦게 끝난 복원은 이미 저장한 자동 귀속·대기 고객 ID·토큰을 덮어쓰지 않습니다.

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

재시도하지 않는 경우: 사용자가 **거절**한 경우, 이미 **완료(confirmed)**된 경우, 서버가 돌려준 **영구 오류(400/401/403/404/409/422)**. 이때 큐를 비우고 더 보내지 않습니다. **408/429/5xx·IO(네트워크) 오류**만 backoff로 재시도합니다. 연결·읽기 timeout은 각각 5초이며 요청 종료 시 연결을 해제합니다. 유효한 비어 있지 않은 leadId를 받은 뒤 완료로 저장합니다. 진행 중 요청과 표시 중 확인 창은 여러 SDK 인스턴스에서도 중복 시작하지 않고, 저장된 토큰·고객 ID를 바꿔 동의를 우회하지 않습니다. 취소는 아니오로 저장되며 이미 확정된 예를 덮어쓰지 않습니다. 이전 버전의 결정에는 예·아니오 구분이 없으므로 동의 증거 없이 retry 대상으로 승격하지 않습니다. 실제 기기의 네트워크 복구·설치 복원·결제 연결 QA는 남아 있습니다.

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
| 14 | `ConfirmationRejected` | 서버가 같은 고객의 다른 설치 확인을 거절(409). 앱을 다시 설치했거나 `appUserId`가 겹친 경우 | 불가 |

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

## 활동 신호와 `trackMilestone` (0.2.0)

개발자 서버가 없는 앱의 활성 사용자 보상을 위해 SDK가 확인된 소개 설치에 묶인 **하루 한 번의 활동 신호**를 Play Integrity 증명과 함께 보냅니다(서버 계약 `docs/SPEC.md` §29).

- **서버 플래그가 꺼져 있으면 아무것도 보내지 않습니다.** 먼저 `GET /track/signal-config`에 공개 키만 보내 기능 상태와 Cloud 프로젝트 번호를 받고(6시간 캐시), 켜져 있고 이 설치가 소개로 온 활성 보상 설치일 때만 challenge → Play Integrity 토큰 → 신호 순서로 보냅니다. `start`와 Activity 시작 때 자동으로 시도합니다.
- 토큰은 Standard API의 `requestHash`에 `base64url(SHA-256(challenge + "\n" + 마일스톤))`을 묶어 요청합니다. 서버가 같은 값을 다시 계산해 대조합니다.
- Play Integrity를 쓸 수 없거나 실패하면 보내지 않고 `Skipped(reason)`으로 끝납니다. 예외나 크래시는 없고 하루 시도는 3회로 제한합니다.
- `codefluence.trackMilestone("onboarded")`: 캠페인이 정한 완료 행동 이름을 서명된 신호에 실어 보냅니다(영문자·숫자·`_ . -` 40자 이하).
- 이 SDK는 `com.google.android.play:integrity`를 의존성으로 포함합니다. 앱은 Google Cloud에서 Play Integrity API를 켜고 Play Console에 연결해야 하며, 광고 ID는 쓰지 않습니다. Google Play 데이터 보안에 앱 사용 일수와 완료 행동 이름 수집(소개 설치에 연결, 추적 아님)을 반영하세요(항목별 표는 서버의 `/docs#store-privacy`).
- 공식 문서: https://developer.android.com/google/play/integrity/standard

## 광고 수익 보고 (`reportAdRevenue`, 0.2.0)

광고 수익 보상 캠페인을 쓰는 앱은 AdMob의 노출 단위 수익(paid event) 값을 SDK로 넘깁니다. SDK가 기기에서 합산해 **한 시간에 한 번 이하**로 묶어 보냅니다(설계 `docs/SPEC.md` §26, 서버 계약 §26.2).

```kotlin
// 광고 형식마다 광고 객체를 받자마자, 보여 주기 전에 리스너를 붙입니다.
ad.onPaidEventListener = OnPaidEventListener { adValue ->
    val precision = when (adValue.precisionType) {
        AdValue.PrecisionType.PRECISE -> CodefluenceAdValuePrecision.PRECISE
        AdValue.PrecisionType.ESTIMATED -> CodefluenceAdValuePrecision.ESTIMATED
        AdValue.PrecisionType.PUBLISHER_PROVIDED -> CodefluenceAdValuePrecision.PUBLISHER_PROVIDED
        else -> CodefluenceAdValuePrecision.UNKNOWN
    }
    codefluence.reportAdRevenue(adValue.valueMicros, adValue.currencyCode, precision)
}
```

- `UNKNOWN`(테스트 광고 등)은 노출 수만 세고 수익은 더하지 않습니다. 통화는 KRW·USD·JPY·EUR만 받고 그 밖의 값과 음수는 무시합니다.
- **서버가 광고 수익을 켜기 전에는 아무것도 보내지 않습니다.** 먼저 `GET /track/signal-config`에 공개 키만 보내 `adRevenue: true`인지 묻고(6시간 캐시), 아니면 값은 기기에 남습니다.
- 소개로 온 설치는 Play Integrity 증명과 함께 보냅니다. 증명의 요청 해시가 서버가 서명 대상으로 정한 `challenge\n통화,값,노출,미확정노출`을 덮으므로 값을 바꾸면 검증에 실패합니다. 증명이 통과한 값만 크리에이터 몫 계산에 쓰입니다. 소개 기간이 끝났거나 Play Integrity를 쓸 수 없는 환경에서는 **식별자 없이 합계만** 보내며 이 값은 지급 근거가 아니라 분모(앱 전체 SDK 합계)에만 쓰입니다.
- 보내는 것은 금액(마이크로 단위)·통화·노출 수뿐입니다. 광고 ID(AAID)는 쓰지 않습니다. 429는 한 시간 쉬었다가 다시 보내고, 실패한 묶음은 기기에 남겨 다음에 보냅니다. 앱 시작 때도 남은 값을 보내며 예외나 크래시를 내지 않습니다.
- Google Play 데이터 보안에 앱 광고 수익 금액 수집(소개 설치에 연결, 추적 아님)을 반영하세요.
- 공식 문서: https://developers.google.com/admob/android/impression-level-ad-revenue (Google Mobile Ads SDK 21.1.0 이상, AdMob 화면에서 노출 단위 수익 켜기 필요)

## 빌드

JDK 17 또는 21, Android SDK Platform 35 및 Build Tools 35가 필요합니다. `JAVA_HOME`과 `ANDROID_HOME`을 해당 설치 경로로 설정하거나 `local.properties`에 `sdk.dir`을 지정합니다. 로컬 검증은 Android Studio의 JDK 21로 수행했습니다.

```sh
./gradlew testReleaseUnitTest assembleRelease lintRelease --no-daemon
```

Gradle 8.9 wrapper는 공식 배포의 SHA-256을 확인합니다. 산출물은 `build/outputs/aar/codefluence-sdk-android-release.aar`입니다.

로컬 AAR 컴파일과 lint를 검증했습니다. 실제 Android 기기의 확인 시트, 붙여넣기·Install Referrer 복원, 네트워크 확인, 스토어 구매는 아직 검증하지 않았습니다.

JVM 단위 테스트는 실제 SDK가 사용하는 저장 결정 로직에 메모리 SharedPreferences를 주입하여 토큰 없는 소프트 상태 유지·명시적 링크 복원·null 응답, 늦은 복원·동의 경합·고객 ID 보존과 한·영 문구를 확인합니다. 공유 admission gate의 동시 호출·해제도 검증합니다. A3는 주입한 transport·scheduler·network monitor·executor로 backoff 계산·지터 범위·상태 분류, 재실행/네트워크 복구 재전송, 영구오류 큐 비움, 동일 `Idempotency-Key` 유지, 시도 상한, 수동 재시도의 backoff 초기화를 확인합니다. B6는 code 1~13 매핑·`IllegalStateException` 호환·`isRetryable`을, C3는 구독 응답 파싱·알 수 없는 event type 보존·404 및 저장값 없음 에러·로컬 토큰 대체를 확인합니다. Android UI, 실제 디스크 저장, Google Play 서비스, 실제 HTTP 요청을 실행하는 테스트는 아닙니다.

## 결제 보고 (`reportPurchases`)

RevenueCat 등 어떤 결제 SDK로 결제했든, 결제 성공 콜백에서 `codefluence.reportPurchases()`를 부르면 SDK가 자체 `BillingClient`로 INAPP·SUBS 구매를 읽어 아직 보고하지 않은 구매 토큰을 확인된 소개 정보(`leadId`, 설치 토큰)와 함께 `/track/purchase`로 보냅니다. 서버는 Google Play Developer API로 구매를 검증한 뒤 연결합니다. 앱 시작과 소개 확인 직후에도 자동으로 돕니다.

- Play Billing 라이브러리는 `compileOnly`이며 **7.x 이상**을 지원합니다. 인앱 결제를 파는 앱은 이미 포함하고 있으며, 없거나 6.x 이하면 보고를 건너뜁니다(앱을 멈추지 않습니다).
- Google은 활성 구독과 소모하지 않은 일회성 구매만 돌려줍니다. 소모성 상품은 결제 성공 콜백에서 구매를 직접 넘기세요(아래 「미전송 구매 큐」).
- iOS와 달리 서명된 계정 값이 없어, "이 기기의 구매"라는 연결은 SDK 보고에 기댑니다(SPEC §25.2 `reported_token`).
- 근거 문서: https://developer.android.com/reference/com/android/billingclient/api/BillingClient#queryPurchasesAsync(com.android.billingclient.api.QueryPurchasesParams,com.android.billingclient.api.PurchasesResponseListener)

## 미전송 구매 큐 (0.2.2)

0.2.1까지는 보내지 못한 구매를 기기에 저장하지 않고 다음 실행 때 `queryPurchasesAsync`를 다시 읽는 방식뿐이라, 소모성 구매가 소비(consume)되면 목록에 나오지 않아 첫 전송이 실패한 구매는 영구히 빠졌고 첫 전송 자체도 소비와 경쟁했습니다. 0.2.2는 구매 보고를 **기기 큐**에 먼저 넣고 서버가 2xx로 받을 때까지 다시 보냅니다. 서버는 소비된 구매 토큰도 Google에서 확인할 수 있습니다.

**다른 결제 SDK(RevenueCat 등)를 쓰는 앱은 결제 완료 콜백에서 구매를 직접 넘기는 호출을 써야 소모성 결제가 빠지지 않습니다.**

```kotlin
// Play Billing을 직접 쓰는 앱: onPurchasesUpdated에서 consume 전에 넘깁니다.
override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
    purchases?.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }?.forEach { purchase ->
        codefluence.purchaseCompleted(purchase, isSubscription = false)   // 구독 상품이면 true
        consume(purchase)
    }
}

// RevenueCat: onCompleted의 StoreTransaction에서 토큰을 넘깁니다.
Purchases.sharedInstance.purchase(params, object : PurchaseCallback {
    override fun onCompleted(storeTransaction: StoreTransaction, customerInfo: CustomerInfo) {
        storeTransaction.productIds.firstOrNull()?.let { productId ->
            codefluence.purchaseCompleted(storeTransaction.purchaseToken, productId,
                storeTransaction.type == ProductType.SUBS, storeTransaction.orderId)
        }
    }
    override fun onError(error: PurchasesError, userCancelled: Boolean) {}
})
```

- `purchaseCompleted(purchaseToken, productId, isSubscription, orderId = null, onResult = {})`와 Play Billing `Purchase`를 받는 확장 함수 `purchaseCompleted(purchase, isSubscription, onResult = {})`(Java: `CodefluenceBilling.purchaseCompleted(sdk, purchase, isSubscription)`). Play Billing은 `compileOnly`라 확장 함수는 별도 클래스에 두어 Billing이 없는 앱에서도 다른 클래스를 깨지 않습니다.
- 본문은 스토어 목록 보고와 같습니다: `purchaseToken`·`productId`·`kind`·확인된 `leadId`와 설치 토큰 `purchaseAccountToken`·`build`. 단건 `reportPurchase(purchase:)`도 0.2.2부터 같은 필드를 싣고 같은 큐를 거칩니다(서버의 `reported_token` 연결이 됩니다).
- 인자 없는 `purchaseCompleted()`·`reportPurchases()`는 그대로 쓸 수 있고, 소개로 온 설치에서는 읽은 구매를 같은 큐에 넣습니다.
- 큐에 넣는 것은 **소개로 온 설치**(확인 완료, 또는 받아들인 확인이 아직 서버에 닿지 않은 상태)뿐입니다. 소개와 무관한 설치는 아무것도 저장·전송하지 않습니다. 확인 전의 구매는 큐에서 기다렸다가 확인 직후 보내고, 소개가 사라지면(거절·영구 실패) 지웁니다.
- 저장 내용: 구매 토큰, 상품 ID, 구독 여부, 주문 ID, 처음 넣은 시각, 시도 횟수, 다음 시도 시각. 가격·사용자 정보는 저장하지 않습니다(lead와 설치 토큰은 보낼 때 채웁니다). 위치는 `noBackupFilesDir/codefluence-purchase-queue.json`이라 자동 백업·기기 이전으로 다른 기기에 복제되지 않습니다.
- 상한: 100건(넘으면 오래된 것부터 버림), 90일. 같은 토큰은 한 번만 들어가고, 이미 보고된 토큰은 다시 넣지 않습니다.
- 다시 보내는 때: 결제 직후, 앱 시작(`configure`), Activity 시작(`start`를 쓴 경우), 네트워크 복구, 그리고 실행 중이면 예약된 다음 시도 시각.

| 서버 응답 | 처리 |
|---|---|
| 2xx | 완료. 큐에서 지우고 보고 완료로 기록 |
| 네트워크 오류·408·429·5xx | 재시도(30초 시작·2배·상한 6시간·full jitter, `CodefluenceBackoffPolicy`) |
| 409 `credential_missing`(결제 조회 키 미등록) | 재시도(위와 같음). 개발자가 키를 등록하면 그때 받아들여집니다 |
| 401·403(키 폐기) | 보존하고 하루 간격으로만 다시 시도(새 키가 담긴 앱 업데이트 대기) |
| 400·404·422 등 그 밖의 4xx | 영구 거절. 큐에서 지움(목록에 남는 구매는 다음 목록 읽기 때 다시 보냄) |

확인 요청(`/track/confirm`) 큐는 409를 영구 실패로 보므로 분류가 다릅니다.

## 기존 무료 사용자 귀속 옵션

첫 실행에서 토큰이 없으면 `noTokenAtLaunch` 소프트 상태로 저장하고 서버 요청 없이 일반 시작으로 처리한다
이후 랜딩 버튼으로 앱을 열어 `handle(url:)`(iOS) 또는 `handle`(Android)에 전달한 서명 형식 토큰만 다시 확인한다
이 경로에서는 클립보드와 Install Referrer를 다시 읽지 않으며 서버가 HMAC 서명·제품·만료·클릭을 검증한다
명시적 아니오는 영구 유지하고 accepted·pending·confirmedLeadId가 있으면 새 링크로 덮어쓰지 않는다
이전 SDK의 `decisionMade=true, accepted=false`는 자동 무귀속과 명시 거절을 구분할 수 없으므로 영구 유지한다

확인 요청의 `launchKind`는 기본 `first_launch`, 소프트 상태의 링크 복원은 `later`다
`later`는 클릭 캠페인과 크리에이터 그룹의 현재 발효 조건 `includeExistingUsers=true`일 때만 lead를 만든다
기본값은 false이며 미허용 응답은 `200 { "leadId": null, "reason": "existing_users_not_included" }`다
SDK는 이 응답에서 대기 큐와 해당 링크를 지우고 소프트 상태로 돌아가며 자동 재전송하지 않는다
나중에 새 링크를 열면 다시 확인할 수 있다
`autoConfirmIfAvailable`는 성공한 일반 시작으로 nil/null을 반환하며, 반환형이 문자열인 iOS 호환 `confirm`은 성공 lead가 없으므로 오류를 반환하되 영구 거절을 저장하지 않는다

기존 무료 사용자의 첫 유료 전환만 새 귀속 대상이며 기존 유료 고객은 제외한다
스토어가 검증한 최초 구매 시각이 lead 생성보다 이르면 구매 이름표·고객 ID·원 거래 참조로 대조하여 `Conversion.exclusion=existing_customer`로 남기고 보상을 만들지 않는다
갱신은 원 거래의 최초 귀속과 첫 구매의 `termsId`를 유지한다
