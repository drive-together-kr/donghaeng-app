package app.donghaeng.car.domain.model

/**
 * 음성 안내 우선순위 (docs/05 §3 VoiceAdapter, VoiceAnnouncer 큐에서 사용).
 *
 * - LOW      : 멤버 합류·정차/재출발 같은 정보성
 * - NORMAL   : 저잔량·휴게소 제안
 * - HIGH     : 급제동·이탈
 * - CRITICAL : 에어백 전개 — 재생 중인 안내를 끊고 즉시 말한다
 */
enum class Priority { LOW, NORMAL, HIGH, CRITICAL }
