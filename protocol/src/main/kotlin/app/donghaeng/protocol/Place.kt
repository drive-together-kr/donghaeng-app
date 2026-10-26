package app.donghaeng.protocol

import kotlinx.serialization.Serializable

/**
 * 목적지·경유지 같은 지점 (docs/03 §2.1 destination 객체와 동일 형태).
 *
 * @param poiId   내비 POI ID. 없으면 null
 * @param address 주소 문자열. 없으면 null
 */
@Serializable
data class Place(
    val lat: Double,
    val lon: Double,
    val name: String,
    val poiId: String? = null,
    val address: String? = null,
)
