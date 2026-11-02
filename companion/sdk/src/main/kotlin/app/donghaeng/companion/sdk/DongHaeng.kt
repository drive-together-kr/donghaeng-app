package app.donghaeng.companion.sdk

import android.util.Log
import app.donghaeng.companion.sdk.internal.DongHaengApi
import app.donghaeng.companion.sdk.internal.HttpDongHaengApi
import app.donghaeng.companion.sdk.internal.IdempotencyKeys
import app.donghaeng.companion.sdk.internal.QrParser
import kotlinx.coroutines.flow.Flow

/**
 * 동행 모듈의 진입점. 공식 앱(현대 블루링크·마이현대, 기아 커넥트, 제네시스 커넥티드 서비스)에 내장되어
 * "사람 확인이 필요한 일"(QR 스캔으로 그룹 합류, 동승자 메시지)을 맡는다.
 *
 * 사용 순서:
 * 1. 앱 시작(또는 로그인 직후) [init] — 서버 주소·계정 제공자·토큰 제공자 등록
 * 2. 공식 앱의 QR 스캐너가 읽은 문자열을 [joinGroup] 에 그대로 전달
 * 3. 합류한 그룹은 [observeGroup] 로 상태를 받고 [sendPassengerMessage] 로 동승자 메시지를 보낸다
 *
 * 자체 로그인 화면·토큰 저장소는 없다. 모든 호출은 호스트 앱이 [TokenProvider] 로 준 토큰으로 인증한다.
 * API 호출 실패는 전부 [DongHaengException] 하위 타입으로 던진다 — [TokenProvider] 가 던진 예외도 여기로 옮긴다
 * (입출력 오류 → `Api("NETWORK")`, 그 밖 → [DongHaengException.Unauthorized]). 호스트 프로그래밍 오류([init] 의 설정 오류,
 * [sendPassengerMessage] 의 키 형식)만 [IllegalArgumentException].
 */
public object DongHaeng {

    private const val TAG = "DongHaeng"

    private class Session(
        val config: DongHaengConfig,
        /** 끝 `/` 를 제거한 서버 공개 주소 */
        val baseUrl: String,
        val api: DongHaengApi,
    )

    @Volatile
    private var session: Session? = null

    /**
     * 모듈을 초기화한다. 여러 번 호출하면 마지막 설정으로 교체된다(계정 전환 시 다시 호출).
     *
     * 모든 제공자가 서버 REST 전송을 쓴다: 첫 호출 때 [TokenProvider.accessToken] 을 `POST /v1/auth/exchange { provider, accessToken }` 로
     * 동행 JWT 와 교환해 캐시하고, 이후 요청에 `Authorization: Bearer` 로 싣는다(401 → 재교환 1회 → [DongHaengException.Unauthorized]).
     * [AccountProvider.DEV] 도 같다 — 호스트 토큰은 서버 `DevTokenVerifier` 가 인정하는 `POST /v1/auth/login` 응답의 accessToken(docs/07 §4.2).
     * 여기서는 네트워크를 쓰지 않는다(교환은 첫 API 호출 때).
     *
     * @param config 서버 주소·계정 제공자·앱 버전
     * @param tokenProvider 호스트 앱의 계정 액세스 토큰 제공자
     * @throws IllegalArgumentException publicBaseUrl 이 비어 있거나 `http://`·`https://` 로 시작하지 않을 때
     */
    public fun init(config: DongHaengConfig, tokenProvider: TokenProvider) {
        initWithApi(config) { normalized -> HttpDongHaengApi(normalized, tokenProvider) }
    }

    /**
     * 테스트 전용 훅 — 설정 검증은 [init] 과 같고, 전송 구현만 [apiFactory] 가 만든 것(예: 메모리 `FakeDongHaengApi`)을 끼운다.
     * 공개 API 가 아니다. [apiFactory] 는 끝 `/` 를 제거한 publicBaseUrl 이 들어간 설정을 받는다.
     */
    internal fun initWithApi(config: DongHaengConfig, apiFactory: (DongHaengConfig) -> DongHaengApi) {
        val baseUrl = config.publicBaseUrl.trim().trimEnd('/')
        require(baseUrl.isNotBlank()) { "publicBaseUrl 이 비어 있습니다" }
        require(baseUrl.startsWith("http://", ignoreCase = true) || baseUrl.startsWith("https://", ignoreCase = true)) {
            "publicBaseUrl 은 http:// 또는 https:// 로 시작해야 합니다: $baseUrl"
        }
        val api = apiFactory(config.copy(publicBaseUrl = baseUrl))
        session = Session(config, baseUrl, api)
        // 토큰·닉네임은 남기지 않는다(CLAUDE.md 규칙 8). 서버 주소는 비밀이 아니다.
        Log.i(TAG, "init provider=${config.provider} base=$baseUrl appVersion=${config.appVersion} api=${api::class.simpleName}")
    }

