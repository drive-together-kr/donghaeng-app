package app.donghaeng.car.feature.settings

/**
 * 숨은 메뉴 진입 탭 세기 (T32) — [windowMs] 안에 [required] 번 누르면 true(그리고 처음부터 다시 센다).
 * 설정 화면 제목에 붙는다. 주행 중 무시는 화면이 한다.
 */
class HiddenTapCounter(private val required: Int = REQUIRED_TAPS, private val windowMs: Long = WINDOW_MS) {
    private val taps = ArrayDeque<Long>()

    fun tap(nowMs: Long): Boolean {
        taps.addLast(nowMs)
        while (taps.isNotEmpty() && nowMs - taps.first() > windowMs) taps.removeFirst()
        if (taps.size >= required) {
            taps.clear()
            return true
        }
        return false
    }

    fun reset() = taps.clear()

    companion object {
        const val REQUIRED_TAPS = 5
        const val WINDOW_MS = 3_000L
    }
}
