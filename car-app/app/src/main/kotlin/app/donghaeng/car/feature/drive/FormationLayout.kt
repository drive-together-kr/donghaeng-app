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
}
