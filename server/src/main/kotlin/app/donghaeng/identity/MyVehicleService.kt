package app.donghaeng.identity

import app.donghaeng.common.text.TextSanitizer
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

    /**
     * nickname / isPrimary 중 온 것만 바꾼다. isPrimary=true 면 같은 사용자의 다른 대표 차량은 해제된다(사용자당 하나).
     * 별명은 제어·서식 문자를 지운 뒤 1~30자(C105). 사용자 잠금(`VehicleRepository.lockOwner`)을 먼저 잡아 같은 사용자의 승인과 대표 판정이 엇갈리지 않게 한다(C114).
     */
    @Transactional
    fun patch(userId: String, vehicleId: String, req: VehiclePatchRequest): MyVehicleDto {
        if (req.nickname == null && req.isPrimary == null) throw IdentityErrors.validation("nickname 또는 isPrimary 중 하나는 있어야 해요")
        vehicles.lockOwner(userId)
        vehicles.findActiveByIdAndOwner(vehicleId, userId) ?: throw IdentityErrors.vehicleNotFound()

        req.nickname?.let { raw -> vehicles.updateNickname(vehicleId, normalizeNickname(raw)) }
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
        if (!revokeOwned(userId, vehicleId)) throw IdentityErrors.vehicleNotFound()
    }

    /**
     * 차량이 스스로 해제(`DELETE /v1/vehicles/me`, 계약 2 — docs/03 §1.3): 폰의 해제와 **같은 경로**([revoke] — 활성 그룹에서 MEMBER_LEFT reason
     * `UNPAIRED`, refresh 전부 폐기, 커밋 뒤 `TOKEN_REVOKED`). 멱등 — 이미 해제됐거나(행 없음 포함), 토큰 발급 뒤 다른 계정으로 소유권이 넘어간 차는
     * 아무것도 바꾸지 않고 성공으로 끝낸다(옛 소유자 시절 토큰으로 새 소유자의 연결을 끊지 못하게).
     *
     * @param tokenOwnerId 차량 토큰의 `owner` 클레임(발급 시점 소유자)
     * @return 실제로 해제했으면 true
     */
    @Transactional
    fun unpairSelf(vehicleId: String, tokenOwnerId: String?): Boolean {
        val vehicle = vehicles.findById(vehicleId)
        if (vehicle == null || !vehicle.active) {
            log.info("vehicle self-unpair: already unpaired vehicleId={}", vehicleId)
            return false
        }
        if (tokenOwnerId == null || tokenOwnerId != vehicle.ownerId) {
            log.info("vehicle self-unpair ignored: owner changed since the token was issued vehicleId={}", vehicleId)
            return false
        }
        val done = revokeOwned(vehicle.ownerId, vehicleId)
        log.info("vehicle self-unpair vehicleId={} done={}", vehicleId, done)
        return done
    }

    private fun revokeOwned(userId: String, vehicleId: String): Boolean {
        val updated = vehicles.revoke(vehicleId, userId, clock.instant())
        if (updated == 0) return false
        val revokedTokens = refreshTokens.revokeAll(vehicleId)
        // 같은 트랜잭션 안에서 group 모듈이 받아 활성 그룹에서 뺀다 (방장이면 승계, 마지막이면 종료)
        events.publishEvent(VehicleUnpaired(vehicleId, userId, VehicleUnpaired.Reason.REVOKED))
        log.info("vehicle unpaired vehicleId={} refreshTokensRevoked={}", vehicleId, revokedTokens)
        return true
    }

    companion object {
        private val log = LoggerFactory.getLogger(MyVehicleService::class.java)

        /** 별명 정리(C105): 제어·서식 문자 제거·공백 접기 뒤 1~30자. 페어링 승인(`DeviceAuthService.approve`)도 같은 규칙 */
        fun normalizeNickname(raw: String): String {
            val nickname = TextSanitizer.normalize(raw)
            if (nickname.isEmpty() || nickname.length > DeviceAuthService.MAX_NICKNAME_LENGTH) {
                throw IdentityErrors.validation("nickname: 1~${DeviceAuthService.MAX_NICKNAME_LENGTH}자여야 해요")
            }
            return nickname
        }
    }
}
