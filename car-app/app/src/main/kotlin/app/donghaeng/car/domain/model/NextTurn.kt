package app.donghaeng.car.domain.model

import kotlin.math.roundToInt

/**
 * 순정 내비의 다음 회전 안내(TBT) 한 건 — NaviHelper `getTBTInfo()` → `onTBTInfo(List<TBTInfo>)` 의 첫 항목을 도메인 값으로 옮긴 것.
 * SDK 타입(`TBTInfo`·`TurnType`)은 어댑터 밖으로 나가지 않는다(CLAUDE.md 규칙 1).
 *
 * @param distanceM   다음 회전까지 남은 거리(m). SDK `TBTInfo.distance` 는 Double 이고 단위가 문서에 없어 **미터로 가정**한다
 *                    (`PleosNaviAdapter` 가 원값을 로그로 남긴다 — 기기 로그로 단위 확인 필요, docs/10 R16).
 * @param kind        회전 종류 이름(SDK `TurnType` 이름 그대로, 예: `TURN_RIGHT`) — 비교·로그용
 * @param description 내비가 주는 안내 문구(도로·방면) — 화면·로그에 쓰지 않고 '같은 회전인지' 비교에만 쓴다
 */
data class NextTurn(val distanceM: Int, val kind: String, val description: String) {
    companion object {
        /** SDK `TurnType` 의 첫 값(종류 코드를 모를 때) */
        const val KIND_UNKNOWN = "TURN_UNKNOWN"
    }
}

/**
 * TBT 첫 항목(회전 종류 이름·거리 m·문구) → [NextTurnResult] — 어댑터가 SDK 값을 꺼내 부른다(순수, `NextTurnTest`).
 * - 거리가 NaN·음수 → 모름.
 * - **자리표시 항목 → 모름:** 종류가 [NextTurn.KIND_UNKNOWN] 인데 거리 0 이거나 문구가 비었다, 또는 종류와 상관없이 거리 0·문구 없음.
 *   내비가 회전 정보를 아직 못 만들었을 때(안내 전·재탐색 중) 빈 목록 대신 이런 항목을 줄 수 있다 — 실제 회전(0 m)으로 보면 카드가 계속 미뤄지다
 *   120초 뒤 버려진다. '안내 없음'이 아니라 '모름'으로 두는 까닭: 미룬 카드가 있을 때 잠깐 나온 자리표시 하나로 회전 직전에 카드를 놓지 않게(모름은 3번 연달아 와야 놓는다).
 *   SDK 가 실제로 이런 항목을 주는지는 기기 로그(`navi tbt … type=… rawDistance=`)로 확인 필요(docs/10 R16).
 * - 그 밖 → [NextTurnResult.Ahead]. `TURN_UNKNOWN` 이어도 거리·문구가 있으면 내비가 보여 주는 실제 안내 항목(종류 코드만 모르는 것)으로 본다.
 */
fun nextTurnOf(kind: String, distanceM: Double, description: String): NextTurnResult {
    if (distanceM.isNaN() || distanceM < 0) return NextTurnResult.Unknown
    val zero = distanceM < 0.5 // 반올림하면 0 m
    val placeholder = (kind == NextTurn.KIND_UNKNOWN && (zero || description.isBlank())) || (zero && description.isBlank())
    if (placeholder) return NextTurnResult.Unknown
    return NextTurnResult.Ahead(NextTurn(distanceM = distanceM.roundToInt(), kind = kind, description = description))
}

/** [app.donghaeng.car.adapter.navi.NaviAdapter.nextTurn] 결과 — '회전 없음'과 '모름'을 구분한다 */
sealed interface NextTurnResult {
    /** 다음 회전이 있다 */
    data class Ahead(val turn: NextTurn) : NextTurnResult

    /** 내비가 빈 목록을 줬다 — 길 안내 중이 아니거나 안내가 끝났다 */
    data object NoGuidance : NextTurnResult

    /** 응답 없음(타임아웃)·미지원·실패 — 모른다 */
    data object Unknown : NextTurnResult
}

/** 로그용 짧은 표기(문구는 빼고 거리·종류만) */
fun NextTurnResult.toLogString(): String = when (this) {
    is NextTurnResult.Ahead -> "${turn.distanceM}m/${turn.kind}"
    NextTurnResult.NoGuidance -> "none"
    NextTurnResult.Unknown -> "unknown"
}