    /**
     * 계정에 연결된 내 차 목록(`GET /me/vehicles`).
     *
     * @throws DongHaengException.NotInitialized [init] 전 호출
     * @throws DongHaengException.Unauthorized 토큰이 비었거나 거부됨
     */
    public suspend fun listMyVehicles(): List<MyVehicle> = requireSession().api.listMyVehicles()

    /**
     * 그룹 QR(`{base}/j/{token}`)로 내 차를 그룹에 합류시킨다(`POST /groups/join`).
     *
     * **멱등**: 그 차가 이미 이 그룹에 있으면(서버 409 `ALREADY_MEMBER`) 오류 대신 지금 그룹을 결과로 돌려준다 —
     * 합류 응답이 타임아웃으로 사라진 뒤 다시 누르거나, 앱을 다시 띄워 같은 QR 을 다시 찍어도 그룹으로 돌아온다
     * (모듈이 `GET /me/groups?active=true` 로 그 차의 그룹을 찾는다. 찾지 못하면 `ALREADY_MEMBER` 를 그대로 던진다).
     *
     * @param qrPayload 공식 앱 스캐너가 읽은 QR 문자열 그대로
     * @param vehicleId 합류할 내 차. 생략하면 대표 차량
     * @throws DongHaengException.InvalidQr 그룹 QR이 아님. 차량 등록 QR(`/pair?c=…`)이면 호스트 앱이 [pairVehicle] 을 불러야 한다
     *   — `reason` 은 사용자에게 그대로 보여 줄 수 있는 문구다
     * @throws DongHaengException.OtherServer QR 의 호스트가 설정된 서버와 다름
     * @throws DongHaengException.Api `INVITE_EXPIRED`, `GROUP_FULL`, `NO_PAIRED_VEHICLE`(, 활성 그룹을 찾지 못한 `ALREADY_MEMBER`)
     */
    public suspend fun joinGroup(qrPayload: String, vehicleId: String? = null): GroupJoinResult {
        val s = requireSession()
        val token = when (val qr = QrParser.parse(qrPayload, s.baseUrl)) {
            is QrParser.Result.Join -> qr.token
            is QrParser.Result.Pair -> throw DongHaengException.InvalidQr("차량 등록용 QR이에요. '차량 등록'에서 스캔해 주세요")
            is QrParser.Result.OtherServer -> throw DongHaengException.OtherServer(qr.host)
            QrParser.Result.Unknown -> throw DongHaengException.InvalidQr("그룹 초대 QR이 아니에요")
        }
        return s.api.joinGroup(token, vehicleId)
    }

    /**
     * 차량 등록 QR(`{base}/pair?c=CODE`)을 승인해 차를 계정에 연결한다(`POST /device/approve`, Device Authorization Grant).
     *
     * **대체 수단**이다: 공식 앱 계정에 이미 차량이 있으면 [listMyVehicles] 가 그대로 나오므로 이 단계가 필요 없고,
     * 계정에 차량이 없을 때(시승차·가족 차 등)만 차 화면의 QR 로 등록한다.
     *
     * @param qrPayload 차 화면 QR 문자열 그대로
     * @param nickname 이 차에 붙일 별명
     * @throws DongHaengException.InvalidQr 차량 등록 QR이 아님. 그룹 QR(`/j/{token}`)이면 호스트 앱이 [joinGroup] 을 불러야 한다
     *   — `reason` 은 사용자에게 그대로 보여 줄 수 있는 문구다
     * @throws DongHaengException.OtherServer QR 의 호스트가 설정된 서버와 다름
     * @throws DongHaengException.Api `CODE_EXPIRED`, `CODE_NOT_FOUND`
     */
    public suspend fun pairVehicle(qrPayload: String, nickname: String): PairResult {
        val s = requireSession()
        val code = when (val qr = QrParser.parse(qrPayload, s.baseUrl)) {
            is QrParser.Result.Pair -> qr.code
            is QrParser.Result.Join -> throw DongHaengException.InvalidQr("그룹 초대 QR이에요. '그룹 합류'에서 스캔해 주세요")
            is QrParser.Result.OtherServer -> throw DongHaengException.OtherServer(qr.host)
            QrParser.Result.Unknown -> throw DongHaengException.InvalidQr("차량 등록 QR이 아니에요")
        }
        return s.api.approvePairing(code, nickname.trim())
    }

