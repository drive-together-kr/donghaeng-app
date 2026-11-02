package app.donghaeng.car.domain.routesync

import app.donghaeng.car.domain.ptt.YesNo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * 주행 중 경로 제안 음성 질문(A034) — 질문을 **끝까지 읽은 뒤에만** 마이크를 연다(F16 무전 확인과 같은 순서). 배선은 di/SignalsModule
 * (VoiceAdapter.speakAndWait · RecognizerChain · PttController 상태 — 인식 체인은 B 배치 소유, 여기서는 부르기만 한다).
 *
 * null = 묻지 않았다(질문도 읽지 않음 → 엔진이 읽고 정차까지 보류): 음성 안내 꺼짐 / 무전이 쓰는 중(마이크를 다투지 않는다) /
 * 들을 수 있는 인식 단계가 없음(권한·모델·서비스) / **마이크를 열어도 소리가 담기지 않음**([micUsable] false — RA-4: API 30+ 에서 동행 화면이
 * 뒤에 있는데 그룹 서비스에 microphone 타입이 없으면 앱이 여는 마이크는 오류 없이 무음이다. 답을 해도 못 듣는 질문은 하지 않는다).
 * 듣는 사이 무전 버튼을 누르면 듣기를 바로 멈추고 [VoiceAnswer.NO_ANSWER].
 */
object VoiceRouteAsk {

    suspend fun ask(
        question: String,
        voiceEnabled: () -> Boolean,
        pttBusy: Flow<Boolean>,
        pttBusyNow: () -> Boolean,
        hasCandidate: () -> Boolean,
        micUsable: () -> Boolean,
        speakAndWait: suspend (String) -> Unit,
        listen: suspend () -> YesNo?,
    ): VoiceAnswer? {
        if (!voiceEnabled() || pttBusyNow() || !hasCandidate() || !micUsable()) return null
        speakAndWait(question)
        if (pttBusyNow()) return VoiceAnswer.NO_ANSWER
        val heard: kotlinx.coroutines.flow.Flow<YesNo?> = flow { emit(listen()) }
        val interrupted: kotlinx.coroutines.flow.Flow<YesNo?> = pttBusy.filter { it }.map { null }
        val answer = merge(heard, interrupted).first()
        return when (answer) {
            YesNo.YES -> VoiceAnswer.YES
            YesNo.NO -> VoiceAnswer.NO
            YesNo.NONE, null -> VoiceAnswer.NO_ANSWER
        }
    }
}
