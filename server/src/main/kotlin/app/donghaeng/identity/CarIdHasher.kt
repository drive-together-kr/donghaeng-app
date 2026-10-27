package app.donghaeng.identity

import app.donghaeng.common.config.DhProperties
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * CarInfo 차량 ID 는 원문을 저장하지 않고 `SHA-256(carInfoId + salt)` hex 만 둔다 (docs/04 vehicle.car_info_id_hash, docs/06 §5).
 * salt 는 `DH_CAR_ID_SALT` — 바꾸면 기존 차량이 모두 "새 차량"으로 보이므로 운영 중 변경 금지.
 */
@Component
class CarIdHasher(private val salt: String) {

    @Autowired
    constructor(props: DhProperties) : this(props.carIdSalt)

    fun hash(carInfoId: String): String = SecureTokens.sha256Hex(carInfoId + salt)
}
