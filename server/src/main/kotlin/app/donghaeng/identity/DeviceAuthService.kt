package app.donghaeng.identity

import app.donghaeng.common.config.DhProperties
import app.donghaeng.identity.jwt.JwtIssuer
import app.donghaeng.identity.repo.DeviceAuthStatus
import app.donghaeng.identity.repo.DeviceAuthorizationRepository
import app.donghaeng.identity.repo.VehicleRepository
import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.DeviceApproveRequest
import app.donghaeng.protocol.rest.DeviceCodeRequest
import app.donghaeng.protocol.rest.DeviceCodeResponse
import app.donghaeng.protocol.rest.DeviceTokenRequest
import app.donghaeng.protocol.rest.DeviceTokenResponse
import app.donghaeng.protocol.rest.MqttInfo
import app.donghaeng.protocol.rest.VehicleSummary
import org.slf4j.LoggerFactory
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

/**
 * 차량 페어링 — Device Authorization Grant 변형 (docs/03 §1.2, RFC 8628). 공식 앱 계정에 차량이 없을 때의 대체 수단.
 *
 * 차량: `POST /device/code` → QR(`{base}/pair?c=…`)·userCode 표시 → `POST /device/token` 을 interval 간격으로 폴링
 * 폰(companion `pairVehicle`): `POST /device/approve` (사용자 JWT) → 차량 행 생성/이전 → 차량이 다음 폴링에서 토큰 수령
 */