    /**
     * 동승자 메시지를 그룹에 보낸다(`POST /groups/{groupId}/messages`). 그룹 차량에서는 음성으로 읽힌다.
     *
     * @param text 메시지 본문(공백만이면 [DongHaengException.Api] `INVALID_MESSAGE`)
     * @param fromVehicleId 동승자가 탄 내 차(이 그룹의 멤버). 차량에서 "{그 차 이름}에서 온 메시지"로 읽힌다.
     *   생략하면 이 프로세스에서 그 그룹에 [joinGroup] 할 때 고른 차, 그것도 없으면 서버 기본(이 그룹의 내 첫 차 — 대표 차량 우선).
     *   한 계정에 차가 여러 대면 넘기는 것이 맞다. 내 멤버 차가 아니면 `Api("NO_PAIRED_VEHICLE")`
     * @param idempotencyKey 사용자 동작 한 번(같은 메시지 보내기)마다 호스트가 만든 키(`[A-Za-z0-9_-]{8,64}`, 예: UUID).
     *   응답이 사라져 다시 보낼 때 **같은 키**를 주면 서버가 한 번만 처리해 차에서 두 번 읽히지 않는다(헤더 `Idempotency-Key`, docs/03).
     *   생략하면 호출마다 새 키
     * @throws IllegalArgumentException [idempotencyKey] 형식이 틀림(호스트 프로그래밍 오류 — 서버로 보내지 않는다)
     */
    public suspend fun sendPassengerMessage(
        groupId: String,
        text: String,
        fromVehicleId: String? = null,
        idempotencyKey: String? = null,
    ) {
        val api = requireSession().api
        val key = idempotencyKey?.trim()?.also {
            require(IdempotencyKeys.isValid(it)) { "idempotencyKey 는 영문·숫자·_- 8~64자여야 합니다" }
        } ?: IdempotencyKeys.generate()
        api.sendPassengerMessage(groupId, text.trim(), fromVehicleId?.trim()?.takeIf { it.isNotEmpty() }, key)
    }

    /**
     * 그룹 상태 스트림. 멤버 위치·ETA·잔량·지금 방장과 최근 이벤트가 담긴 [GroupSnapshot] 을 내보낸다.
     * 발행 주기는 서버 전송 방식이 정한다 — MVP 는 `GET /v1/groups/{id}` 15초 폴링, 이후 푸시로 바뀌면 이벤트 즉시(docs/07 §3).
     * 구독을 취소하면 폴링도 끝난다. 호출 시점에 [init] 이 안 되어 있으면 즉시 던진다.
     *
     * 끝나는 경우:
     * - **그룹 종료**(서버 상태 `ENDED`): 그 마지막 스냅샷(보통 `GROUP_ENDED` 이벤트 포함)을 내보낸 뒤 흐름이 **정상 종료**한다(더 폴링하지 않음).
     * - **오류**: 흐름의 예외로 전달된다. 서버가 그룹을 숨김(나감·내보내짐) = `Api("NOT_FOUND")`, 인증 = [DongHaengException.Unauthorized],
     *   연결 실패·타임아웃 = `Api("NETWORK")`, 봉투 없는 5xx = `Api("HTTP_5xx")`. **일시 오류(NETWORK·5xx·429) 뒤 다시 구독하는 것은 호스트 몫**이다 —
     *   예: `retryWhen { e, n -> e is DongHaengException.Api && e.code == "NETWORK" && n < … }` 또는 demo-host `GroupObservation`
     *   (백오프 재구독 + 화면에 '다시 연결 중' 표시). 모듈이 조용히 재시도하지 않는 이유는 호스트가 끊김을 화면에 보여 줄 수 있게 하려는 것이다.
     *
     * @throws DongHaengException.NotInitialized [init] 전 호출
     */
    public fun observeGroup(groupId: String): Flow<GroupSnapshot> = requireSession().api.observeGroup(groupId)

    /** 테스트 전용 — 초기화 상태를 지운다 */
    internal fun reset() {
        session = null
    }

    private fun requireSession(): Session = session ?: throw DongHaengException.NotInitialized()
}
