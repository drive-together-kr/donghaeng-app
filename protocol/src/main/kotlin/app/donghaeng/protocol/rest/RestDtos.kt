package app.donghaeng.protocol.rest

import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupState
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.Place
import kotlinx.serialization.Serializable

/*
 * REST 요청·응답 DTO — docs/03_API_프로토콜_명세.md §1~§3.
 * server(Spring MVC, kotlinx-serialization 컨버터)·car-app·companion/sdk 가 같은 클래스를 쓴다(CLAUDE.md 규칙 7).
 * 필드 이름은 문서의 camelCase 그대로. 시간은 epoch ms(Long).
 */

/** REST 경로 상수 (docs/03 머리말: Base URL = `{DH_PUBLIC_BASE_URL}/v1`). */
object ApiPaths {
    const val BASE = "/v1"
    const val AUTH_LOGIN = "$BASE/auth/login"
    const val AUTH_EXCHANGE = "$BASE/auth/exchange"
    const val AUTH_REFRESH = "$BASE/auth/refresh"
    const val DEVICE_CODE = "$BASE/device/code"
    const val DEVICE_TOKEN = "$BASE/device/token"
    const val DEVICE_APPROVE = "$BASE/device/approve"
    const val ME_VEHICLES = "$BASE/me/vehicles"
    const val ME_GROUPS = "$BASE/me/groups"
    const val GROUPS = "$BASE/groups"
    const val GROUPS_JOIN = "$BASE/groups/join"
    const val POI_REST_AREAS = "$BASE/poi/rest-areas"
    const val JWKS = "/.well-known/jwks.json"
    const val PAIR_PAGE = "/pair"

    fun meVehicle(vehicleId: String): String = "$ME_VEHICLES/$vehicleId"
    fun group(groupId: String): String = "$GROUPS/$groupId"
    fun groupInvites(groupId: String): String = "$GROUPS/$groupId/invites"
    fun groupLeave(groupId: String): String = "$GROUPS/$groupId/leave"
    fun groupEnd(groupId: String): String = "$GROUPS/$groupId/end"
    fun groupLeader(groupId: String): String = "$GROUPS/$groupId/leader"
    fun groupMessages(groupId: String): String = "$GROUPS/$groupId/messages"

    /** 차량 페어링 QR 내용: `{DH_PUBLIC_BASE_URL}/pair?c={userCode}` (하이픈 없는 6자리) */
    fun pairUrl(publicBaseUrl: String, userCode: String): String = "${publicBaseUrl.trimEnd('/')}$PAIR_PAGE?c=$userCode"

    /** 그룹 초대 QR 내용: `{DH_PUBLIC_BASE_URL}/j/{inviteToken}` */
    fun joinUrl(publicBaseUrl: String, inviteToken: String): String = "${publicBaseUrl.trimEnd('/')}/j/$inviteToken"
}

/** REST 요청 헤더 이름 (docs/03 §1.1). */
object ApiHeaders {
    /**
     * dev 간편 로그인 공유 키 — 서버가 `DH_DEV_LOGIN_KEY` 를 설정했을 때(클라우드) `POST /auth/login` 에 같은 값을 보내야 한다(아니면 403 `FORBIDDEN`).
     * 로컬 서버는 검사하지 않는다. 값은 저장소·로그에 남기지 않는다.
     */
    const val DEV_LOGIN_KEY = "X-Dev-Login-Key"
}

/** 오류 코드 상수 — docs/03 오류 형식 `{ "error": { "code", "message", "traceId" } }` 의 `code` 값. */
object ErrorCodes {
    // 공통
    const val VALIDATION_FAILED = "VALIDATION_FAILED"
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val FORBIDDEN = "FORBIDDEN"
    const val NOT_FOUND = "NOT_FOUND"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"

    // 인증 (§1.1)
    const val INVALID_TOKEN = "INVALID_TOKEN"
    const val PROVIDER_NOT_SUPPORTED = "PROVIDER_NOT_SUPPORTED"

    // 페어링 (§1.2) — Device Authorization Grant
    const val AUTHORIZATION_PENDING = "AUTHORIZATION_PENDING"   // 428: 아직 승인 전
    const val SLOW_DOWN = "SLOW_DOWN"                           // 429: interval 보다 빨리 폴링
    const val CODE_EXPIRED = "CODE_EXPIRED"                     // 410: 코드 만료
    const val CODE_NOT_FOUND = "CODE_NOT_FOUND"                 // 404: 없는 코드
    const val ALREADY_APPROVED = "ALREADY_APPROVED"             // 409: 이미 승인·소비됨

