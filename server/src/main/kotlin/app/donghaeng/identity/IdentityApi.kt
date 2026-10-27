package app.donghaeng.identity

import app.donghaeng.identity.repo.VehicleRecord
import app.donghaeng.identity.repo.VehicleRepository
import org.springframework.stereotype.Component

/**
 * 다른 모듈(group 등)이 identity 를 쓰는 유일한 창구 (docs/06 §2: 패키지 간 호출은 `XxxApi` 인터페이스만).
 * 차량의 소유자·별명·활성 여부를 조회한다. 쓰기는 없다.
 */
interface IdentityApi {

    /** 해제 여부와 무관하게 차량을 찾는다. 없으면 null — 호출자가 [VehicleInfo.active] 를 확인한다. */
    fun vehicle(vehicleId: String): VehicleInfo?

    /** 여러 차량을 한 번에 (멤버 목록 보강용). 없는 ID 는 빠진다. */
    fun vehicles(vehicleIds: Collection<String>): Map<String, VehicleInfo>

    /** 사용자의 활성(해제되지 않은) 차량 — 대표 차량 먼저, 그다음 등록순 */
    fun activeVehiclesOf(userId: String): List<VehicleInfo>
}

/** identity 가 밖으로 내보내는 차량 정보 (해시 같은 내부 필드는 없음) */
data class VehicleInfo(
    val vehicleId: String,
    val ownerId: String,
    val nickname: String,
    val brand: String?,
    val model: String?,
    val isPrimary: Boolean,
    /** 페어링 해제(`revoked_at`)되지 않았는가 */
    val active: Boolean,
)

@Component
class DbIdentityApi(private val vehicles: VehicleRepository) : IdentityApi {

    override fun vehicle(vehicleId: String): VehicleInfo? = vehicles.findById(vehicleId)?.toInfo()

    override fun vehicles(vehicleIds: Collection<String>): Map<String, VehicleInfo> =
        if (vehicleIds.isEmpty()) emptyMap() else vehicles.findAllByIds(vehicleIds).associate { it.vehicleId to it.toInfo() }

    override fun activeVehiclesOf(userId: String): List<VehicleInfo> = vehicles.listActiveByOwner(userId).map { it.toInfo() }

    private fun VehicleRecord.toInfo() = VehicleInfo(
        vehicleId = vehicleId,
        ownerId = ownerId,
        nickname = nickname,
        brand = brand,
        model = model,
        isPrimary = isPrimary,
        active = active,
    )
}
