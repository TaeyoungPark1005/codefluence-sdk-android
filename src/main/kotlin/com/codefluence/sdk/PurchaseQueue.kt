package com.codefluence.sdk

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 서버가 아직 받지 않은 구매 보고 하나(0.2.2). 전송에 필요한 최소 정보만 담는다:
 * 구매 토큰·상품 ID·구독 여부·주문 ID와 재시도 상태. 가격·사용자 정보는 저장하지 않는다(서버가 Google에서 다시 읽는다).
 * lead와 설치 토큰은 보낼 때 저장된 확인 결과에서 채운다.
 */
data class QueuedPurchase(
    val purchaseToken: String,
    val productId: String,
    val subscription: Boolean,
    val orderId: String?,
    /** 처음 큐에 넣은 시각(ms). 90일이 지나면 버린다. */
    val enqueuedAt: Long,
    val attempts: Int = 0,
    /** 이 시각(ms) 전에는 자동으로 다시 보내지 않는다. */
    val nextAttemptAt: Long = enqueuedAt
) {
    internal fun toJson(): JSONObject = JSONObject().put("purchaseToken", purchaseToken).put("productId", productId)
        .put("subscription", subscription).put("enqueuedAt", enqueuedAt).put("attempts", attempts).put("nextAttemptAt", nextAttemptAt)
        .apply { orderId?.let { put("orderId", it) } }

    internal companion object {
        fun fromJson(value: JSONObject): QueuedPurchase? = runCatching {
            QueuedPurchase(value.getString("purchaseToken"), value.getString("productId"), value.getBoolean("subscription"),
                value.optString("orderId").takeIf { value.has("orderId") && it.isNotBlank() },
                value.getLong("enqueuedAt"), value.optInt("attempts", 0), value.optLong("nextAttemptAt", value.getLong("enqueuedAt")))
        }.getOrNull()
    }
}

/**
 * 구매 보고 응답 분류. 확인(confirm) 큐와 다르다: 409(`credential_missing`, 개발자가 결제 조회 키를 아직 등록하지 않음)는
 * 키가 등록되면 받아들여지므로 재시도 대상이고, 401·403(키 폐기)은 새 키가 담긴 앱 업데이트를 기다리며 긴 간격으로 보존한다.
 */
enum class PurchaseReportOutcome {
    ACCEPTED,    // 2xx. 큐에서 지우고 보고 완료로 기록한다.
    RETRY,       // 네트워크 오류·408·409·429·5xx. backoff로 다시 보낸다.
    RETRY_LATER, // 401·403. 긴 간격(하루)으로 보존한다.
    DROP;        // 400·404·422 등 영구 거절. 큐에서 지운다.

    companion object {
        fun forStatus(status: Int?): PurchaseReportOutcome = when (status) {
            null -> RETRY
            in 200..299 -> ACCEPTED
            401, 403 -> RETRY_LATER
            408, 409, 429 -> RETRY
            in 500..599 -> RETRY
            in 400..499 -> DROP
            else -> RETRY
        }
    }
}

/** 큐의 순수 규칙(상한·만료·중복). 저장과 전송은 [Codefluence]가 한다. */
object PurchaseQueueRules {
    const val MAX_RECORDS = 100
    const val MAX_AGE_MS = 90L * 24 * 3600 * 1000
    /** 401·403 뒤 다음 자동 시도까지의 간격. */
    const val REVOKED_KEY_INTERVAL_MS = 24L * 3600 * 1000
    /** 구매 큐 backoff: 30초 시작·2배·상한 6시간·full jitter. 횟수가 아니라 90일 만료로 멈춘다. */
    val backoff = CodefluenceBackoffPolicy(baseMillis = 30_000, factor = 2.0, capMillis = 6L * 3600 * 1000, maxAttempts = Int.MAX_VALUE)

    fun pruned(records: List<QueuedPurchase>, now: Long): List<QueuedPurchase> =
        records.filter { now - it.enqueuedAt < MAX_AGE_MS }.sortedBy { it.enqueuedAt }.takeLast(MAX_RECORDS)

    /** 같은 토큰이 이미 있거나 보고가 끝난 구매면 그대로 둔다. 새 구매는 바로 보낼 수 있는 상태로 넣는다. */
    fun enqueue(records: List<QueuedPurchase>, purchase: QueuedPurchase, now: Long, reported: Set<String>): List<QueuedPurchase> {
        if (purchase.purchaseToken in reported || records.any { it.purchaseToken == purchase.purchaseToken }) return pruned(records, now)
        return pruned(records + purchase, now)
    }

    fun nextAttempt(outcome: PurchaseReportOutcome, attempts: Int, now: Long, jitter: Double): Long =
        if (outcome == PurchaseReportOutcome.RETRY_LATER) now + REVOKED_KEY_INTERVAL_MS
        else now + backoff.delayMillis(attempts + 1, jitter)
}

/** 큐를 저장하는 곳. 기본은 `noBackupFilesDir`의 파일이라 자동 백업·기기 이전으로 다른 기기에 복제되지 않는다. */
interface PurchaseQueueStore {
    fun load(): List<QueuedPurchase>
    fun save(records: List<QueuedPurchase>)
}

/** JVM 테스트와 Context가 없는 경우용. */
class InMemoryPurchaseQueueStore : PurchaseQueueStore {
    private var records: List<QueuedPurchase> = emptyList()
    override fun load(): List<QueuedPurchase> = records
    override fun save(records: List<QueuedPurchase>) { this.records = records }
}

class FilePurchaseQueueStore(val file: File) : PurchaseQueueStore {
    /** 첫 읽기 뒤에는 메모리 사본을 쓴다(호출은 [Codefluence]의 큐 잠금 안에서만 일어난다). */
    private var cache: List<QueuedPurchase>? = null

    override fun load(): List<QueuedPurchase> {
        cache?.let { return it }
        val records = runCatching {
            if (!file.exists()) emptyList() else JSONArray(file.readText()).let { array ->
                (0 until array.length()).mapNotNull { QueuedPurchase.fromJson(array.getJSONObject(it)) }
            }
        }.getOrDefault(emptyList())
        cache = records
        return records
    }

    override fun save(records: List<QueuedPurchase>) {
        cache = records
        runCatching {
            if (records.isEmpty()) { file.delete(); return }
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(JSONArray(records.map { it.toJson() }).toString())
            if (!temporary.renameTo(file)) { file.delete(); check(temporary.renameTo(file)) { "rename failed" } }
        }.onFailure {
            // 저장 실패가 결제 흐름을 막으면 안 된다. 이번 실행의 메모리 상태로 계속하고 다음 저장에서 다시 시도한다.
            runCatching { android.util.Log.w("Codefluence", "purchase queue could not be saved: ${it.javaClass.simpleName}") }
        }
    }
}
