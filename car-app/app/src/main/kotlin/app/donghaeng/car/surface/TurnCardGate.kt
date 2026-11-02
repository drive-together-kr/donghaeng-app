package app.donghaeng.car.surface

import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.model.NextTurn
import app.donghaeng.car.domain.model.NextTurnResult
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * 알림 카드 TBT 게이트 (docs/05 §5.2, 사용자 지시 2026-10-28) — 순수 클래스(시계·TBT 조회·게시는 [EventCardNotifier] 가 넘긴다, `TurnCardGateTest`).
 *
 * 헤드업 카드는 순정 내비 상단의 회전 안내(TBT) 위에 겹친다. 그래서 '카드 + 음성' 모드에서도 다음 회전까지 [nearM](300 m) 이내면
 * 카드를 미루고(음성은 VoiceAnnouncer 가 이미 읽었다) **회전 후**에 올린다. 규칙의 기준은 '지금 읽은 다음 회전이 300 m 안인가' 하나다.
 * - 미루기([hold]): 새 카드 직전에 읽은 TBT 가 [nearM] 이내([isNear])이거나, 이미 미룬 카드가 있다(줄 끝에 선다). 같은 종류는 최신 1장만 남긴다
 *   (같은 알림 id 로 교체되는 카드와 같은 규칙).
 * - 놓기([observe] — 미룬 동안 1초마다 다시 읽은 TBT 로): ① 다음 회전이 [nearM] 밖(far) ② TBT 없음(안내 끝, no-guidance)
 *   ③ 모름이 [unknownReleaseCount]번 연달아(unknown — 한 번 늦은 응답으로 회전 직전에 카드가 뜨지 않게. 처음 판단에서 모르면 바로 띄우는 규칙과는 따로다).
 * - 회전을 지났어도 **바뀐 다음 회전이 다시 [nearM] 안이면 계속 붙잡는다**(연속 회전 — 놓으면 그 회전의 TBT 를 가린다). 이때 [Update.turnChanged] 로 알린다(로그용).
 *   연속 회전이 길게 이어지면 아래 120초 폐기가 막는다.
 * - 버리기: [maxDeferMs](120초) 넘게 미뤄진 카드 — 그만큼 지난 소식은 음성으로 이미 들었고 카드로 띄우기엔 늦다.
 *   위험 카드(급제동·에어백)는 [dangerMaxDeferMs](기본 = [maxDeferMs], 앱 DI 15초) 뒤 버린다 — 지난 급제동이 회전 뒤 늦게 떠 헷갈리지 않게(B067).
 */
