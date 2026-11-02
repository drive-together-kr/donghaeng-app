package app.donghaeng.group.web

import app.donghaeng.group.GroupErrors
import app.donghaeng.group.GroupService
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.rest.ApiHeaders
import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.GroupCreateRequest
import app.donghaeng.protocol.rest.GroupCreateResponse
import app.donghaeng.protocol.rest.GroupDetailResponse
import app.donghaeng.protocol.rest.GroupJoinRequest
import app.donghaeng.protocol.rest.GroupJoinResponse
import app.donghaeng.protocol.rest.GroupLeaveRequest
import app.donghaeng.protocol.rest.InviteDto
import app.donghaeng.protocol.rest.LeaderRequest
import app.donghaeng.protocol.rest.PassengerMessageRequest
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * docs/03 §2 그룹 API. 역할 제한은 `SecurityConfig`(POST /groups → ROLE_VEHICLE, POST /groups/join → ROLE_USER)와
 * 여기의 `requireVehicle`/`requireUser` 가 함께 지킨다. 나머지는 사용자·차량 토큰 모두 허용하고 서비스가 멤버·방장 여부를 본다.
 */
@RestController
@RequestMapping(ApiPaths.GROUPS)
class GroupController(private val groups: GroupService) {

    /** 차량 JWT → 201 { groupId, leaderVehicleId, invite, mqttTopics } */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@AuthenticationPrincipal jwt: Jwt, @RequestBody req: GroupCreateRequest): GroupCreateResponse =
        groups.create(jwt.requireVehicle(), req)

    /** 사용자 JWT → 200 { groupId, name, members } / 410 INVITE_EXPIRED / 409 GROUP_FULL·ALREADY_MEMBER / 404 NO_PAIRED_VEHICLE */
    @PostMapping("/join")
    fun join(@AuthenticationPrincipal jwt: Jwt, @RequestBody req: GroupJoinRequest): GroupJoinResponse =
        groups.join(jwt.requireUser(), req)

    /** 멤버 차량 또는 멤버 차량의 소유자 → 200 GroupDetailResponse / 404 */
    @GetMapping("/{groupId}")
    fun detail(@AuthenticationPrincipal jwt: Jwt, @PathVariable groupId: String): GroupDetailResponse =
        groups.detail(jwt.toCaller(), groupId)

    /** 방장 → 200 InviteDto (이전 초대는 만료) / 403 / 409 GROUP_ENDED */
    @PostMapping("/{groupId}/invites")
    fun reissueInvite(@AuthenticationPrincipal jwt: Jwt, @PathVariable groupId: String): InviteDto =
        groups.reissueInvite(jwt.toCaller(), groupId)

    /**
     * 차량(자기 자신) 또는 사용자(내 차) → 204 / 404 / 409 GROUP_ENDED.
     * 본문은 선택(`{ vehicleId? }`). 본문 없이 보내는 클라이언트가 Content-Type 을 아무렇게나 붙여도(HttpURLConnection 의 기본
     * `application/x-www-form-urlencoded` 등) 415 가 나지 않도록 문자열로 받아 직접 해석한다.
     */
    @PostMapping("/{groupId}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun leave(@AuthenticationPrincipal jwt: Jwt, @PathVariable groupId: String, @RequestBody(required = false) body: String?) {
        val req = body?.takeIf { it.isNotBlank() }?.let {
            try {
                DhJson.json.decodeFromString(GroupLeaveRequest.serializer(), it)
            } catch (e: Exception) {
                throw GroupErrors.validation("본문을 읽을 수 없어요")
            }
        }
        groups.leave(jwt.toCaller(), groupId, req)
    }

    /** 방장 → 204 / 403 / 409 GROUP_ENDED */
    @PostMapping("/{groupId}/end")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun end(@AuthenticationPrincipal jwt: Jwt, @PathVariable groupId: String) {
        groups.end(jwt.toCaller(), groupId)
    }

    /** 방장 → 200 GroupDetailResponse / 403 / 404(대상이 멤버가 아님) */
    @PostMapping("/{groupId}/leader")
    fun delegateLeader(@AuthenticationPrincipal jwt: Jwt, @PathVariable groupId: String, @RequestBody req: LeaderRequest): GroupDetailResponse =
        groups.delegateLeader(jwt.toCaller(), groupId, req)

    /**
     * 사용자 JWT(멤버 차량 소유자) → 201 GroupEvent(PASSENGER_MESSAGE) / 400 INVALID_MESSAGE·VALIDATION_FAILED(키 형식) / 404 / 409 / 429 RATE_LIMITED.
     * 선택 헤더 `Idempotency-Key` — 같은 키의 재시도는 첫 201 응답을 그대로 받는다(다시 저장·팬아웃 안 함, 계약 3).
     */
    @PostMapping("/{groupId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    fun postMessage(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable groupId: String,
        @RequestHeader(name = ApiHeaders.IDEMPOTENCY_KEY, required = false) idempotencyKey: String?,
        @RequestBody req: PassengerMessageRequest,
    ): GroupEvent =
        groups.postMessage(jwt.requireUser(), groupId, req, idempotencyKey)
}
