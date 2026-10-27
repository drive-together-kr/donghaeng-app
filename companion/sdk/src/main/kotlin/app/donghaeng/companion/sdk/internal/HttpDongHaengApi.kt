package app.donghaeng.companion.sdk.internal

import android.util.Log
import app.donghaeng.companion.sdk.DongHaengConfig
import app.donghaeng.companion.sdk.DongHaengException
import app.donghaeng.companion.sdk.GroupJoinResult
import app.donghaeng.companion.sdk.GroupMember
import app.donghaeng.companion.sdk.GroupSnapshot
import app.donghaeng.companion.sdk.MemberRole
import app.donghaeng.companion.sdk.MyVehicle
import app.donghaeng.companion.sdk.PairResult
import app.donghaeng.companion.sdk.TokenProvider
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.MemberState
import app.donghaeng.protocol.MemberStatus
import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.DeviceApproveRequest
import app.donghaeng.protocol.rest.ErrorCodes
import app.donghaeng.protocol.rest.ErrorEnvelope
import app.donghaeng.protocol.rest.ExchangeRequest
import app.donghaeng.protocol.rest.GroupDetailResponse
import app.donghaeng.protocol.rest.GroupJoinRequest
import app.donghaeng.protocol.rest.GroupJoinResponse
import app.donghaeng.protocol.rest.MemberDto
import app.donghaeng.protocol.rest.MyVehicleDto
import app.donghaeng.protocol.rest.PassengerMessageRequest
import app.donghaeng.protocol.rest.TokenResponse
import app.donghaeng.protocol.rest.VehicleSummary
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * REST 전송 구현 (docs/07 §3·§4.1, docs/03 §1~§2). DEV 를 포함한 모든 제공자가 이 구현을 쓴다.
 *
 * 인증:
 * 1. 첫 호출 때 `tokenProvider.accessToken()`(호스트 앱 토큰) → `POST /v1/auth/exchange { provider, accessToken }` → 동행 JWT 쌍을
 *    캐시한다. 만료는 JWT `exp`(없으면 55분)로 본다. 호스트 토큰은 교환 때만 서버로 간다.
 * 2. 이후 모든 요청은 `Authorization: Bearer <동행 JWT>`. 서버가 401 을 주면 교환을 **한 번** 다시 하고 재시도, 그래도 401 이면
 *    [DongHaengException.Unauthorized].
 *
 * 오류 매핑:
 * - 오류 봉투 `{ "error": { "code", "message", "traceId" } }` → [DongHaengException.Api] (code, 서버 문구)
 * - 봉투가 없는 오류 → `Api("HTTP_<status>", …)`
 * - [IOException](연결 실패·타임아웃) → `Api("NETWORK", "서버에 연결할 수 없어요")`
 * - 404 `NOT_FOUND` 도 그대로 전달한다 — 서버는 멤버가 아닌 그룹·나간 그룹을 404 로 숨긴다(docs/03 §2.3). 호스트는 `code` 로 분기한다
 *
 * [observeGroup] 은 `GET /v1/groups/{id}` 를 [pollIntervalMs](기본 15초)마다 폴링한다(수집 취소 시 종료). 푸시 전환은 이후 작업.
 * 토큰·닉네임·코드는 로그에 남기지 않는다(CLAUDE.md 규칙 8).
 *
 * @param client 테스트에서 바꿔 끼울 수 있다. 기본은 연결·읽기·쓰기 10초 타임아웃
 * @param pollIntervalMs [observeGroup] 폴링 간격
 * @param clock 캐시 만료 판단용 현재 시각(ms)
 */
