package app.donghaeng.companion.sdk.internal

import app.donghaeng.companion.sdk.DongHaengConfig
import app.donghaeng.companion.sdk.GroupJoinResult
import app.donghaeng.companion.sdk.GroupSnapshot
import app.donghaeng.companion.sdk.MyVehicle
import app.donghaeng.companion.sdk.PairResult
import app.donghaeng.companion.sdk.TokenProvider
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * REST 전송 구현 — **T13 에서 채운다.** 지금은 스텁이며 호출하면 [NotImplementedError] 가 난다.
 *
 * 계획(docs/07 §4.1):
 * 1. 인증 — `tokenProvider.accessToken()`(호스트 앱 토큰) → `POST /v1/auth/exchange { provider, accessToken }` → 동행 JWT(사용자 토큰 1h)를
 *    캐시하고, 이후 모든 요청에 `Authorization: Bearer <동행 JWT>` 로 싣는다. 호스트 토큰을 매 요청에 보내지 않는다.
 *    401 이면 교환을 1회 재시도한 뒤 [app.donghaeng.companion.sdk.DongHaengException.Unauthorized].
 * 2. 호출 — `GET /v1/me/vehicles`, `POST /v1/groups/join`, `POST /v1/device/approve`, `POST /v1/groups/{id}/messages`.
 * 3. [observeGroup] — `GET /v1/groups/{id}` 15초 폴링으로 시작해 이후 푸시로 바꾼다(docs/07 §3).
 * 4. 그 외 오류 본문 `error.code` → `Api(code, message)`.
 *
 * DEV 제공자도 T13 부터 이 구현을 쓴다(dev 토큰 = `POST /v1/auth/login` 응답의 accessToken, docs/03 §1.1).
 */
internal class HttpDongHaengApi(
    private val config: DongHaengConfig,
    private val tokenProvider: TokenProvider,
) : DongHaengApi {

    @Suppress("unused") // TODO(T13): 요청 구현 시 사용
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val restBase: String get() = "${config.publicBaseUrl}/v1"

    override suspend fun listMyVehicles(): List<MyVehicle> =
        TODO("T13: GET $restBase/me/vehicles (provider=${config.provider})")

    override suspend fun joinGroup(inviteToken: String, vehicleId: String?): GroupJoinResult =
        TODO("T13: POST $restBase/groups/join")

    override suspend fun approvePairing(userCode: String, nickname: String): PairResult =
        TODO("T13: POST $restBase/device/approve")

    override suspend fun sendPassengerMessage(groupId: String, text: String): Unit =
        TODO("T13: POST $restBase/groups/{groupId}/messages")

    override fun observeGroup(groupId: String): Flow<GroupSnapshot> =
        TODO("T13: GET $restBase/groups/{groupId} 폴링 (tokenProvider=${tokenProvider::class.simpleName})")
}
