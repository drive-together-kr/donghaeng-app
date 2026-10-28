package app.donghaeng.car.feature.creategroup

import app.donghaeng.car.core.common.Josa

/**
 * 그룹 생성 화면의 기본 그룹 이름 (docs/05 §5 "그룹 기본 이름", 2026-10-28).
 *
 * - 닉네임이 있으면 "{닉네임}{와|과} 같이 가기" — 조사는 [Josa.gwaWa](받침 있음 → 과, 없음 → 와; 숫자는 읽는 소리, 영문은 받침 없음).
 *   "아빠 차" → "아빠 차와 같이 가기", "엄마 차량" → "엄마 차량과 같이 가기", "EV6" → "EV6과 같이 가기", "DemoCarB" → "DemoCarB와 같이 가기".
 * - 닉네임이 비었거나, 붙인 결과가 서버 그룹 이름 한도([MAX_NAME_LENGTH]자, docs/03 §2.1)를 넘으면 "같이 가기".
 *   (닉네임을 중간에서 자르면 조사가 맞지 않고 어색해서 자르지 않는다.)
 *
 * 이 이름은 플로팅 패널 머리줄에 그대로 나온다 — 패널 폭에 안 들어가면 그룹 이름만 말줄임된다(docs/05 §5.2).
 */
object GroupNaming {
    /** 서버 `GroupService.MAX_NAME_LENGTH` 와 같다 */
    const val MAX_NAME_LENGTH = 30
    const val FALLBACK = "같이 가기"
    private const val SUFFIX = " 같이 가기"

    fun defaultName(nickname: String?): String {
        val nick = nickname?.trim().orEmpty()
        if (nick.isEmpty()) return FALLBACK
        val name = nick + Josa.gwaWa(nick) + SUFFIX
        return if (name.length <= MAX_NAME_LENGTH) name else FALLBACK
    }
}
