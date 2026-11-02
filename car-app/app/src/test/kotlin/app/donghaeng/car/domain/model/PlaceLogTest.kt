package app.donghaeng.car.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A090(규칙 8): 장소 로그에 이름·poiId 원문을 남기지 않는다 — 좌표를 가려도 '우리집'·POI 로 장소가 드러난다 */
class PlaceLogTest {

    @Test
    fun `place log masks the name and the poi id`() {
        val s = Place(37.56789, 126.97654, "우리집 아파트", poiId = "123456").toLogString()
        assertFalse(s, s.contains("우리집 아파트"))
        assertFalse(s, s.contains("123456"))
        assertFalse(s, s.contains("37.56789"))
        assertTrue(s, s.contains("poi=Y"))
    }
}
