package app.donghaeng.car.adapter.location

import app.donghaeng.car.domain.model.Fix
import app.donghaeng.protocol.LocSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 4순위(마지막) Fake 위치 — 고정 지점을 1 Hz 로 흘린다(ts 만 갱신). 항상 가용·절대 끊기지 않아 체인의 바닥 역할을 한다.
 * 움직이는 가짜 위치가 필요하면 fake flavor 의 `FakeNaviAdapter`(GPX 재생, 2순위 NAVI 소스) 가 담당한다.
 *
 * @param clock 테스트에서 시각을 고정하기 위한 훅
 */
class FakeLocationAdapter(
    private val lat: Double = DEMO_LAT,
    private val lon: Double = DEMO_LON,
    private val intervalMillis: Long = 1_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : LocationAdapter, LocationSource {

    override val source: LocSource = LocSource.FAKE

    override suspend fun isAvailable(): Boolean = true

    override fun locations(): Flow<Fix> = flow {
        while (true) {
            emit(Fix(lat = lat, lon = lon, heading = 0f, accuracy = 3f, ts = clock(), source = LocSource.FAKE))
            delay(intervalMillis)
        }
    }

    companion object {
        /** 데모용 임의 지점 — 실제 장소·주소와 무관 */
        const val DEMO_LAT = 36.3500
        const val DEMO_LON = 127.3800
    }
}
