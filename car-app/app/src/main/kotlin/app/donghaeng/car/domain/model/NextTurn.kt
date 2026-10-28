package app.donghaeng.car.domain.model

/**
 * 순정 내비의 다음 회전 안내(TBT) 한 건 — NaviHelper `getTBTInfo()` → `onTBTInfo(List<TBTInfo>)` 의 첫 항목을 도메인 값으로 옮긴 것.
 * SDK 타입(`TBTInfo`·`TurnType`)은 어댑터 밖으로 나가지 않는다(CLAUDE.md 규칙 1).
 *
 * @param distanceM   다음 회전까지 남은 거리(m). SDK `TBTInfo.distance` 는 Double 이고 단위가 문서에 없어 **미터로 가정**한다
 *                    (`PleosNaviAdapter` 가 원값을 로그로 남긴다 — 기기 로그로 단위 확인 필요, docs/10 R16).
 * @param kind        회전 종류 이름(SDK `TurnType` 이름 그대로, 예: `TURN_RIGHT`) — 비교·로그용
 * @param description 내비가 주는 안내 문구(도로·방면) — 화면·로그에 쓰지 않고 '같은 회전인지' 비교에만 쓴다
 */
data class NextTurn(val distanceM: Int, val kind: String, val description: String)

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
