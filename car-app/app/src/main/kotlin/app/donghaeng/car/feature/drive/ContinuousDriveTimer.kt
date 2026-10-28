package app.donghaeng.car.feature.drive

/**
 * 연속 주행 시간(docs/05 §5.1 '내 주행' 줄) — 주행 구간만 더하고, [restResetMs](15분) 이상 정차하면 0 으로 되돌린다.
 *
 * - 짧은 정차(신호 대기·잠깐 멈춤)는 시간을 더하지도 지우지도 않는다.
 * - 2026-10-28 실측: 예전 규칙(그룹 합류 시각부터 경과)은 밤새 이어진 시연 그룹에서 정차 중인데도 "연속 주행 18시간 27분 · 휴식 권장"을 띄웠다.
 * - 앱 프로세스가 새로 뜨면 0 부터 센다(기기에 남기지 않는다).
 * - 순수 클래스 — 시각은 부르는 쪽이 넘긴다(단위 테스트는 가짜 시각).
 */
class ContinuousDriveTimer(private val restResetMs: Long = REST_RESET_MS) {
    private var accumulatedMs = 0L
    private var drivingSinceMs: Long? = null
    private var parkedSinceMs: Long? = null

    /** 주행 여부가 바뀔 때(같은 값이 다시 와도 된다) 부른다 */
    @Synchronized
    fun onDriving(driving: Boolean, nowMs: Long) {
        if (driving) {
            if (drivingSinceMs != null) return
            parkedSinceMs?.let { if (nowMs - it >= restResetMs) accumulatedMs = 0L }
            parkedSinceMs = null
            drivingSinceMs = nowMs
        } else {
            drivingSinceMs?.let { accumulatedMs += (nowMs - it).coerceAtLeast(0L) }
            drivingSinceMs = null
            if (parkedSinceMs == null) parkedSinceMs = nowMs
        }
    }

    /** 지금까지의 연속 주행(ms). 정차한 지 [restResetMs] 이상이면 0 */
    @Synchronized
    fun drivenMs(nowMs: Long): Long {
        drivingSinceMs?.let { return accumulatedMs + (nowMs - it).coerceAtLeast(0L) }
        val parked = parkedSinceMs
        return if (parked != null && nowMs - parked >= restResetMs) 0L else accumulatedMs
    }

    companion object {
        /** 이만큼 쉬면 연속 주행을 0 으로 — 장거리 운전 권장 휴식(2시간 주행 · 15분 휴식) */
        const val REST_RESET_MS = 15 * 60_000L
    }
}
