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

    /**
     * [vehicle] 과 같지만 차량 행을 `FOR KEY SHARE` 로 잡는다 — **호출자 트랜잭션이 끝날 때까지** 재페어링(`VehicleRegistry.register` 의
     * 차량 행 `FOR UPDATE`)이 기다린다. 해제(`revoked_at` 갱신 = 키가 아닌 UPDATE)는 막지 않는다. 반드시 `@Transactional` 안에서 부른다.
     *
     * 쓰는 곳: 차량을 참조(FK)하는 행을 그룹 행 잠금 뒤에 넣는 경로(F16 무전 업로드 — `GroupAccessApi.lockActiveMemberVehicle`).
     * 재페어링의 순서(차량 행 → 그룹 행)와 맞춰 차량 행을 **먼저** 잡아야 FK 검사(차량 행 KEY SHARE)가 그룹 행을 쥔 채 기다리지 않는다(교착 40P01 방지).
     */
    fun lockVehicleForShare(vehicleId: String): VehicleInfo?

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

    override fun lockVehicleForShare(vehicleId: String): VehicleInfo? = vehicles.findByIdForKeyShare(vehicleId)?.toInfo()

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
