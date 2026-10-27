package app.donghaeng.car.domain.event

import app.donghaeng.car.domain.model.NaviEvent
import app.donghaeng.car.domain.model.Place
import app.donghaeng.protocol.EnergyKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDetectorTest {

    private fun sample(ts: Long, speed: Float? = 60f, brake: Boolean? = false, fuel: Int? = null, soc: Int? = null, airbag: Boolean? = false, lat: Double? = null, lon: Double? = null) =
        Sample(tsMs = ts, speedKph = speed, brakePressed = brake, fuelPct = fuel, socPct = soc, airbagDeployed = airbag, lat = lat, lon = lon)

    @Test
    fun `low energy fires at 15 then every 5 points and resets after refuel`() {
        val d = EventDetector()
        assertTrue(d.onSample(sample(0, fuel = 40)).isEmpty())
        assertTrue(d.onSample(sample(1_000, fuel = 16)).isEmpty())
        assertEquals(listOf(DetectedEvent.LowEnergy(EnergyKind.FUEL, 15)), d.onSample(sample(2_000, fuel = 15)))
        assertTrue(d.onSample(sample(3_000, fuel = 12)).isEmpty())            // 15 → 12: 5%p 미만
        assertEquals(listOf(DetectedEvent.LowEnergy(EnergyKind.FUEL, 10)), d.onSample(sample(4_000, fuel = 10)))
        assertEquals(listOf(DetectedEvent.LowEnergy(EnergyKind.FUEL, 4)), d.onSample(sample(5_000, fuel = 4)))
        assertTrue(d.onSample(sample(6_000, fuel = 80)).isEmpty())            // 주유 → 초기화
        assertEquals(listOf(DetectedEvent.LowEnergy(EnergyKind.FUEL, 14)), d.onSample(sample(7_000, fuel = 14)))
        // EV: 연료 없으면 SoC
        val ev = EventDetector()
        assertEquals(listOf(DetectedEvent.LowEnergy(EnergyKind.EV, 12)), ev.onSample(sample(0, soc = 12)))
    }

    @Test
    fun `hard brake needs brake on, 40 kph before, 4 mps2 within a second, and a 30 s cooldown`() {
        val d = EventDetector()
        assertTrue(d.onSample(sample(0, speed = 60f)).isEmpty())
        // 60 → 40 km/h in 1 s = 5.6 m/s², 브레이크 OFF → 아님
        assertTrue(d.onSample(sample(1_000, speed = 40f, brake = false)).isEmpty())
        // 40 → 20 with brake: 직전 속도 40 ≥ 40, 5.6 m/s² → 급제동
        val e = d.onSample(sample(2_000, speed = 20f, brake = true))
        assertEquals(1, e.size)
        val hb = e.single() as DetectedEvent.HardBrake
        assertEquals(5.6, hb.decelMps2, 0.05)
        assertEquals(40.0, hb.speedBeforeKph, 0.01)
        // 쿨다운 안: 다시 60 → 0 이어도 조용
        d.onSample(sample(3_000, speed = 60f))
        assertTrue(d.onSample(sample(4_000, speed = 0f, brake = true)).isEmpty())
        // 쿨다운 뒤 다시 가능
        d.onSample(sample(33_000, speed = 60f))
        assertEquals(1, d.onSample(sample(34_000, speed = 0f, brake = true)).size)
        // 샘플 간격이 1.5 초를 넘으면 판정하지 않는다
        d.onSample(sample(70_000, speed = 60f))
        assertTrue(d.onSample(sample(73_000, speed = 0f, brake = true)).isEmpty())
        // 완만한 감속(2 m/s²)은 아님
        d.onSample(sample(80_000, speed = 60f))
        assertTrue(d.onSample(sample(81_000, speed = 53f, brake = true)).isEmpty())
    }

    @Test
    fun `stopped after 60 s and resumed with total duration`() {
        val d = EventDetector()
        for (t in 0..59) assertTrue(d.onSample(sample(t * 1_000L, speed = 0f)).isEmpty())
        assertEquals(listOf(DetectedEvent.Stopped(60)), d.onSample(sample(60_000, speed = 0f)))
        assertTrue(d.onSample(sample(61_000, speed = 0f)).isEmpty())
        assertTrue(d.onSample(sample(62_000, speed = 3f)).isEmpty())          // 1~5 km/h 는 아직 정차
        assertEquals(listOf(DetectedEvent.Resumed(90)), d.onSample(sample(90_000, speed = 20f)))
        // 짧은 정차(30 s)는 이벤트 없음
        for (t in 100..130) d.onSample(sample(t * 1_000L, speed = 0f))
        assertTrue(d.onSample(sample(131_000, speed = 30f)).isEmpty())
    }

    @Test
    fun `airbag fires once, arrival by proximity once per destination, navi arrivals pass through`() {
        val d = EventDetector()
        assertEquals(listOf(DetectedEvent.AirbagDeployed), d.onSample(sample(0, airbag = true)))
        assertTrue(d.onSample(sample(1_000, airbag = true)).isEmpty())

        d.setDestination(Place(37.5000, 127.0, "휴게소"))
        assertTrue(d.onSample(sample(2_000, speed = 50f, lat = 37.5005, lon = 127.0)).isEmpty())   // 56 m 지만 달리는 중
        assertEquals(listOf(DetectedEvent.DestinationArrived("휴게소")), d.onSample(sample(3_000, speed = 0f, lat = 37.5005, lon = 127.0)))
        assertTrue(d.onSample(sample(4_000, speed = 0f, lat = 37.5005, lon = 127.0)).isEmpty())
        assertTrue(d.onNavi(NaviEvent.DestinationArrived(null, "휴게소")).isEmpty())                 // 이미 알림
        d.setDestination(Place(35.1, 126.8, "할머니댁"))
        assertEquals(listOf(DetectedEvent.DestinationArrived("할머니댁")), d.onNavi(NaviEvent.DestinationArrived(null, "할머니댁")))

        val wp = d.onNavi(NaviEvent.WaypointArrived(index = 0, waypoint = Place(36.0, 127.0, "덕평휴게소"), name = "덕평휴게소"))
        assertEquals(listOf(DetectedEvent.WaypointArrived("덕평휴게소", 36.0, 127.0)), wp)
        // 경유지 좌표가 없으면 마지막 위치
        assertEquals(listOf(DetectedEvent.WaypointArrived("이름만", 37.5005, 127.0)), d.onNavi(NaviEvent.WaypointArrived(index = null, waypoint = null, name = "이름만")))
    }
}
