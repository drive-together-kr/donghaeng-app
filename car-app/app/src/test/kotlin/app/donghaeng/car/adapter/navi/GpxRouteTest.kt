package app.donghaeng.car.adapter.navi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxRouteTest {

    private val gpx = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1">
          <trk><name>t</name><trkseg>
            <trkpt lat="37.0000" lon="127.0000"><ele>1</ele></trkpt>
            <trkpt lat="37.0090" lon="127.0000"></trkpt>
            <trkpt lat="bad" lon="127.0000"></trkpt>
          </trkseg></trk>
        </gpx>
    """.trimIndent()

    @Test
    fun `parse reads trkpt and skips bad coordinates`() {
        val pts = GpxRoute.parse(gpx.byteInputStream())
        assertEquals(2, pts.size)
        assertEquals(37.009, pts[1].lat, 1e-9)
    }

    @Test
    fun `parse falls back to rtept when there is no track`() {
        val route = """<gpx xmlns="http://www.topografix.com/GPX/1/1"><rte><rtept lat="1" lon="2"/><rtept lat="3" lon="4"/></rte></gpx>"""
        assertEquals(listOf(LatLon(1.0, 2.0), LatLon(3.0, 4.0)), GpxRoute.parse(route.byteInputStream()))
    }

    @Test
    fun `distance and bearing for a point one km north`() {
        val a = LatLon(37.0, 127.0)
        val b = LatLon(37.0090, 127.0)      // 약 1 km 북쪽
        assertEquals(1_000.0, GpxRoute.distanceM(a, b), 10.0)
        assertEquals(0f, GpxRoute.bearingDeg(a, b), 0.5f)
        assertEquals(180f, GpxRoute.bearingDeg(b, a), 0.5f)
    }

    @Test
    fun `resample spaces points by step and keeps endpoints`() {
        val pts = listOf(LatLon(37.0, 127.0), LatLon(37.0090, 127.0), LatLon(37.0090, 127.0113)) // 1 km 북 + 약 1 km 동
        val out = GpxRoute.resample(pts, 100.0)
        assertEquals(pts.first(), out.first())
        assertEquals(pts.last(), out.last())
        // 약 2 km / 100 m ≈ 20 구간 → 21 ± 1 점
        assertTrue("size=${out.size}", out.size in 20..23)
        out.zipWithNext().dropLast(1).forEach { (a, b) ->
            val d = GpxRoute.distanceM(a, b)
            assertTrue("step=$d", d in 95.0..105.0)
        }
        assertEquals(GpxRoute.lengthM(pts), GpxRoute.lengthM(out), 1.0)
    }

    @Test
    fun `resample keeps duplicate points as a stop`() {
        val p = LatLon(37.0, 127.0)
        val out = GpxRoute.resample(listOf(p, p, p, LatLon(37.0009, 127.0)), 50.0)
        assertEquals(3, out.takeWhile { it == p }.size)
    }

    @Test
    fun `resample with fewer than two points returns input`() {
        assertEquals(emptyList<LatLon>(), GpxRoute.resample(emptyList(), 10.0))
        assertEquals(listOf(LatLon(1.0, 1.0)), GpxRoute.resample(listOf(LatLon(1.0, 1.0)), 10.0))
    }
}
