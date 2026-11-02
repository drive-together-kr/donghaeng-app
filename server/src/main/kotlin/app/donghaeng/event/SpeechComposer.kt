package app.donghaeng.event

import app.donghaeng.protocol.EnergyKind
import org.springframework.stereotype.Component

/**
 * 그룹 이벤트의 한국어 음성 문구 (docs/06 §4 템플릿). 차량 앱은 이 문구를 Android TTS 로 그대로 읽는다.
 *
 * T14: 그룹 수명주기 이벤트(합류·나가기·종료)와 동승자 메시지. T26: 차량 이벤트(저잔량·급제동·도착·정차/재출발·에어백·비상등). F16: 무전 알림.
 * 닉네임 조사는 [Josa] 로 고른다. 닉네임·메시지 본문은 호출자가 이미 정리(trim·길이 제한)한 값이다.
 */
@Component
class SpeechComposer {

    /** `{nickname}이 그룹에 들어왔어요.` */
    fun memberJoined(nickname: String): String = "${nickname}${Josa.iGa(nickname)} 그룹에 들어왔어요."

    /** `{nickname}이 그룹에서 나갔어요.` — 방장이 바뀌었으면 뒤에 `이제 {newLeader}이 선두예요.` */
    fun memberLeft(nickname: String, newLeaderNickname: String? = null): String {
        val base = "${nickname}${Josa.iGa(nickname)} 그룹에서 나갔어요."
        return if (newLeaderNickname == null) base else "$base ${leaderChanged(newLeaderNickname)}"
    }

    /** `이제 {nickname}이 선두예요.` (방장 위임) */
    fun leaderChanged(nickname: String): String = "이제 ${nickname}${Josa.iGa(nickname)} 선두예요."

    /** `{nickname}에서 온 메시지. {text}` (욕설 필터·60자 제한은 호출자 몫 — P1 T52) */
    fun passengerMessage(nickname: String, text: String): String = "${nickname}에서 온 메시지. $text"

    /**
     * `{nickname} 무전` (F16, docs/03 §5.4 `VOICE_MESSAGE`) — 받는 차는 이 짧은 알림 뒤 녹음을 재생한다(문구만 읽고 끝내지 않는다).
     * 녹음 내용은 서버가 해석하지 않으므로 문구에 담지 않는다.
     */
    fun voiceMessage(nickname: String): String = "$nickname 무전"

    /** 그룹 종료. 방장이 끝냈으면 `방장이 그룹을 종료했어요.`, 그 외 `그룹이 종료됐어요.` */
    fun groupEnded(reason: String): String = when (reason) {
        REASON_LEADER_ENDED -> "방장이 그룹을 종료했어요."
        else -> "그룹이 종료됐어요."
    }

    // ---------------------------------------------------------------- 차량 이벤트 (T26)

    /** `{nickname} 연료가 {pct}% 남았어요.` / EV: `{nickname} 배터리가 {pct}% 남았어요.` */
    fun lowEnergy(nickname: String, kind: EnergyKind, pct: Int): String =
        if (kind == EnergyKind.EV) "$nickname 배터리가 $pct% 남았어요." else "$nickname 연료가 $pct% 남았어요."

    /**
     * `앞쪽 {nickname}이 급제동했어요. 주의하세요.` — **급제동한 차보다 경로상 뒤에 있는 차**만 듣는 문구.
     * 대상은 서버가 정한다: [VehicleEventService] 가 스냅샷 `distanceToLeaderM` 으로 뒤차를 골라 `GroupEvent.audience` 에 싣고,
     * 차량 앱은 `EventAudience.isForMe` 로 자기 차가 audience 에 있을 때만 읽는다(앞차·급제동한 차 자신은 듣지 않는다, docs/03 §5.4).
     */
    fun hardBrake(nickname: String): String = "앞쪽 ${nickname}${Josa.iGa(nickname)} 급제동했어요. 주의하세요."

    /** `{nickname}이 {name}에 도착했어요.` */
    fun waypointArrived(nickname: String, name: String): String = "${nickname}${Josa.iGa(nickname)} ${name}에 도착했어요."

    /** `{nickname}이 먼저 도착했어요.` */
    fun destinationArrived(nickname: String): String = "${nickname}${Josa.iGa(nickname)} 먼저 도착했어요."

    /** `{nickname}이 잠시 멈췄어요.` */
    fun stopped(nickname: String): String = "${nickname}${Josa.iGa(nickname)} 잠시 멈췄어요."

    /** `{nickname}이 다시 출발했어요.` */
    fun resumed(nickname: String): String = "${nickname}${Josa.iGa(nickname)} 다시 출발했어요."

    /** `{nickname} 에어백이 터졌어요. 확인해 주세요.` */
    fun airbagDeployed(nickname: String): String = "$nickname 에어백이 터졌어요. 확인해 주세요."

    /** `선두차가 {name}을 경유지로 추가했어요. 따라갈까요?` / 경유지 없이 목적지만 바뀌면 `선두차가 목적지를 {dest}로 바꿨어요. 따라갈까요?` (T28) */
    fun routeFollowProposal(waypointName: String?, destinationName: String): String =
        if (!waypointName.isNullOrBlank()) "선두차가 ${waypointName}${Josa.eulReul(waypointName)} 경유지로 추가했어요. 따라갈까요?"
        else "선두차가 목적지를 ${destinationName}${Josa.euroRo(destinationName)} 바꿨어요. 따라갈까요?"

    /** `{nickname}이 비상등을 켰어요.` */
    fun hazardOn(nickname: String): String = "${nickname}${Josa.iGa(nickname)} 비상등을 켰어요."

    companion object {
        /** `GROUP_ENDED.reason` 값 (protocol `GroupEnded.reason` 과 같은 문자열) */
        const val REASON_LEADER_ENDED = "LEADER_ENDED"
        const val REASON_ALL_LEFT = "ALL_LEFT"
        const val REASON_ALL_ARRIVED = "ALL_ARRIVED"
    }
}