@Service
class DeviceAuthService(
    private val codes: DeviceAuthorizationRepository,
    private val vehicles: VehicleRepository,
    private val registry: VehicleRegistry,
    private val hasher: CarIdHasher,
    private val jwt: JwtIssuer,
    private val refreshTokens: RefreshTokenService,
    private val redis: StringRedisTemplate,
    private val props: DhProperties,
    private val clock: Clock,
) {
    /**
     * 디바이스 코드 발급 (인증 없음). 트랜잭션을 걸지 않는다 — user_code UNIQUE 충돌 시 같은 연결에서 바로 다시 INSERT 하기 위해.
     */
    fun issueCode(req: DeviceCodeRequest): DeviceCodeResponse {
        val carInfoId = req.vehicleId.trim()
        if (carInfoId.isEmpty() || carInfoId.length > MAX_CAR_INFO_ID_LENGTH) throw IdentityErrors.validation("vehicleId: 차량 ID 가 비어 있거나 너무 길어요")
        val brand = req.brand.clean(MAX_LABEL_LENGTH)
        val model = req.model.clean(MAX_LABEL_LENGTH)
        val hash = hasher.hash(carInfoId)
        val deviceCode = DEVICE_CODE_PREFIX + SecureTokens.randomBase62Chars(DEVICE_CODE_RANDOM_LENGTH)
        val expiresAt = clock.instant().plus(CODE_TTL)

        var userCode: String? = null
        for (attempt in 1..MAX_USER_CODE_ATTEMPTS) {
            val candidate = UserCodes.generate()
            try {
                codes.insertPending(deviceCode, candidate, hash, brand, model, expiresAt)
                userCode = candidate
                break
            } catch (e: DuplicateKeyException) {
                log.warn("user code collision attempt={}", attempt)
            }
        }
        val code = userCode ?: throw IllegalStateException("user code 를 ${MAX_USER_CODE_ATTEMPTS}번 시도해도 만들지 못했어요")
        log.info("device code issued brand={} model={} expiresInS={}", brand, model, CODE_TTL.seconds)
        return DeviceCodeResponse(
            deviceCode = deviceCode,
            userCode = code,
            verifyUri = ApiPaths.pairUrl(props.publicBaseUrl, code),
            expiresIn = CODE_TTL.seconds.toInt(),
            interval = POLL_INTERVAL_SECONDS,
        )
    }

    /**
     * 폰에서 승인 (사용자 JWT). 404 CODE_NOT_FOUND → 410 CODE_EXPIRED → 409 ALREADY_APPROVED 순으로 검사하고,
     * 차량 행을 만들거나 호출자에게 이전한 뒤 코드를 APPROVED 로 바꾼다. 응답은 200 VehicleSummary.
     */
    @Transactional
    fun approve(userId: String, req: DeviceApproveRequest): VehicleSummary {
        val nickname = req.nickname.trim()
        if (nickname.isEmpty() || nickname.length > MAX_NICKNAME_LENGTH) throw IdentityErrors.validation("nickname: 1~${MAX_NICKNAME_LENGTH}자여야 해요")
        if (req.userCode.isBlank()) throw IdentityErrors.validation("userCode: 연결 코드가 비어 있어요")
        val userCode = UserCodes.normalize(req.userCode) ?: throw IdentityErrors.codeNotFound()

        val auth = codes.findByUserCodeForUpdate(userCode) ?: throw IdentityErrors.codeNotFound()
        if (!auth.expiresAt.isAfter(clock.instant())) throw IdentityErrors.codeExpired()
        if (auth.status != DeviceAuthStatus.PENDING) throw IdentityErrors.alreadyApproved()

        val vehicle = registry.register(auth.carInfoIdHash, userId, nickname, auth.brand, auth.model)
        codes.approve(auth.deviceCode, userId, nickname)
        log.info("device code approved vehicleId={} ownerId={}", vehicle.vehicleId, vehicle.ownerId)
        return VehicleSummary(vehicleId = vehicle.vehicleId, nickname = vehicle.nickname, ownerId = vehicle.ownerId)
    }

    /**
     * 차량 폴링. 404 CODE_NOT_FOUND → 429 SLOW_DOWN → 410 CODE_EXPIRED → 428 AUTHORIZATION_PENDING / 200 / 409 ALREADY_APPROVED.
     * 승인된 코드는 한 번만 토큰으로 바꾸고 CONSUMED 가 된다.
     */
    @Transactional
    fun poll(req: DeviceTokenRequest): DeviceTokenResponse {
        val deviceCode = req.deviceCode.trim()
        if (deviceCode.isEmpty() || deviceCode.length > MAX_DEVICE_CODE_LENGTH) throw IdentityErrors.codeNotFound()
        val auth = codes.findByDeviceCodeForUpdate(deviceCode) ?: throw IdentityErrors.codeNotFound()
        throttle(deviceCode)
        if (!auth.expiresAt.isAfter(clock.instant())) throw IdentityErrors.codeExpired()

        return when (auth.status) {
            DeviceAuthStatus.PENDING -> throw IdentityErrors.authorizationPending()
            DeviceAuthStatus.CONSUMED -> throw IdentityErrors.alreadyApproved()
            DeviceAuthStatus.EXPIRED -> throw IdentityErrors.codeExpired()
            DeviceAuthStatus.APPROVED -> {
                val vehicle = vehicles.findByCarInfoIdHashForUpdate(auth.carInfoIdHash)
                    ?: throw IllegalStateException("승인된 코드에 차량 행이 없어요")
                // 승인 뒤 차량이 폰에서 해제됐으면 승인도 무효
                if (!vehicle.active) throw IdentityErrors.codeExpired()
                val accessToken = jwt.vehicleToken(vehicle.vehicleId, vehicle.ownerId)
                val refreshToken = refreshTokens.issue(vehicle.vehicleId)
                codes.setStatus(auth.deviceCode, DeviceAuthStatus.CONSUMED)
                log.info("vehicle token issued vehicleId={} ownerId={}", vehicle.vehicleId, vehicle.ownerId)
                DeviceTokenResponse(
                    vehicleAccessToken = accessToken,
                    refreshToken = refreshToken,
                    vehicle = VehicleSummary(vehicle.vehicleId, vehicle.nickname, vehicle.ownerId),
                    mqtt = MqttInfo(uri = props.mqtt.publicUri, clientId = vehicle.vehicleId, username = vehicle.vehicleId),
                )
            }
        }
    }

    /** `devcode:poll:{deviceCode}` 에 마지막으로 받아 준 폴링 시각(ms)을 둔다 (docs/04 §2). interval 안에 다시 오면 429 — 시각은 갱신하지 않는다. */
    private fun throttle(deviceCode: String) {
        val key = POLL_KEY_PREFIX + deviceCode
        val nowMs = clock.millis()
        val last = redis.opsForValue().get(key)?.toLongOrNull()
        if (last != null && nowMs - last < POLL_INTERVAL_SECONDS * 1000L) throw IdentityErrors.slowDown()
        redis.opsForValue().set(key, nowMs.toString(), POLL_KEY_TTL)
    }

    private fun String?.clean(max: Int): String? = this?.trim()?.takeIf { it.isNotEmpty() }?.take(max)

    companion object {
        private val log = LoggerFactory.getLogger(DeviceAuthService::class.java)
        const val DEVICE_CODE_PREFIX = "dc_"
        const val DEVICE_CODE_RANDOM_LENGTH = 32
        const val POLL_INTERVAL_SECONDS = 3
        const val POLL_KEY_PREFIX = "devcode:poll:"
        val CODE_TTL: Duration = Duration.ofSeconds(600)
        val POLL_KEY_TTL: Duration = Duration.ofMinutes(15)
        const val MAX_USER_CODE_ATTEMPTS = 5
        const val MAX_CAR_INFO_ID_LENGTH = 256
        const val MAX_DEVICE_CODE_LENGTH = 64
        const val MAX_LABEL_LENGTH = 40
        const val MAX_NICKNAME_LENGTH = 30
    }
}
