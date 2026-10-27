package app.donghaeng.car.adapter.navi

import android.content.Context
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.Geo
import org.w3c.dom.Element
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/** 위경도 한 점 */
data class LatLon(val lat: Double, val lon: Double)

/**
 * GPX(`<trkpt>`/`<rtept>`) 읽기 + 일정 속도로 1Hz 재생하기 위한 재표본화 (T20/T30 Fake 내비·위치).
 * 좌표는 로그에 남기지 않는다.
 */
object GpxRoute {

    /** DOM 파서 사용 — Android·JVM 단위 테스트 양쪽에서 동작(XmlPull 은 JVM 테스트에서 스텁). GPX 는 작아서 충분하다. */
    fun parse(input: InputStream): List<LatLon> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val doc = factory.newDocumentBuilder().parse(input)
        val points = ArrayList<LatLon>()
        for (tag in listOf("trkpt", "rtept")) {
            val nodes = doc.getElementsByTagNameNS("*", tag)
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                val lat = el.getAttribute("lat").toDoubleOrNull()
                val lon = el.getAttribute("lon").toDoubleOrNull()
                if (lat != null && lon != null) points += LatLon(lat, lon)
            }
            if (points.isNotEmpty()) break   // trkpt 가 있으면 rtept 는 무시
        }
        return points
    }

    /** assets 의 GPX. 없거나 깨지면 빈 목록 + 경고 로그(크래시 금지) */
    fun loadAsset(context: Context, path: String): List<LatLon> = try {
        context.assets.open(path).use { parse(it) }.also { DhLog.i("gpx loaded asset=$path points=${it.size}") }
    } catch (e: Exception) {
        DhLog.w("gpx load failed asset=$path", e)
        emptyList()
    }

    /** 두 점 사이 거리(m), Haversine */
    fun distanceM(a: LatLon, b: LatLon): Double = Geo.distanceM(a.lat, a.lon, b.lat, b.lon)

    /** a → b 방위각(0~360) */
    fun bearingDeg(a: LatLon, b: LatLon): Float = Geo.bearingDeg(a.lat, a.lon, b.lat, b.lon)

    /** 경로 전체 길이(m) */
    fun lengthM(points: List<LatLon>): Double = points.zipWithNext().sumOf { (a, b) -> distanceM(a, b) }

    /**
     * [stepM] 간격으로 선형 보간한 점 목록(1Hz 재생 시 stepM = 초속). 원 점이 1개 이하이면 그대로.
     * 겹친 점(거리 0)은 그대로 둬 "정차" 를 표현한다.
     */
    fun resample(points: List<LatLon>, stepM: Double): List<LatLon> {
        if (points.size < 2 || stepM <= 0) return points
        val out = ArrayList<LatLon>()
        out += points.first()
        var carry = 0.0
        for ((a, b) in points.zipWithNext()) {
            val seg = distanceM(a, b)
            if (seg == 0.0) {
                out += b
                continue
            }
            var d = stepM - carry
            while (d <= seg) {
                val t = d / seg
                out += LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
                d += stepM
            }
            carry = seg - (d - stepM)
        }
        if (out.last() != points.last()) out += points.last()
        return out
    }
}
