package app.donghaeng.car.adapter.sdk

import kotlinx.coroutines.flow.StateFlow

/**
 * Pleos SDK 존재 점검 결과 한 줄.
 *
 * @param name      점검 항목(예: "Vehicle 2.3.2")
 * @param available 클래스가 classpath 에 있는가
 * @param detail    찾은 클래스 이름 또는 실패 사유
 */
data class ProbeLine(val name: String, val available: Boolean, val detail: String)

/**
 * 앱 시작 시 한 번 실행하는 SDK 점검 (T04).
 *
 * - pleos flavor: `PleosSdkProbe` — Vehicle / NaviHelper / FusedLocation 진입 클래스를 `Class.forName` 으로 확인해 로그로 남긴다.
 * - fake flavor : `NoSdkProbe` — SDK 가 없음을 한 줄로 알린다.
 *
 * 결과는 홈 화면 디버그 카드에 그대로 표시한다(사용자에게 막연한 수동 확인을 시키지 않기 위한 확인용 화면, CLAUDE.md 작업 방식).
 */
interface SdkProbe {
    /** null = 아직 실행 전 */
    val report: StateFlow<List<ProbeLine>?>

    /** 한 번만 실행한다. 두 번째 호출부터는 무시. */
    fun runOnce()
}
