package app.donghaeng.car.feature.drive

/**
 * 대열 막대 배치 규칙(순수 함수 — JVM 단위 테스트, E036·4-1).
 *
 * - 막대 끝(km) = max(다음 정차지, 선두). 정차지를 모르면 선두 + 2 km. 정차지가 선두 뒤에 있어도(이미 지나침) 선두가 막대 끝에 잘리지 않는다.
 * - 화면에서 원 지름 + 여백보다 가까운 차들은 **한 묶음**으로 그린다 — 같은 주차장 출발(간격 0 m)·붙어 달리기에서 원·이름이 겹쳐
 *   "둘빠"처럼 깨지고 내 차 원이 가려지던 문제. 묶음 라벨은 2대면 "아빠·둘째", 3대 이상이면 "3대 함께".
 * - 묶음 안 원은 살짝 겹쳐 쌓고 **내 차를 맨 위(마지막)** 에 그린다.
 */
object FormationLayout {

    /** 정차지·선두 중 더 먼 쪽(km). 차가 없으면 0 기준 */
    fun endKm(cars: List<FormationCar>, nextStopKm: Float?): Float {
        val lead = cars.maxOfOrNull { it.offsetKm } ?: 0f
        return maxOf(nextStopKm ?: (lead + 2f), lead)
    }

    /**
     * 화면 x 좌표(왼→오 정렬 기준) 목록에서 이웃 간격이 [minGapPx] 보다 작은 것끼리 묶는다 — 결과는 입력 인덱스 묶음(왼쪽부터).
     * 체인처럼 이어지면(0·30·60 px, 최소 간격 40 px) 한 묶음이 된다.
     */
    fun clusters(xs: List<Float>, minGapPx: Float): List<List<Int>> {
        if (xs.isEmpty()) return emptyList()
        val order = xs.indices.sortedBy { xs[it] }
        val out = mutableListOf(mutableListOf(order.first()))
        for (k in 1 until order.size) {
            val prev = xs[order[k - 1]]
            val cur = xs[order[k]]
            if (cur - prev < minGapPx) out.last().add(order[k]) else out.add(mutableListOf(order[k]))
        }
        return out
    }

    /** 묶음 라벨 — 1대 = 이름 그대로, 2대 = 짧은 이름 "아빠·둘째", 3대 이상 = "N대 함께" */
    fun clusterLabel(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names.first()
        2 -> names.joinToString("·") { DriveFormat.panelName(it) }
        else -> "${names.size}대 함께"
    }

    /** 묶음 안 그리는 순서 — 내 차를 맨 마지막(맨 위)에 */
    fun drawOrder(cars: List<FormationCar>): List<FormationCar> = cars.sortedBy { it.isMe }

    /**
     * 묶음 안 원 중심 x — 묶음 가운데를 기준으로 [stepPx] 씩 좌우로 펼친다(살짝 겹친 스택). 1대면 [centerX] 그대로.
     */
    fun stackXs(count: Int, centerX: Float, stepPx: Float): List<Float> {
        if (count <= 1) return List(count) { centerX }
        val start = centerX - stepPx * (count - 1) / 2f
        return List(count) { start + stepPx * it }
    }

    // ---------------------------------------------------------------- 다음 정차지 라벨 자리(2026-11-02 사용자 지적: "덕평휴게소"·"아빠 차" 겹침)

    /** 가로 구간(px) — 이미 그린 라벨이 차지한 자리 */
    data class Span(val left: Float, val right: Float)

    /** 정차지 라벨 줄: 차 이름 줄 / 그 위 줄 / 막대 아래(간격·목적지) 줄 */
    enum class StopRow { NEAR, HIGH, BELOW }

    data class StopPlacement(val row: StopRow, val left: Float, val overlaps: Boolean)

    /**
     * 다음 정차지 라벨(아이콘 + 이름, 폭 [width])을 다른 라벨과 겹치지 않는 자리에 둔다.
     *
     * 1) 정차지 바로 위(차 이름 줄 [near])가 비면 거기 — 2) 막히면 한 줄 위([high]) — 3) 그것도 막히면 막대 아래([below], 간격·목적지 라벨).
     * 각 줄에서 [maxShiftPx] 안으로 옆으로 밀어 비는 자리를 먼저 찾는다. 4) 어느 줄도 그 안에 없으면 셋 중 가장 덜 밀리는 빈자리,
     * 5) 빈자리가 아예 없으면 [StopRow.HIGH] 의 원래 자리를 겹침 표시와 함께 돌려준다(그리는 쪽이 이름을 줄여 다시 시도).
     * [desiredLeft] 는 정차지 눈금 중심에 맞춘 왼쪽 끝, 결과 left 는 늘 [0, canvasWidth - width] 안.
     */
    fun placeStopLabel(
        desiredLeft: Float,
        width: Float,
        canvasWidth: Float,
        near: List<Span>,
        high: List<Span>,
        below: List<Span>,
        gapPx: Float,
        maxShiftPx: Float,
    ): StopPlacement {
        val (idx, left, overlaps) = placeLabel(desiredLeft, width, canvasWidth, listOf(near, high, below), gapPx, maxShiftPx, fallbackRow = 1)
        return StopPlacement(StopRow.entries[idx], left, overlaps)
    }

    /**
     * 일반형: [rows] 순서(앞이 우선)로 각 줄에서 [maxShiftPx] 안의 빈자리 → 없으면 가장 덜 밀리는 빈자리 → 그래도 없으면
     * [fallbackRow] 의 원래 자리(overlaps=true). 돌려주는 left 는 늘 [0, canvasWidth - width] 안.
     * 목적지 라벨(막대 오른쪽 끝)도 이것으로 간격 라벨과 겹치지 않게 둔다(2026-11-02 기기 캡처 "앞 2.5 km경포해변 14 km").
     */
    fun placeLabel(
        desiredLeft: Float,
        width: Float,
        canvasWidth: Float,
        rows: List<List<Span>>,
        gapPx: Float,
        maxShiftPx: Float,
        fallbackRow: Int = 0,
    ): Triple<Int, Float, Boolean> {
        val maxLeft = (canvasWidth - width).coerceAtLeast(0f)
        val want = desiredLeft.coerceIn(0f, maxLeft)
        fun free(left: Float, spans: List<Span>) = spans.none { left < it.right + gapPx && left + width > it.left - gapPx }
        fun best(spans: List<Span>): Float? {
            val candidates = buildList {
                add(want)
                spans.forEach { add(it.left - gapPx - width); add(it.right + gapPx) }
            }.map { it.coerceIn(0f, maxLeft) }.filter { free(it, spans) }
            return candidates.minByOrNull { kotlin.math.abs(it - want) }
        }
        val found = rows.mapIndexedNotNull { i, spans -> best(spans)?.let { i to it } }
        found.firstOrNull { kotlin.math.abs(it.second - want) <= maxShiftPx }?.let { return Triple(it.first, it.second, false) }
        found.minByOrNull { kotlin.math.abs(it.second - want) }?.let { return Triple(it.first, it.second, false) }
        return Triple(fallbackRow, want, true)
    }
}
