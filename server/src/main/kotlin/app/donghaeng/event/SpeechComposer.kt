package app.donghaeng.event

import org.springframework.stereotype.Component

/**
 * 그룹 이벤트의 한국어 음성 문구 (docs/06 §4 템플릿). 차량 앱은 이 문구를 Android TTS 로 그대로 읽는다.
 *
 * T14 는 그룹 수명주기 이벤트(합류·나가기·종료)와 동승자 메시지만 다룬다. 차량 이벤트(저잔량·급제동 등)는 T26 에서 채운다.
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

    /** 그룹 종료. 방장이 끝냈으면 `방장이 그룹을 종료했어요.`, 그 외 `그룹이 종료됐어요.` */
    fun groupEnded(reason: String): String = when (reason) {
        REASON_LEADER_ENDED -> "방장이 그룹을 종료했어요."
        else -> "그룹이 종료됐어요."
    }

    companion object {
        /** `GROUP_ENDED.reason` 값 (protocol `GroupEnded.reason` 과 같은 문자열) */
        const val REASON_LEADER_ENDED = "LEADER_ENDED"
        const val REASON_ALL_LEFT = "ALL_LEFT"
        const val REASON_ALL_ARRIVED = "ALL_ARRIVED"
    }
}