internal class HttpDongHaengApi(
    private val config: DongHaengConfig,
    private val tokenProvider: TokenProvider,
    private val client: OkHttpClient = defaultClient(),
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : DongHaengApi {

    /** 끝 `/` 없는 서버 공개 주소. 경로는 [ApiPaths] 상수(`/v1/...`)를 그대로 붙인다 */
    private val baseUrl: String = config.publicBaseUrl.trim().trimEnd('/')
    private val userAgent: String = buildUserAgent(config)

    // ---------- 인증 세션 (동행 JWT 캐시) ----------

    private class Cached(val tokens: TokenResponse, val expiresAtMs: Long)

    private val authMutex = Mutex()

    @Volatile
    private var cached: Cached? = null

    /**
     * 유효한 동행 JWT 를 돌려준다. 캐시가 없거나 만료됐으면 교환한다.
     * @param rejected 서버가 401 로 거부한 토큰. 그 토큰이 아직 캐시에 있을 때만 다시 교환한다(동시 호출이 이미 갱신했으면 재사용)
     */
    private suspend fun bearer(rejected: String? = null): String = authMutex.withLock {
        val current = cached
        if (current != null && current.tokens.accessToken != rejected && clock() < current.expiresAtMs - EXPIRY_SKEW_MS) {
            return current.tokens.accessToken
        }
        val hostToken = tokenProvider.accessToken()
        if (hostToken.isBlank()) throw DongHaengException.Unauthorized()
        val body = DhJson.json.encodeToString(ExchangeRequest.serializer(), ExchangeRequest(config.provider.wireName, hostToken))
        val response = execute(request(ApiPaths.AUTH_EXCHANGE, "POST", body, bearer = null))
        if (response.code == 401) {
            Log.w(TAG, "exchange rejected provider=${config.provider.wireName} status=401")
            cached = null
            throw DongHaengException.Unauthorized()
        }
        if (!response.isSuccess) throw failure(ApiPaths.AUTH_EXCHANGE, response)
        val tokens = decode(TokenResponse.serializer(), response.body)
        val expiresAt = JwtClaims.expiresAtMs(tokens.accessToken) ?: (clock() + DEFAULT_TTL_MS)
        cached = Cached(tokens, expiresAt)
        Log.i(TAG, "exchange ok provider=${config.provider.wireName} ttlS=${(expiresAt - clock()) / 1000}")
        tokens.accessToken
    }

    // ---------- DongHaengApi ----------

    override suspend fun listMyVehicles(): List<MyVehicle> {
        val response = authed(ApiPaths.ME_VEHICLES, "GET")
        return decode(ListSerializer(MyVehicleDto.serializer()), response.body).map { it.toModel() }
    }

    override suspend fun joinGroup(inviteToken: String, vehicleId: String?): GroupJoinResult {
        val body = DhJson.json.encodeToString(GroupJoinRequest.serializer(), GroupJoinRequest(inviteToken, vehicleId))
        val response = authed(ApiPaths.GROUPS_JOIN, "POST", body)
        val joined = decode(GroupJoinResponse.serializer(), response.body)
        return GroupJoinResult(joined.groupId, joined.name, joined.members.map { it.toModel() })
    }

    override suspend fun approvePairing(userCode: String, nickname: String): PairResult {
        val body = DhJson.json.encodeToString(DeviceApproveRequest.serializer(), DeviceApproveRequest(userCode, nickname))
        val response = authed(ApiPaths.DEVICE_APPROVE, "POST", body)
        // 승인 시점에 차량 행이 만들어지므로 서버는 200 VehicleSummary 를 돌려준다(docs/03 §1.2 — 204 에서 변경)
        val vehicle = decode(VehicleSummary.serializer(), response.body)
        return PairResult(vehicle.vehicleId, vehicle.nickname)
    }

    override suspend fun sendPassengerMessage(groupId: String, text: String) {
        val body = DhJson.json.encodeToString(PassengerMessageRequest.serializer(), PassengerMessageRequest(text))
        authed(ApiPaths.groupMessages(groupId), "POST", body) // 200/201/204 모두 성공. 본문은 쓰지 않는다
    }

    override fun observeGroup(groupId: String): Flow<GroupSnapshot> = flow {
        while (true) {
            emit(fetchGroup(groupId))
            delay(pollIntervalMs)
        }
    }

    private suspend fun fetchGroup(groupId: String): GroupSnapshot {
        val response = authed(ApiPaths.group(groupId), "GET")
        val detail = decode(GroupDetailResponse.serializer(), response.body)
        // 최신 STATE 스냅샷이 있으면 그대로, 없으면 Member 목록을 오프라인 상태로 채운다
        val members = detail.snapshot?.members ?: detail.members.map { it.toOfflineState() }
        // 서버 recentEvents 는 최신순, GroupSnapshot.events 는 오래된 것부터
        return GroupSnapshot(detail.groupId, members, detail.recentEvents.asReversed())
    }

    // ---------- 요청 공통 ----------

    private class RawResponse(val code: Int, val body: String) {
        val isSuccess: Boolean get() = code in 200..299
    }

    /** 동행 JWT 를 실어 보내고, 401 이면 한 번 재교환 후 재시도한다. 2xx 응답만 돌려주고 나머지는 예외 */
    private suspend fun authed(path: String, method: String, jsonBody: String? = null): RawResponse {
        val first = bearer()
        var response = execute(request(path, method, jsonBody, first))
        if (response.code == 401) {
            Log.w(TAG, "401 → re-exchange path=$path")
            val second = bearer(rejected = first)
            response = execute(request(path, method, jsonBody, second))
            if (response.code == 401) {
                cached = null
                throw DongHaengException.Unauthorized()
            }
        }
        if (!response.isSuccess) throw failure(path, response)
        return response
    }

    private fun request(path: String, method: String, jsonBody: String?, bearer: String?): Request {
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("Accept", "application/json")
            .header("User-Agent", userAgent)
        if (bearer != null) builder.header("Authorization", "Bearer $bearer")
        when (method) {
            "GET" -> builder.get()
            else -> builder.method(method, (jsonBody ?: "{}").toRequestBody(JSON))
        }
        return builder.build()
    }

    /** OkHttp 비동기 호출을 suspend 로. 본문은 OkHttp 스레드에서 다 읽어 돌려준다. 취소하면 호출도 취소된다 */
    private suspend fun execute(request: Request): RawResponse {
        val raw = try {
            suspendCancellableCoroutine<RawResponse> { cont ->
                val call = client.newCall(request)
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(
                    object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }

                        override fun onResponse(call: Call, response: Response) {
                            val result = try {
                                response.use { RawResponse(it.code, it.body?.string().orEmpty()) }
                            } catch (e: IOException) {
                                if (cont.isActive) cont.resumeWithException(e)
                                return
                            }
                            if (cont.isActive) cont.resume(result)
                        }
                    },
                )
            }
        } catch (e: IOException) {
            Log.w(TAG, "network failure path=${request.url.encodedPath} cause=${e.javaClass.simpleName}")
            throw DongHaengException.Api("NETWORK", "서버에 연결할 수 없어요")
        }
        return raw
    }

    /** 2xx 가 아닌 응답 → [DongHaengException.Api]. 본문의 오류 봉투를 읽고, 없으면 HTTP 상태로 코드를 만든다 */
    private fun failure(path: String, response: RawResponse): DongHaengException {
        val envelope = runCatching { DhJson.json.decodeFromString(ErrorEnvelope.serializer(), response.body) }.getOrNull()
        val code = envelope?.error?.code?.takeIf { it.isNotBlank() } ?: "HTTP_${response.code}"
        val message = envelope?.error?.message?.takeIf { it.isNotBlank() } ?: "서버 오류가 났어요 (HTTP ${response.code})"
        Log.w(TAG, "http error status=${response.code} code=$code path=$path trace=${envelope?.error?.traceId}")
        return DongHaengException.Api(code, message)
    }

    private fun <T> decode(serializer: KSerializer<T>, body: String): T =
        try {
            DhJson.json.decodeFromString(serializer, body)
        } catch (e: Exception) {
            Log.w(TAG, "bad response body len=${body.length} cause=${e.javaClass.simpleName}")
            throw DongHaengException.Api("BAD_RESPONSE", "서버 응답을 읽을 수 없어요")
        }

    // ---------- DTO → 공개 모델 ----------

    private fun MyVehicleDto.toModel(): MyVehicle =
        MyVehicle(vehicleId, nickname, brand.orEmpty(), model.orEmpty(), isPrimary)

    private fun MemberDto.toModel(): GroupMember =
        GroupMember(vehicleId, nickname, color, if (role.equals("LEADER", ignoreCase = true)) MemberRole.LEADER else MemberRole.MEMBER)

    /** 스냅샷이 아직 없을 때의 멤버 상태: 위치·ETA·잔량 없음, 오프라인 */
    private fun MemberDto.toOfflineState(): MemberState = MemberState(
        vehicleId = vehicleId,
        nickname = nickname,
        color = color,
        online = false,
        lat = null,
        lon = null,
        heading = null,
        speedKph = null,
        ageMs = null,
        eta = null,
        energy = null,
        distanceToLeaderM = null,
        status = MemberStatus.OFFLINE,
    )

    internal companion object {
        private const val TAG = "DongHaeng"
        const val POLL_INTERVAL_MS = 15_000L
        private const val TIMEOUT_S = 10L

        /** JWT exp 가 없을 때의 캐시 수명(사용자 토큰 1h 보다 짧게) */
        private const val DEFAULT_TTL_MS = 55 * 60 * 1000L

        /** 만료 직전 토큰으로 요청하지 않도록 두는 여유 */
        private const val EXPIRY_SKEW_MS = 30 * 1000L

        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()

        /** 헤더에 들어갈 수 있는 ASCII 만 남긴다 */
        private fun buildUserAgent(config: DongHaengConfig): String {
            val version = config.appVersion.filter { it.code in 0x21..0x7e }.ifBlank { "0" }
            return "DongHaengCompanion/$version (${config.provider.wireName})"
        }
    }
}
