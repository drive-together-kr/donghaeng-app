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
 * 카드를 미루고(음성은 VoiceAnnouncer 가 이미 읽었다) **회전 후**에 올린다.
 * - 미루기([hold]): 새 카드 직전에 읽은 TBT 가 [nearM] 이내([isNear]). 같은 종류는 최신 1장만 남긴다(같은 알림 id 로 교체되는 카드와 같은 규칙).
 * - 놓기([observe] — 미룬 동안 1초마다 다시 읽은 TBT 로): ① 거리 > [nearM] ② 회전 종류·문구가 바뀜 ③ 같은 종류·문구인데 거리가 [passedJumpM] 넘게
 *   늘어남(같은 문구가 이어지는 연속 회전) ④ TBT 없음(안내 끝) ⑤ 모름(타임아웃·미지원 — TBT 를 모르면 카드는 바로 뜬다는 규칙과 같다).
 * - 버리기: [maxDeferMs](120초) 넘게 미뤄진 카드 — 그만큼 지난 소식은 음성으로 이미 들었고 카드로 띄우기엔 늦다.
 */
class TurnCardGate(
    private val nearM: Int = NEAR_M,
    private val maxDeferMs: Long = MAX_DEFER_MS,
    private val passedJumpM: Int = PASSED_JUMP_M,
) {
    /** 미룬 카드 한 장 — [sinceMs] 는 처음 미룬 시각(같은 종류로 바뀌면 새 카드의 시각) */
    data class Held(val card: EventCard, val event: ReceivedGroupEvent, val sinceMs: Long)

    /** TBT 를 한 번 읽고 정리한 결과 — [reason] 은 놓은 이유(놓은 게 없으면 null): far / turn-changed / distance-jump / no-guidance / unknown */
    data class Update(val released: List<Held>, val expired: List<Held>, val reason: String?)

    private val held = LinkedHashMap<String, Held>()

    /** 지금 붙잡고 있는 회전(가장 최근에 읽은 [nearM] 이내 TBT) — 다음 읽기와 비교해 '회전 후'를 판단한다 */
    private var reference: NextTurn? = null

    /** 마지막으로 읽은 TBT(디버그 `surface` 한 줄) */
    var lastReading: NextTurnResult? = null
        private set

    val heldCount: Int get() = held.size

    fun heldTypes(): List<String> = held.keys.toList()

    fun isNear(reading: NextTurnResult): Boolean = reading is NextTurnResult.Ahead && reading.turn.distanceM <= nearM

    /**
     * 새로 읽은 TBT 반영: 오래된 카드는 버리고, 회전을 지났으면 미룬 카드를 모두 놓는다. 그 뒤 [reading] 이 [nearM] 이내면 그 회전을 붙잡는다.
     * 새 카드가 왔을 때도 먼저 이걸 부른다 — 이미 미뤄 둔 카드가 새 카드보다 먼저 나가게.
     */
    fun observe(reading: NextTurnResult, nowMs: Long): Update {
        lastReading = reading
        val expired = held.values.filter { nowMs - it.sinceMs > maxDeferMs }
        expired.forEach { held.remove(it.card.type) }
        val reason = passedReason(reading)
        val released = if (reason != null && held.isNotEmpty()) held.values.toList().also { held.clear() } else emptyList()
        reference = (reading as? NextTurnResult.Ahead)?.turn?.takeIf { it.distanceM <= nearM }
        return Update(released, expired, reason.takeIf { released.isNotEmpty() })
    }

    /** 카드를 미룬다(호출 쪽이 [isNear] 를 먼저 확인). 같은 종류가 있으면 바꾸고 바뀐 옛 카드를 돌려준다 */
    fun hold(card: EventCard, event: ReceivedGroupEvent, nowMs: Long): Held? = held.put(card.type, Held(card, event, nowMs))

    /** 놓았지만 아직 못 올린 카드(빈도 제한 burst)를 다시 넣는다 — 처음 미룬 시각 유지, 그새 같은 종류가 들어왔으면 새 것을 둔다 */
    fun putBack(h: Held) {
        held.putIfAbsent(h.card.type, h)
    }

    /** 모두 비운다(모드가 바뀌어 카드를 안 쓰게 됐을 때) — 비운 카드 */
    fun clear(): List<Held> = held.values.toList().also { held.clear(); reference = null }

    private fun passedReason(reading: NextTurnResult): String? = when (reading) {
        NextTurnResult.Unknown -> "unknown"
        NextTurnResult.NoGuidance -> "no-guidance"
        is NextTurnResult.Ahead -> {
            val t = reading.turn
            val ref = reference
            when {
                t.distanceM > nearM -> "far"
                ref == null -> null // 새로 가까워진 회전 — 붙잡는다
                t.kind != ref.kind || t.description != ref.description -> "turn-changed"
                t.distanceM > ref.distanceM + passedJumpM -> "distance-jump"
                else -> null // 같은 회전에 다가가는 중
            }
        }
    }

    companion object {
        /** 이 거리 안에 회전이 있으면 카드를 미룬다(사용자 지시 300 m) */
        const val NEAR_M = 300

        /** 이보다 오래 미뤄진 카드는 버린다 */
        const val MAX_DEFER_MS = 120_000L

        /** 같은 종류·문구인데 거리가 이만큼 넘게 늘면 앞 회전을 지난 것으로 본다(내비 거리 흔들림보다 크게) */
        const val PASSED_JUMP_M = 50

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
