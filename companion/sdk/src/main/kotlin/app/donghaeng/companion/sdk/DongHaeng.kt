package app.donghaeng.companion.sdk

import android.util.Log
import app.donghaeng.companion.sdk.internal.DongHaengApi
import app.donghaeng.companion.sdk.internal.FakeDongHaengApi
import app.donghaeng.companion.sdk.internal.HttpDongHaengApi
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
 * 실패는 전부 [DongHaengException] 하위 타입으로 던진다.
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
     * [DongHaengConfig.provider] 가 [AccountProvider.DEV] 면 서버 없이 메모리 Fake 로 동작하고,
     * 그 외 제공자는 서버 REST 전송(T13)을 쓴다.
     *
     * @param config 서버 주소·계정 제공자·앱 버전
     * @param tokenProvider 호스트 앱의 계정 액세스 토큰 제공자
     * @throws IllegalArgumentException publicBaseUrl 이 비어 있을 때
     */
    public fun init(config: DongHaengConfig, tokenProvider: TokenProvider) {
        val baseUrl = config.publicBaseUrl.trim().trimEnd('/')
        require(baseUrl.isNotBlank()) { "publicBaseUrl 이 비어 있습니다" }
        val api: DongHaengApi = when (config.provider) {
            AccountProvider.DEV -> FakeDongHaengApi(tokenProvider)
            else -> HttpDongHaengApi(config.copy(publicBaseUrl = baseUrl), tokenProvider)
        }
        session = Session(config, baseUrl, api)
        // 토큰·닉네임은 남기지 않는다(CLAUDE.md 규칙 8). 서버 주소는 비밀이 아니다.
        Log.i(TAG, "init provider=${config.provider} base=$baseUrl appVersion=${config.appVersion}")
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
     * @param qrPayload 공식 앱 스캐너가 읽은 QR 문자열 그대로
     * @param vehicleId 합류할 내 차. 생략하면 대표 차량
     * @throws DongHaengException.InvalidQr 그룹 QR 이 아님(차량 등록 QR 이면 [pairVehicle] 을 쓰라는 이유가 담긴다)
     * @throws DongHaengException.OtherServer QR 의 호스트가 설정된 서버와 다름
     * @throws DongHaengException.Api `INVITE_EXPIRED`, `GROUP_FULL`, `NO_PAIRED_VEHICLE`, `ALREADY_MEMBER`
     */
    public suspend fun joinGroup(qrPayload: String, vehicleId: String? = null): GroupJoinResult {
        val s = requireSession()
        val token = when (val qr = QrParser.parse(qrPayload, s.baseUrl)) {
            is QrParser.Result.Join -> qr.token
            is QrParser.Result.Pair -> throw DongHaengException.InvalidQr("차량 등록용 QR입니다. pairVehicle 을 사용하세요")
            is QrParser.Result.OtherServer -> throw DongHaengException.OtherServer(qr.host)
            QrParser.Result.Unknown -> throw DongHaengException.InvalidQr("그룹 초대 QR 이 아닙니다")
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
     * @throws DongHaengException.InvalidQr 차량 등록 QR 이 아님
     * @throws DongHaengException.OtherServer QR 의 호스트가 설정된 서버와 다름
     * @throws DongHaengException.Api `CODE_EXPIRED`, `CODE_NOT_FOUND`
     */
    public suspend fun pairVehicle(qrPayload: String, nickname: String): PairResult {
        val s = requireSession()
        val code = when (val qr = QrParser.parse(qrPayload, s.baseUrl)) {
            is QrParser.Result.Pair -> qr.code
            is QrParser.Result.Join -> throw DongHaengException.InvalidQr("그룹 초대 QR입니다. joinGroup 을 사용하세요")
            is QrParser.Result.OtherServer -> throw DongHaengException.OtherServer(qr.host)
            QrParser.Result.Unknown -> throw DongHaengException.InvalidQr("차량 등록 QR 이 아닙니다")
        }
        return s.api.approvePairing(code, nickname.trim())
    }

    /**
     * 동승자 메시지를 그룹에 보낸다(`POST /groups/{groupId}/messages`). 그룹 차량에서는 음성으로 읽힌다.
     *
     * @param text 메시지 본문(공백만이면 [DongHaengException.Api] `INVALID_MESSAGE`)
     */
    public suspend fun sendPassengerMessage(groupId: String, text: String) {
        requireSession().api.sendPassengerMessage(groupId, text.trim())
    }

    /**
     * 그룹 상태 스트림. 멤버 위치·ETA·잔량과 최근 이벤트가 담긴 [GroupSnapshot] 을 약 2초마다(이벤트 발생 시 즉시) 내보낸다.
     * 구독을 취소하면 수신도 끝난다. 호출 시점에 [init] 이 안 되어 있으면 즉시 던진다.
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
