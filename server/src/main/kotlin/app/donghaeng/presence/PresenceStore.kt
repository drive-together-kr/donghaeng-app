package app.donghaeng.presence

import app.donghaeng.common.config.DhProperties
import app.donghaeng.group.GroupMembershipCache
import app.donghaeng.protocol.Energy
import app.donghaeng.protocol.Eta
import app.donghaeng.protocol.LocSource
import app.donghaeng.protocol.Telemetry
import org.slf4j.LoggerFactory
import org.springframework.data.geo.Point
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/** Redis `veh:{vid}:state` 해시를 읽은 값 (docs/04 §2). 좌표는 로그에 찍지 않는다 */
data class VehicleState(
    val lat: Double,
    val lon: Double,
    val heading: Float?,
    val speedKph: Float?,
    val gear: String?,
    val locSource: LocSource?,
    /** 마지막 텔레메트리를 **서버가 받은** 시각(epoch ms, 서버 시계 — C099) */
    val ts: Long,
    val eta: Eta?,
    /** ETA 를 마지막으로 받은 시각 */
    val etaTs: Long?,
    val energy: Energy?,
    /** 속도 < 1 km/h 가 시작된 시각. 움직이면 null */
    val stoppedSinceMs: Long?,
)

/**
 * presence 의 실시간 상태 저장소 (docs/04 §2, 모두 TTL). 구현은 Redis([RedisPresenceStore]); 테스트는 메모리 구현으로 바꿔 끼운다.
 */
interface PresenceStore {
    /** 텔레메트리 한 건 반영. [tsMs] = 서버 수신 시각. ETA·에너지는 **있을 때만** 덮어쓴다(차량은 변화 시에만 보낸다) */
    fun recordTelemetry(vehicleId: String, telemetry: Telemetry, tsMs: Long)

    /** 그룹 GEO 인덱스 `grp:{gid}:geo` 갱신 (insight 가 근접 조회에 쓴다) */
    fun recordGeo(groupId: String, vehicleId: String, lat: Double, lon: Double)

    /** presence 토픽(접속/LWT) 반영 */
    fun recordPresence(vehicleId: String, online: Boolean, tsMs: Long)

    fun stateOf(vehicleId: String): VehicleState?

    /** presence 기준 접속 여부. 아직 모르면 null */
    fun onlineOf(vehicleId: String): Boolean?
}