class TurnCardGate(
    private val nearM: Int = NEAR_M,
    private val maxDeferMs: Long = MAX_DEFER_MS,
    private val unknownReleaseCount: Int = UNKNOWN_RELEASE_COUNT,
    private val dangerMaxDeferMs: Long = maxDeferMs,
) {
    /** 미룬 카드 한 장 — [sinceMs] 는 처음 미룬 시각(같은 종류로 바뀌면 새 카드의 시각) */
    data class Held(val card: EventCard, val event: ReceivedGroupEvent, val sinceMs: Long)

    /**
     * TBT 를 한 번 읽고 정리한 결과 — [reason] 은 놓은 이유(놓은 게 없으면 null): far / no-guidance / unknown.
     * [turnChanged] = 붙잡던 회전이 다른 회전으로 바뀌었지만 그 회전도 [nearM] 안이라 계속 붙잡는다(연속 회전, 로그용).
     */
    data class Update(val released: List<Held>, val expired: List<Held>, val reason: String?, val turnChanged: Boolean = false)

    private val held = LinkedHashMap<String, Held>()

    /** 지금 붙잡고 있는 회전(가장 최근에 읽은 [nearM] 이내 TBT) — 바뀌었는지는 로그에만 쓴다 */
    private var reference: NextTurn? = null

    /** 연달아 '모름'을 읽은 횟수 — 다른 값을 읽으면 0 */
    private var unknownStreak = 0

    /** 마지막으로 읽은 TBT(디버그 `surface` 한 줄) */
    var lastReading: NextTurnResult? = null
        private set

    val heldCount: Int get() = held.size

    fun heldTypes(): List<String> = held.keys.toList()

    /** [eventId] 카드가 아직 미뤄져 있는지 */
    fun isHolding(eventId: String): Boolean = held.values.any { it.event.event.eventId == eventId }

    fun isNear(reading: NextTurnResult): Boolean = reading is NextTurnResult.Ahead && reading.turn.distanceM <= nearM

    /**
     * 새로 읽은 TBT 반영: 오래된 카드는 버리고, 놓을 조건(far·no-guidance·연속 unknown)이면 미룬 카드를 모두(미룬 순서대로) 놓는다.
     * 새 카드가 왔을 때는 [hold] 로 줄에 세운 뒤 이걸 부른다 — 이미 미뤄 둔 카드가 새 카드보다 먼저 나가게.
     */
    fun observe(reading: NextTurnResult, nowMs: Long): Update {
        lastReading = reading
        unknownStreak = if (reading == NextTurnResult.Unknown) unknownStreak + 1 else 0
        val expired = held.values.filter { nowMs - it.sinceMs > limitOf(it) }
        expired.forEach { held.remove(it.card.type) }
        val reason = releaseReason(reading)
        val turnChanged = reason == null && held.isNotEmpty() && changedTurn(reading)
        val released = if (reason != null && held.isNotEmpty()) held.values.toList().also { held.clear() } else emptyList()
        reference = when (reading) {
            is NextTurnResult.Ahead -> reading.turn.takeIf { it.distanceM <= nearM }
            NextTurnResult.Unknown -> reference // 모르는 동안은 붙잡던 회전을 그대로 기억한다
            NextTurnResult.NoGuidance -> null
        }
        return Update(released, expired, reason.takeIf { released.isNotEmpty() }, turnChanged)
    }

    /** 카드를 미룬다. 같은 종류가 있으면 그 자리에서 바꾸고 바뀐 옛 카드를 돌려준다 */
    fun hold(card: EventCard, event: ReceivedGroupEvent, nowMs: Long): Held? = held.put(card.type, Held(card, event, nowMs))

    /** 놓았지만 아직 못 올린 카드(빈도 제한 burst)를 다시 넣는다 — 처음 미룬 시각 유지, 그새 같은 종류가 들어왔으면 새 것을 둔다 */
    fun putBack(h: Held) {
        held.putIfAbsent(h.card.type, h)
    }

    /** 모두 비운다(모드가 바뀌어 카드를 안 쓰게 됐을 때) — 비운 카드 */
    fun clear(): List<Held> = held.values.toList().also { held.clear(); reference = null; unknownStreak = 0 }

    /** 지금 미룬 카드(미룬 순서) */
    fun heldList(): List<Held> = held.values.toList()

    /** [type] 카드 하나를 줄에서 뺀다(위험 카드를 먼저 놓을 때) — 없으면 null */
    fun take(type: String): Held? = held.remove(type)

    private fun limitOf(h: Held): Long = if (EventHighlightPolicy.isDanger(h.card.type)) dangerMaxDeferMs else maxDeferMs

    private fun releaseReason(reading: NextTurnResult): String? = when (reading) {
        NextTurnResult.Unknown -> "unknown".takeIf { unknownStreak >= unknownReleaseCount }
        NextTurnResult.NoGuidance -> "no-guidance"
        is NextTurnResult.Ahead -> "far".takeIf { reading.turn.distanceM > nearM }
    }

    private fun changedTurn(reading: NextTurnResult): Boolean {
        val ref = reference ?: return false
        val t = (reading as? NextTurnResult.Ahead)?.turn ?: return false
        return t.kind != ref.kind || t.description != ref.description
    }

    companion object {
        /** 이 거리 안에 회전이 있으면 카드를 미룬다(사용자 지시 300 m) */
        const val NEAR_M = 300

        /** 이보다 오래 미뤄진 카드는 버린다 */
        const val MAX_DEFER_MS = 120_000L

        /** 위험 카드(급제동·에어백)를 미루는 상한(앱 DI) — 넘으면 버린다(B067) */
        const val DANGER_MAX_DEFER_MS = 15_000L

        /**
         * 미룬 카드가 있을 때 '모름'이 이만큼 연달아 오면 놓는다(재확인 1초 + 조회 최대 0.8 s → 약 3~5초).
         * 한 번의 타임아웃(내비 재탐색·바인더 지연)으로 회전 직전에 카드가 뜨지 않게 하면서, 내비가 아예 답을 못 하면 카드를 너무 오래 잡아 두지 않는다.
         */
        const val UNKNOWN_RELEASE_COUNT = 3

        /** 미룬 동안 TBT 를 다시 묻는 간격 */
        const val RECHECK_MS = 1_000L
    }
}

