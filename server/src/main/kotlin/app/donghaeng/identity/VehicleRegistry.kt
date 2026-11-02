package app.donghaeng.identity

import app.donghaeng.common.id.Ids
import app.donghaeng.identity.repo.VehicleRecord
import app.donghaeng.identity.repo.VehicleRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component

/**
 * 페어링(대체 수단) 결과를 동행 DB 에 등록한다 (docs/06 §2 `VehicleRegistry`).
 *
 * - `car_info_id_hash` 가 처음이면 새 차량(`veh_` + ULID). 호출자에게 활성 대표 차량이 없으면 이 차가 대표가 된다.
 * - 이미 있으면 호출자에게 소유권을 넘긴다(닉네임 갱신, 해제 상태 복원). 이전 소유자가 쥔 차량 refresh 토큰은 폐기한다
 *   (이미 발급된 차량 access token 은 만료(24h)까지 서명상 유효 — 소유자가 바뀌면 [VehicleUnpaired](TRANSFERRED) 를 받은 group 모듈이
 *   커밋 뒤 이전 vehicleId 에 cmd `TOKEN_REVOKED` 를 보낸다, T33).
 *
 * 승인 트랜잭션 안에서 호출된다. 잠금 순서: 사용자 advisory(`owner:{userId}`, C114 — 같은 사용자의 동시 승인 두 건이 둘 다 '대표 없음'을 보고
 * 부분 유니크 인덱스 `uq_vehicle_owner_primary` 에 걸려 500 이 나지 않게) → 차량 행(FOR UPDATE) → (이전이면) refresh 주체 잠금 → group 의 차량 advisory·그룹 행.
 */
@Component
class VehicleRegistry(
    private val vehicles: VehicleRepository,
    private val refreshTokens: RefreshTokenService,
    private val events: ApplicationEventPublisher,
) {
    fun register(carInfoIdHash: String, ownerId: String, nickname: String, brand: String?, model: String?): VehicleRecord {
        vehicles.lockOwner(ownerId)
        val existing = vehicles.findByCarInfoIdHashForUpdate(carInfoIdHash)
        if (existing == null) {
            val isPrimary = !vehicles.hasActivePrimary(ownerId)
            val created = vehicles.insert(Ids.vehicle(), carInfoIdHash, ownerId, nickname, brand, model, isPrimary)
            log.info("vehicle registered vehicleId={} ownerId={} primary={}", created.vehicleId, ownerId, isPrimary)
            return created
        }
        val revoked = refreshTokens.revokeAll(existing.vehicleId)
        val isPrimary = !vehicles.hasActivePrimary(ownerId, excludingVehicleId = existing.vehicleId)
        val transferred = vehicles.transfer(existing.vehicleId, ownerId, nickname, brand, model, isPrimary)
        // 다른 계정으로 넘어가면 이전 소유자의 그룹에서는 빠져야 한다 (group 모듈이 같은 트랜잭션에서 처리).
        // 옛 그룹에는 옛 별명으로 알린다(C104) — 차량 행은 위 transfer 로 이미 새 별명이다
        if (existing.ownerId != ownerId) {
            events.publishEvent(VehicleUnpaired(existing.vehicleId, existing.ownerId, VehicleUnpaired.Reason.TRANSFERRED, previousNickname = existing.nickname))
        }
        log.info(
            "vehicle re-paired vehicleId={} ownerChanged={} wasRevoked={} primary={} refreshTokensRevoked={}",
            existing.vehicleId, existing.ownerId != ownerId, !existing.active, isPrimary, revoked,
        )
        return transferred
    }

    companion object {
        private val log = LoggerFactory.getLogger(VehicleRegistry::class.java)
    }
}
