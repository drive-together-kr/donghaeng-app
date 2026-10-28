package app.donghaeng.identity

import app.donghaeng.identity.repo.VehicleRepository
import app.donghaeng.protocol.rest.MyVehicleDto
import app.donghaeng.protocol.rest.VehiclePatchRequest
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/** 내 차 (docs/03 §1.3): 목록은 [VehicleDirectory], 닉네임·대표 변경과 페어링 해제는 동행 DB. */
@Service
class MyVehicleService(
    private val directory: VehicleDirectory,
    private val vehicles: VehicleRepository,
    private val refreshTokens: RefreshTokenService,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    fun list(userId: String): List<MyVehicleDto> = directory.vehiclesOf(userId)

    /** nickname / isPrimary 중 온 것만 바꾼다. isPrimary=true 면 같은 사용자의 다른 대표 차량은 해제된다(사용자당 하나). */
    @Transactional
    fun patch(userId: String, vehicleId: String, req: VehiclePatchRequest): MyVehicleDto {
        if (req.nickname == null && req.isPrimary == null) throw IdentityErrors.validation("nickname 또는 isPrimary 중 하나는 있어야 해요")
        vehicles.findActiveByIdAndOwner(vehicleId, userId) ?: throw IdentityErrors.vehicleNotFound()

        req.nickname?.let { raw ->
            val nickname = raw.trim()
            if (nickname.isEmpty() || nickname.length > DeviceAuthService.MAX_NICKNAME_LENGTH) {
                throw IdentityErrors.validation("nickname: 1~${DeviceAuthService.MAX_NICKNAME_LENGTH}자여야 해요")
            }
            vehicles.updateNickname(vehicleId, nickname)
        }
        req.isPrimary?.let { primary ->
            if (primary) {
                vehicles.clearPrimary(userId)
                vehicles.setPrimary(vehicleId, true)
            } else {
                vehicles.setPrimary(vehicleId, false)
            }
        }
        log.info("vehicle patched vehicleId={} nicknameChanged={} isPrimary={}", vehicleId, req.nickname != null, req.isPrimary)
        return vehicles.findById(vehicleId)!!.toMyVehicleDto()
    }

    /**
     * 페어링 해제: `revoked_at` 기록 + 차량 refresh 토큰 폐기. 이미 발급된 차량 access token 은 만료(24h)까지 서명상 유효하므로
     * group 모듈이 [VehicleUnpaired] 를 받아 커밋 뒤 차량에 cmd `TOKEN_REVOKED` 를 보낸다(T33) — 차량 앱이 스스로 자격 증명을 지운다.
     */
    @Transactional
    fun revoke(userId: String, vehicleId: String) {
        val updated = vehicles.revoke(vehicleId, userId, clock.instant())
        if (updated == 0) throw IdentityErrors.vehicleNotFound()
        val revokedTokens = refreshTokens.revokeAll(vehicleId)
        // 같은 트랜잭션 안에서 group 모듈이 받아 활성 그룹에서 뺀다 (방장이면 승계, 마지막이면 종료)
        events.publishEvent(VehicleUnpaired(vehicleId, userId, VehicleUnpaired.Reason.REVOKED))
        log.info("vehicle unpaired vehicleId={} refreshTokensRevoked={}", vehicleId, revokedTokens)
    }

    companion object {
        private val log = LoggerFactory.getLogger(MyVehicleService::class.java)
    }
}