/**
 * 카드 게이트가 묻는 '다음 회전' (docs/05 §5.2) — [lookup](`NaviAdapter.nextTurn`)을 [timeoutMs] 안에 묻는다. 예외·시간 초과 = [NextTurnResult.Unknown].
 *
 * 디버그 `tbt` 액션(debug 빌드의 DemoControlReceiver)이 [setOverride] 로 결과를 잠시 덮어쓴다 — Pleos AVD 의 순정 내비는 위치가 고정돼 TBT 가
 * 늘 같은 값이라, 회전 직전(≤ 300 m) → 회전 후(> 300 m)를 기기에서 보이려면 값을 바꿔 줘야 한다. 덮어쓴 값은 종류 `DEBUG`·문구 고정.
 */
class TurnProbe(
    private val lookup: suspend (timeoutMs: Long) -> NextTurnResult,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = LOOKUP_TIMEOUT_MS,
) {
    private data class Override(val distanceM: Int, val untilMs: Long)

    @Volatile private var override: Override? = null

    @Volatile var lookups: Int = 0
        private set

    /** [distanceM] 을 [forMs] 동안 다음 회전 거리로 돌려준다 */
    fun setOverride(distanceM: Int, forMs: Long) {
        override = Override(distanceM.coerceAtLeast(0), clock() + forMs.coerceAtLeast(0))
        DhLog.i("surface: tbt override=${distanceM}m for=${forMs / 1000}s")
    }

    fun clearOverride() {
        override = null
        DhLog.i("surface: tbt override 해제")
    }

    suspend fun read(): NextTurnResult {
        override?.let { o ->
            if (clock() < o.untilMs) return NextTurnResult.Ahead(NextTurn(o.distanceM, OVERRIDE_KIND, OVERRIDE_DESCRIPTION))
            override = null
            DhLog.i("surface: tbt override 끝 — 내비 값으로")
        }
        lookups++
        return try {
            // 어댑터도 timeoutMs 를 지키지만 SDK 쪽이 막혀도 카드가 멈추지 않게 한 번 더 감싼다
            withTimeoutOrNull(timeoutMs + GUARD_MS) { lookup(timeoutMs) } ?: NextTurnResult.Unknown
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DhLog.d("surface: tbt 조회 실패 ${e.javaClass.simpleName}")
            NextTurnResult.Unknown
        }
    }

    fun describe(): String {
        val o = override
        val ov = if (o == null) "none" else "${o.distanceM}m/${((o.untilMs - clock()).coerceAtLeast(0) + 999) / 1000}s"
        return "override=$ov lookups=$lookups"
    }

    companion object {
        /** NaviHelper 응답 대기 — 카드가 늦어지는 최대 시간이기도 하다 */
        const val LOOKUP_TIMEOUT_MS = 800L
        const val GUARD_MS = 200L
        const val OVERRIDE_KIND = "DEBUG"
        const val OVERRIDE_DESCRIPTION = "debug-override"
    }
}
