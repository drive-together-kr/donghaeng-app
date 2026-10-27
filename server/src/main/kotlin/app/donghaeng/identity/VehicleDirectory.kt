package app.donghaeng.identity

import app.donghaeng.identity.repo.VehicleRecord
import app.donghaeng.identity.repo.VehicleRepository
import app.donghaeng.protocol.rest.MyVehicleDto
import org.springframework.stereotype.Component

/**
 * 계정에 연결된 차량 조회 (docs/06 §2, docs/07 §4.1).
 * 실서비스는 OEM 차량 API(공식 앱 계정의 차량 목록) 구현으로 바꿔 끼우고, MVP 는 동행 DB(페어링으로 등록된 차량)를 본다.
 */
interface VehicleDirectory {
    fun vehiclesOf(userId: String): List<MyVehicleDto>
}

/** MVP 구현 — `vehicle` 테이블의 활성 차량(해제되지 않은 것). 대표 차량이 먼저 온다. */
@Component
class DbVehicleDirectory(private val vehicles: VehicleRepository) : VehicleDirectory {

    override fun vehiclesOf(userId: String): List<MyVehicleDto> =
        vehicles.listActiveByOwner(userId).map { it.toMyVehicleDto() }
}

internal fun VehicleRecord.toMyVehicleDto() = MyVehicleDto(
    vehicleId = vehicleId,
    nickname = nickname,
    brand = brand,
    model = model,
    isPrimary = isPrimary,
)
