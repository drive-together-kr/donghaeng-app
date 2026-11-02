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
}
