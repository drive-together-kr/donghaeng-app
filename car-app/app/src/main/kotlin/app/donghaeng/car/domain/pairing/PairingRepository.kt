package app.donghaeng.car.domain.pairing

import app.donghaeng.car.BuildConfig
import app.donghaeng.car.adapter.vehicle.VehicleAdapter
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.StoredCredentials
import app.donghaeng.car.core.common.TokenClearReason
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.common.VehicleIdFallback
import app.donghaeng.car.core.network.ApiResult
import app.donghaeng.car.core.network.IdentityApi
import app.donghaeng.car.core.network.safeApiCall
import app.donghaeng.protocol.rest.DeviceCodeRequest
import app.donghaeng.protocol.rest.DeviceCodeResponse
import app.donghaeng.protocol.rest.DeviceTokenRequest
import app.donghaeng.protocol.rest.DeviceTokenResponse
import javax.inject.Inject
import javax.inject.Singleton

/** CarInfo 도 폴백도 차량 ID 를 주지 못할 때(사실상 폴백 저장소 예외). 네트워크 전 단계라 [ApiResult.UnexpectedFailure] 로 전달. */
class VehicleIdUnavailableException : IllegalStateException("vehicleId unavailable (CarInfo + fallback)")

/**
 * 페어링 데이터 계층 (docs/03 §1.2, T12). ViewModel 은 이 클래스만 보고 Retrofit·TokenStore 를 모른다.
 *
 * - `vehicleId` 는 [VehicleAdapter.vehicleId] (fake: `FAKE-DH-0001`, pleos: CarInfo VIN/CRN). 서버는 해시만 저장한다.
 *   CarInfo 가 자리표시자(`"invalid"`, Pleos AVD 실측)만 주면 [VehicleIdFallback] 의 설치별 ID 를 쓴다 — AVD 두 대가 서로 다른 차량이어야 한다.
 * - `model` 은 빌드 변형(ADAPTER 대문자, 예: PLEOS / FAKE), `brand` 는 SDK 가 주지 않으므로 null, `appVersion` 은 VERSION_NAME.
 */
@Singleton
class PairingRepository @Inject constructor(
    private val api: IdentityApi,
    private val tokenStore: TokenStore,
    private val vehicleAdapter: VehicleAdapter,
    private val vehicleIdFallback: VehicleIdFallback,
) {
    /** 저장된 자격 증명(있으면 이미 연결됨). */
    suspend fun storedCredentials(): StoredCredentials? = tokenStore.load()

    /** `POST /v1/device/code`. 차량 ID 출처(CAR_INFO / INSTALL_ID)와 길이만 로그에 남긴다. */
    suspend fun requestCode(): ApiResult<DeviceCodeResponse> {
        val fromCar = runCatching { vehicleAdapter.vehicleId() }
            .onFailure { DhLog.w("vehicleId() 예외", it) }
            .getOrNull()
        val vehicleId = fromCar
            ?: runCatching { vehicleIdFallback.get() }.getOrNull()
            ?: return ApiResult.UnexpectedFailure(VehicleIdUnavailableException())
        DhLog.i("pairing vehicleId source=${if (fromCar != null) "CAR_INFO" else "INSTALL_ID"} len=${vehicleId.length}")
        val request = DeviceCodeRequest(
            vehicleId = vehicleId,
            model = BuildConfig.ADAPTER.uppercase(),
            brand = null,
            appVersion = BuildConfig.VERSION_NAME,
        )
        return safeApiCall { api.requestDeviceCode(request) }
    }

    /** `POST /v1/device/token` 한 번 폴링. 428/429/410/404 는 [ApiResult.HttpFailure] 하위 타입으로 돌아온다. */
    suspend fun pollToken(deviceCode: String): ApiResult<DeviceTokenResponse> =
        safeApiCall { api.requestDeviceToken(DeviceTokenRequest(deviceCode)) }

    /** 200 응답을 [TokenStore] 에 저장한다. */
    suspend fun saveCredentials(response: DeviceTokenResponse): StoredCredentials {
        val credentials = StoredCredentials(
            vehicleAccessToken = response.vehicleAccessToken,
            refreshToken = response.refreshToken,
            vehicleId = response.vehicle.vehicleId,
            nickname = response.vehicle.nickname,
            ownerId = response.vehicle.ownerId,
            mqttUri = response.mqtt.uri,
            mqttClientId = response.mqtt.clientId,
            mqttUsername = response.mqtt.username,
        )
        tokenStore.save(credentials)
        return credentials
    }

    /**
     * 연결 해제(설정 화면 / 반복 시연 / 저장소 초기화). 서버 측 폐기(`DELETE /me/vehicles/{id}`)는 폰 쪽 일이다.
     * 사유는 진단 기록(`dh_diag`)에 남는다 — 화면은 확인 창을 거친 뒤에만 부른다(T33, docs/10 R13).
     */
    suspend fun disconnect(reason: TokenClearReason) {
        DhLog.i("pairing disconnect requested reason=$reason")
        tokenStore.clear(reason)
    }

    /** 저장소를 읽을 수 없는 상태인가(T33) — 페어링 화면이 새 코드를 받지 않고 오류를 보인다 */
    fun storeUnavailableReason(): String? = (tokenStore.state.value as? TokenStoreState.Unavailable)?.reason
}