    // 그룹 (§2.2~§2.3)
    const val INVITE_EXPIRED = "INVITE_EXPIRED"       // 410: 초대 토큰이 없음·만료·사용 횟수 초과·그룹 종료 (구분하지 않음)
    const val GROUP_FULL = "GROUP_FULL"               // 409: maxMembers 도달
    const val NO_PAIRED_VEHICLE = "NO_PAIRED_VEHICLE" // 404: 합류할 내 차가 없음(대표 차량 없음 또는 vehicleId 가 내 활성 차량이 아님)
    const val ALREADY_MEMBER = "ALREADY_MEMBER"       // 409: 그 차량이 이미 이 그룹의 멤버
    const val GROUP_ENDED = "GROUP_ENDED"             // 409: 이미 종료된 그룹에 대한 변경 요청(나가기·종료·위임·초대·메시지)
    const val INVALID_MESSAGE = "INVALID_MESSAGE"     // 400: 동승자 메시지가 비었거나 60자 초과
}

/** 오류 응답 봉투 (클라이언트 파싱용). 서버는 같은 모양을 `common/api/ApiError` 로 만든다. */
@Serializable
data class ErrorEnvelope(val error: ErrorBody)

@Serializable
data class ErrorBody(
    val code: String,
    val message: String,
    val traceId: String? = null,
)

// ---------------------------------------------------------------- §1.1 사용자 인증 (companion 모듈 — 공식 앱 계정)

/** `POST /auth/login` — MVP 개발용 간편 로그인 (provider = "dev"). */
@Serializable
data class LoginRequest(
    val provider: String = "dev",
    val email: String,
    val displayName: String,
)

/** `POST /auth/exchange` — 호스트 공식 앱의 OAuth 2.0 access token 교환 (provider: bluelink | myhyundai | kia_connect | genesis_connected | dev). */
@Serializable
data class ExchangeRequest(
    val provider: String,
    val accessToken: String,
)

/** `POST /auth/refresh` */
@Serializable
data class RefreshRequest(
    val refreshToken: String,
)

@Serializable
data class UserDto(
    val userId: String,
    val displayName: String,
)

/** 로그인·교환·갱신 공통 응답: 사용자 토큰 쌍 */
@Serializable
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val user: UserDto,
)

// ---------------------------------------------------------------- §1.2 차량 페어링 — Device Authorization Grant (대체 수단)

/** `POST /device/code` (차량 앱, 인증 없음). `vehicleId` 는 CarInfo 에서 읽은 차량 ID 원문(서버는 해시만 저장). */
@Serializable
data class DeviceCodeRequest(
    val vehicleId: String,
    val model: String? = null,
    val brand: String? = null,
    val appVersion: String? = null,
)

@Serializable
data class DeviceCodeResponse(
    /** 차량만 보관 */
    val deviceCode: String,
    /** 화면 표시용 6자리 (하이픈 없음, 예: KQ74MZ — 표시할 때 `KQ7-4MZ` 로 나눠도 된다) */
    val userCode: String,
    /** QR 내용 = `{DH_PUBLIC_BASE_URL}/pair?c={userCode}` */
    val verifyUri: String,
    /** 초 */
    val expiresIn: Int,
    /** 폴링 간격(초) */
    val interval: Int,
)

/** `POST /device/token` (차량 앱 폴링). 428 AUTHORIZATION_PENDING / 429 SLOW_DOWN / 410 CODE_EXPIRED / 404 CODE_NOT_FOUND */
@Serializable
data class DeviceTokenRequest(
    val deviceCode: String,
)

@Serializable
data class VehicleSummary(
    val vehicleId: String,
    val nickname: String,
    val ownerId: String,
)

/** 차량 앱이 MQTT CONNECT 에 쓰는 값 (password = vehicleAccessToken) */
@Serializable
data class MqttInfo(
    val uri: String,
    val clientId: String,
    val username: String,
)

@Serializable
data class DeviceTokenResponse(
    val vehicleAccessToken: String,
    val refreshToken: String,
    val vehicle: VehicleSummary,
    val mqtt: MqttInfo,
)

/** `POST /device/approve` (companion 모듈 `pairVehicle`, 사용자 JWT) → 204 */
@Serializable
data class DeviceApproveRequest(
    val userCode: String,
    val nickname: String,
)

// ---------------------------------------------------------------- §1.3 내 차

/** `GET /me/vehicles` 항목 */
@Serializable
data class MyVehicleDto(
    val vehicleId: String,
    val nickname: String,
    val brand: String? = null,
    val model: String? = null,
    val isPrimary: Boolean = false,
)

/** `PATCH /me/vehicles/{vehicleId}` */
@Serializable
data class VehiclePatchRequest(
    val nickname: String? = null,
    val isPrimary: Boolean? = null,
)

// ---------------------------------------------------------------- §2 그룹 (서버 구현은 T14; 클라이언트는 이 모양으로 먼저 작성)

