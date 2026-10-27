package app.donghaeng.car.feature.groupdrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMapProjectionTest {

    @Test
    fun `fit keeps the farthest member inside the fill ratio and north is up`() {
        val center = 37.5 to 127.0
        val north1km = 37.5 + 1_000 / GroupMapProjection.M_PER_DEG_LAT to 127.0
        val p = GroupMapProjection.fit(center.first, center.second, listOf(center, north1km), widthPx = 1000f, heightPx = 600f)
        val c = p.toPoint(center.first, center.second)
        assertEquals(500f, c.x, 0.01f); assertEquals(300f, c.y, 0.01f)
        val n = p.toPoint(north1km.first, north1km.second)
        assertEquals(500f, n.x, 0.5f)
        assertTrue("north must be above center: y=${n.y}", n.y < c.y)
        // 반지름 300px × 0.78 = 234px 에 1 km 가 닿는다
        assertEquals(300f - 234f, n.y, 1.5f)
        assertEquals(1_000f / 234f, p.metersPerPx, 0.05f)
    }

    @Test
    fun `single member uses the minimum radius`() {
        val p = GroupMapProjection.fit(37.5, 127.0, listOf(37.5 to 127.0), 800f, 800f, minRadiusM = 300.0)
        assertEquals(300f / (400f * 0.78f), p.metersPerPx, 0.01f)
        assertEquals(200, p.ringStepM())   // 반경 312px × 0.96 m/px ≈ 385 m / 2.5 ≈ 154 → 다음 보기 좋은 값 200
    }

    @Test
    fun `points outside the view are clamped to the edge in the same direction`() {
        val p = GroupMapProjection(37.5, 127.0, 1000f, 600f, metersPerPx = 10f)
        val far = p.toPoint(37.5, 127.0 + 100_000 / (GroupMapProjection.M_PER_DEG_LON_EQ * kotlin.math.cos(Math.toRadians(37.5))))   // 100 km 동쪽
        assertFalse(p.contains(far))
        val edge = p.edgePointToward(far, insetPx = 20f)
        assertEquals(980f, edge.x, 0.5f)
        assertEquals(300f, edge.y, 0.5f)
        assertTrue(p.contains(edge))
    }

    @Test
    fun `offset in meters matches the known scale`() {
        val p = GroupMapProjection(0.0, 0.0, 100f, 100f, 1f)
        val (ex, ny) = p.offsetM(1.0, 1.0)
        assertEquals(GroupMapProjection.M_PER_DEG_LAT, ny, 1e-6)
        assertEquals(GroupMapProjection.M_PER_DEG_LON_EQ, ex, 1e-6)
    }
}