@Component
class RedisPresenceStore(
    private val redis: StringRedisTemplate,
    private val props: DhProperties,
) : PresenceStore {

    private val stateTtl: Duration get() = Duration.ofSeconds(props.presence.stateTtlSeconds)

    override fun recordTelemetry(vehicleId: String, telemetry: Telemetry, tsMs: Long) = safely("recordTelemetry") {
        val key = GroupMembershipCache.vehicleStateKey(vehicleId)
        val hash = redis.opsForHash<String, String>()
        val put = mutableMapOf(
            F_LAT to telemetry.lat.toString(),
            F_LON to telemetry.lon.toString(),
            F_HEADING to (telemetry.heading?.toString() ?: ""),
            F_SPEED to (telemetry.speedKph?.toString() ?: ""),
            F_GEAR to (telemetry.gear ?: ""),
            F_LOC_SOURCE to telemetry.locSource.name,
            F_TS to tsMs.toString(),
        )
        telemetry.eta?.let { eta ->
            put[F_ETA_DEST_TS] = eta.destTs?.toString() ?: ""
            put[F_ETA_REMAIN_M] = eta.remainM?.toString() ?: ""
            put[F_ETA_REMAIN_S] = eta.remainS?.toString() ?: ""
            put[F_ETA_TS] = tsMs.toString()
        }
        telemetry.energy?.let { e ->
            e.fuelPct?.let { put[F_FUEL] = it.toString() }
            e.socPct?.let { put[F_SOC] = it.toString() }
        }
        val speed = telemetry.speedKph
        if (speed != null) {
            if (speed < STOP_SPEED_KPH) {
                if (hash.get(key, F_STOPPED_SINCE).isNullOrBlank()) put[F_STOPPED_SINCE] = tsMs.toString()
            } else {
                hash.delete(key, F_STOPPED_SINCE)
            }
        }
        hash.putAll(key, put)
        redis.expire(key, stateTtl)
        // 텔레메트리가 오면 접속 중이다 (retained presence 를 놓쳤을 때 대비)
        redis.opsForValue().set(onlineKey(vehicleId), "1", ONLINE_TTL)
    }

    override fun recordGeo(groupId: String, vehicleId: String, lat: Double, lon: Double) = safely("recordGeo") {
        val key = GroupMembershipCache.geoKey(groupId)
        redis.opsForGeo().add(key, Point(lon, lat), vehicleId)
        redis.expire(key, stateTtl)
    }

    override fun recordPresence(vehicleId: String, online: Boolean, tsMs: Long) = safely("recordPresence") {
        redis.opsForValue().set(onlineKey(vehicleId), if (online) "1" else "0", ONLINE_TTL)
    }

    override fun stateOf(vehicleId: String): VehicleState? = try {
        val m = redis.opsForHash<String, String>().entries(GroupMembershipCache.vehicleStateKey(vehicleId))
        if (m.isEmpty()) null else parse(m)
    } catch (e: Exception) {
        log.warn("redis read failed op=stateOf cause={}", e.javaClass.simpleName)
        null
    }

    override fun onlineOf(vehicleId: String): Boolean? = try {
        when (redis.opsForValue().get(onlineKey(vehicleId))) {
            "1" -> true
            "0" -> false
            else -> null
        }
    } catch (e: Exception) {
        log.warn("redis read failed op=onlineOf cause={}", e.javaClass.simpleName)
        null
    }

    private fun parse(m: Map<String, String>): VehicleState? {
        val lat = m[F_LAT]?.toDoubleOrNull() ?: return null
        val lon = m[F_LON]?.toDoubleOrNull() ?: return null
        val ts = m[F_TS]?.toLongOrNull() ?: return null
        val eta = if (m.containsKey(F_ETA_TS)) Eta(destTs = m[F_ETA_DEST_TS]?.toLongOrNull(), remainM = m[F_ETA_REMAIN_M]?.toIntOrNull(), remainS = m[F_ETA_REMAIN_S]?.toIntOrNull()) else null
        val fuel = m[F_FUEL]?.toIntOrNull()
        val soc = m[F_SOC]?.toIntOrNull()
        return VehicleState(
            lat = lat,
            lon = lon,
            heading = m[F_HEADING]?.toFloatOrNull(),
            speedKph = m[F_SPEED]?.toFloatOrNull(),
            gear = m[F_GEAR]?.takeIf { it.isNotBlank() },
            locSource = m[F_LOC_SOURCE]?.let { s -> LocSource.entries.firstOrNull { it.name == s } },
            ts = ts,
            eta = eta,
            etaTs = m[F_ETA_TS]?.toLongOrNull(),
            energy = if (fuel != null || soc != null) Energy(fuelPct = fuel, socPct = soc) else null,
            stoppedSinceMs = m[F_STOPPED_SINCE]?.toLongOrNull(),
        )
    }

    private inline fun safely(op: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.warn("redis update failed op={} cause={}", op, e.javaClass.simpleName)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(RedisPresenceStore::class.java)

        fun onlineKey(vehicleId: String) = "veh:$vehicleId:online"

        const val STOP_SPEED_KPH = 1f
        private val ONLINE_TTL: Duration = Duration.ofHours(24)

        const val F_LAT = "lat"
        const val F_LON = "lon"
        const val F_HEADING = "heading"
        const val F_SPEED = "speedKph"
        const val F_GEAR = "gear"
        const val F_LOC_SOURCE = "locSource"
        const val F_TS = "ts"
        const val F_ETA_DEST_TS = "etaDestTs"
        const val F_ETA_REMAIN_M = "etaRemainM"
        const val F_ETA_REMAIN_S = "etaRemainS"
        const val F_ETA_TS = "etaTs"
        const val F_FUEL = "fuelPct"
        const val F_SOC = "socPct"
        const val F_STOPPED_SINCE = "stoppedSince"
    }
}
