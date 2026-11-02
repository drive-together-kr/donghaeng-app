package app.donghaeng.common.idempotency

import java.time.Duration
import java.time.Instant

/** 단위 테스트용 [IdempotencyStore] — 잠금 없이 맵에 둔다(동시성은 통합 테스트가 본다) */
class InMemoryIdempotencyStore : IdempotencyStore {
    private data class Row(val response: StoredResponse, val expiresAt: Instant)

    private val rows = mutableMapOf<Triple<String, String, String>, Row>()
    var lockCalls = 0

    val size: Int get() = rows.size

    override fun lockAndFind(callerId: String, path: String, key: String, now: Instant): StoredResponse? {
        lockCalls++
        return rows[Triple(callerId, path, key)]?.takeIf { it.expiresAt.isAfter(now) }?.response
    }

    override fun save(callerId: String, path: String, key: String, response: StoredResponse, now: Instant, ttl: Duration) {
        rows[Triple(callerId, path, key)] = Row(response, now.plus(ttl))
    }

    override fun deleteExpired(now: Instant): Int {
        val expired = rows.filterValues { !it.expiresAt.isAfter(now) }.keys
        expired.forEach { rows.remove(it) }
        return expired.size
    }
}