/** `POST /groups` (차량 JWT, 정차 중) */
@Serializable
data class GroupCreateRequest(
    val name: String,
    val destination: Place,
    val maxMembers: Int = 10,
)

@Serializable
data class InviteDto(
    val token: String,
    /** QR 내용 = `{DH_PUBLIC_BASE_URL}/j/{token}` */
    val url: String,
    val expiresAt: Long,
)

@Serializable
data class GroupCreateResponse(
    val groupId: String,
    val leaderVehicleId: String,
    val invite: InviteDto,
    val mqttTopics: GroupTopics,
)

/**
 * 초대 미리보기 — `GET {DH_PUBLIC_BASE_URL}/j/{token}` 을 `Accept: application/json` 으로 읽으면(인증 없음, 토큰 자체가 비밀) 돌려준다.
 * 호스트 앱이 QR 을 찍은 뒤 "함께 가기" 전에 그룹명·방장·목적지·인원을 보여 주는 용도. 사용 횟수에 포함되지 않는다. 만료·없음은 410 `INVITE_EXPIRED`.
 */
@Serializable
data class InvitePreviewResponse(
    val name: String,
    val leaderVehicleId: String,
    val leaderNickname: String,
    val destination: Place,
    val memberCount: Int,
    val maxMembers: Int,
    /** epoch ms */
    val expiresAt: Long,
)

/** `POST /groups/join` (사용자 JWT). vehicleId 생략 시 대표 차량 */
@Serializable
data class GroupJoinRequest(
    val inviteToken: String,
    val vehicleId: String? = null,
)

/** docs/03 §2.4 Member 객체 */
@Serializable
data class MemberDto(
    val vehicleId: String,
    val nickname: String,
    val color: String,
    /** LEADER | MEMBER */
    val role: String,
    val joinedAt: Long,
    val brand: String? = null,
    val model: String? = null,
)

@Serializable
data class GroupJoinResponse(
    val groupId: String,
    val name: String,
    val members: List<MemberDto>,
)

/** `GET /groups/{groupId}` — Group + members + 최신 스냅샷 (T14 에서 확정) */
@Serializable
data class GroupDetailResponse(
    val groupId: String,
    val name: String,
    val leaderVehicleId: String,
    /** ACTIVE | ENDED */
    val status: String,
    val destination: Place? = null,
    val members: List<MemberDto> = emptyList(),
    /** 최신 STATE 스냅샷 (없으면 null) */
    val snapshot: GroupState? = null,
    /** 최근 그룹 이벤트 (최신순, 최대 20) */
    val recentEvents: List<GroupEvent> = emptyList(),
)

/** `GET /me/groups?active=` 항목 — 내 차량이 멤버인 그룹 요약 (docs/03 §2.3, T14 확정) */
@Serializable
data class MyGroupDto(
    val groupId: String,
    val name: String,
    val leaderVehicleId: String,
    /** ACTIVE | ENDED */
    val status: String,
    val destination: Place? = null,
    /** 현재(나가지 않은) 멤버 수 */
    val memberCount: Int,
    /** 이 그룹에 들어가 있는 내 차량 ID 들 */
    val myVehicleIds: List<String>,
    /** epoch ms */
    val createdAt: Long,
    /** epoch ms. ACTIVE 면 null */
    val endedAt: Long? = null,
)

/**
 * `POST /groups/{groupId}/leave` 본문 (선택). 사용자 JWT 로 부를 때 어느 내 차를 뺄지 — 생략하면 이 그룹에 있는 내 차 전부.
 * 차량 JWT 로 부를 때는 본문을 무시한다(자기 자신).
 */
@Serializable
data class GroupLeaveRequest(
    val vehicleId: String? = null,
)

/** `POST /groups/{groupId}/messages` (companion 동승자) → 201 + 그룹 이벤트 `PASSENGER_MESSAGE`(GroupEvent) */
@Serializable
data class PassengerMessageRequest(
    val text: String,
    val fromVehicleId: String? = null,
)

/**
 * `POST /groups/{groupId}/voice` (차량 JWT, multipart: `audio` 파일 + `durationMs`) → 201 — F16 무전 업로드.
 * 같은 내용이 그룹 이벤트 `VOICE_MESSAGE`([app.donghaeng.protocol.VoiceMessagePayload])로 그룹 전체에 나간다.
 */
@Serializable
data class VoiceUploadResponse(
    val voiceId: String,
    val eventId: String,
    val durationMs: Int,
    val expiresAtMs: Long,
)

/** `POST /groups/{groupId}/leader` (방장 위임) → 200 GroupDetailResponse */
@Serializable
data class LeaderRequest(
    val vehicleId: String,
)
