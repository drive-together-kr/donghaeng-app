package app.donghaeng.car.feature.drive

import org.junit.Assert.assertEquals
import org.junit.Test

/** 대열 막대 배치(E036·4-1): 막대 끝·묶음·묶음 라벨·그리는 순서 */
class FormationLayoutTest {

    private fun car(name: String, km: Float, me: Boolean = false) = FormationCar(name, 0xFF2E7DFFL, offsetKm = km, isMe = me)

    @Test
    fun `bar end is the farther of the next stop and the leader - a stop behind the leader does not clip the leader`() {
        val cars = listOf(car("둘째 차", 0f), car("아빠 차", 6.9f))
        assertEquals("정차지 없으면 선두 + 2 km", 8.9f, FormationLayout.endKm(cars, null), 0.001f)
        assertEquals("정차지가 앞이면 정차지", 23.7f, FormationLayout.endKm(cars, 23.7f), 0.001f)
        assertEquals("정차지를 이미 지났으면 선두", 6.9f, FormationLayout.endKm(cars, 4.0f), 0.001f)
        assertEquals(2f, FormationLayout.endKm(emptyList(), null), 0.001f)
    }

    @Test
    fun `cars closer than the minimum gap on screen form one cluster, chains included`() {
        // 같은 주차장(같은 x) 두 대 + 멀리 한 대
        assertEquals(listOf(listOf(0, 1), listOf(2)), FormationLayout.clusters(listOf(100f, 100f, 600f), 46f))
        // 0·30·60 px 은 이웃끼리 40 px 안이라 한 묶음(체인)
        assertEquals(listOf(listOf(0, 1, 2)), FormationLayout.clusters(listOf(0f, 30f, 60f), 40f))
        // 정렬되지 않은 입력도 x 순서로 묶는다
        assertEquals(listOf(listOf(1), listOf(0, 2)), FormationLayout.clusters(listOf(500f, 10f, 520f), 46f))
        assertEquals(emptyList<List<Int>>(), FormationLayout.clusters(emptyList(), 46f))
    }

    @Test
    fun `cluster label - one name, two short names, three or more as a count`() {
        assertEquals("아빠 차", FormationLayout.clusterLabel(listOf("아빠 차")))
        assertEquals("아빠·둘째", FormationLayout.clusterLabel(listOf("아빠 차", "둘째 차")))
        assertEquals("3대 함께", FormationLayout.clusterLabel(listOf("아빠 차", "둘째 차", "삼촌 차")))
    }

    @Test
    fun `my car is drawn last so it stays on top, stacked circles spread around the cluster centre`() {
        val order = FormationLayout.drawOrder(listOf(car("내 차", 0f, me = true), car("둘째 차", 0f), car("아빠 차", 0f)))
        assertEquals("내 차", order.last().name)
        assertEquals(listOf(80f, 100f, 120f), FormationLayout.stackXs(3, 100f, 20f))
        assertEquals(listOf(100f), FormationLayout.stackXs(1, 100f, 20f))
    }

    // ---- 다음 정차지 라벨 자리(2026-11-02 사용자 캡처: "덕평휴게소" 와 "아빠 차" 가 같은 줄에 겹침)

    private fun place(want: Float, near: List<FormationLayout.Span>, high: List<FormationLayout.Span> = emptyList(), below: List<FormationLayout.Span> = emptyList(), w: Float = 150f) =
        FormationLayout.placeStopLabel(want, w, 1000f, near, high, below, gapPx = 12f, maxShiftPx = 120f)

    @Test
    fun `stop label stays on the name row above its tick when nothing is there`() {
        val p = place(400f, near = listOf(FormationLayout.Span(100f, 180f)))
        assertEquals(FormationLayout.StopRow.NEAR, p.row)
        assertEquals(400f, p.left, 0.01f)
        assertEquals(false, p.overlaps)
    }

    @Test
    fun `user capture - car label right under the stop moves the stop label one row up, not on top of it`() {
        // 휴게소 눈금 x≈820, "아빠 차" 라벨이 같은 x 에 — 예전엔 둘 다 막대 위 같은 높이에 그려져 글자가 겹쳤다
        val car = FormationLayout.Span(780f, 880f)
        val p = place(745f, near = listOf(car), below = listOf(FormationLayout.Span(820f, 1000f)))
        assertEquals(FormationLayout.StopRow.HIGH, p.row)
        assertEquals(745f, p.left, 0.01f)
        val stop = FormationLayout.Span(p.left, p.left + 150f)
        // 같은 줄이 아니므로 겹침 없음 — 그리고 이름 줄에 두었다면 겹쳤을 것(시나리오가 실제로 충돌함을 확인)
        assert(stop.left < car.right && stop.right > car.left)
    }

    @Test
    fun `both rows above blocked goes below the bar, avoiding gap and destination labels`() {
        val p = place(400f, near = listOf(FormationLayout.Span(300f, 560f)), high = listOf(FormationLayout.Span(350f, 560f)),
            below = listOf(FormationLayout.Span(820f, 1000f)))
        assertEquals(FormationLayout.StopRow.BELOW, p.row)
        assertEquals(400f, p.left, 0.01f)
    }

    @Test
    fun `small sideways shift on the name row wins over changing rows`() {
        // 왼쪽 라벨 끝이 원하는 자리와 40 px 겹침 → 같은 줄에서 오른쪽으로 52 px 밀면(간격 12) 빈다
        val p = place(400f, near = listOf(FormationLayout.Span(300f, 440f)))
        assertEquals(FormationLayout.StopRow.NEAR, p.row)
        assertEquals(452f, p.left, 0.01f)
    }

    @Test
    fun `result never leaves the canvas and reports overlap when every row is full`() {
        val full = listOf(FormationLayout.Span(0f, 1000f))
        val p = place(950f, near = full, high = full, below = full)
        assertEquals(true, p.overlaps)
        assertEquals(850f, p.left, 0.01f) // 1000 - 150 로 잘림
        val q = place(-50f, near = emptyList())
        assertEquals(0f, q.left, 0.01f)
    }

    @Test
    fun `device capture - destination label slides left of the gap label under the leading car instead of touching it`() {
        // 막대 아래: 선두 아래 "앞 2.5 km"(1590..1700), 목적지 "경포해변 14 km"(폭 150)는 오른쪽 끝(1850)에 붙으려 함
        val gap = FormationLayout.Span(1590f, 1700f)
        val (row, left, overlaps) = FormationLayout.placeLabel(1850f, 150f, 2000f, listOf(listOf(gap), emptyList(), emptyList()), 12f, 160f)
        assertEquals(0, row)
        assertEquals(1850f, left, 0.01f) // 오른쪽 끝은 이미 비어 있음 — 그대로
        assertEquals(false, overlaps)
        // 간격 라벨이 오른쪽 끝까지 차 있으면(1760..1900) 같은 줄 왼쪽으로 밀거나(차이 ≤ 160) 위 줄로
        val wide = FormationLayout.Span(1760f, 1900f)
        val (row2, left2, _) = FormationLayout.placeLabel(1850f, 150f, 2000f, listOf(listOf(wide), emptyList(), emptyList()), 12f, 160f)
        val r = left2 + 150f
        assert(row2 != 0 || r <= wide.left - 12f || left2 >= wide.right + 12f) { "겹침: row=$row2 left=$left2" }
    }
}

