package app.donghaeng.group

import app.donghaeng.common.config.DhProperties
import app.donghaeng.common.id.Ids
import app.donghaeng.common.idempotency.Idempotency
import app.donghaeng.common.text.TextSanitizer
import app.donghaeng.common.tx.afterCommit
import app.donghaeng.common.web.RateLimitedException
import app.donghaeng.event.GroupEventReader
import app.donghaeng.event.GroupEventSink
import app.donghaeng.event.SpeechComposer
import app.donghaeng.group.repo.GroupInviteRepository
import app.donghaeng.group.repo.GroupMemberRepository
import app.donghaeng.group.repo.GroupRecord
import app.donghaeng.group.repo.GroupRepository
import app.donghaeng.group.repo.GroupStatus
import app.donghaeng.group.repo.MemberRecord
import app.donghaeng.group.repo.MemberRole
import app.donghaeng.identity.IdentityApi
import app.donghaeng.presence.PresenceApi
import app.donghaeng.identity.VehicleInfo
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupEnded
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.GroupJoined
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.Place
import app.donghaeng.protocol.Severity
import app.donghaeng.protocol.Topics
import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.GroupCreateRequest
import app.donghaeng.protocol.rest.GroupCreateResponse
import app.donghaeng.protocol.rest.GroupDetailResponse
import app.donghaeng.protocol.rest.GroupJoinRequest
import app.donghaeng.protocol.rest.GroupJoinResponse
import app.donghaeng.protocol.rest.GroupLeaveRequest
import app.donghaeng.protocol.rest.InviteDto
import app.donghaeng.protocol.rest.InvitePreviewResponse
import app.donghaeng.protocol.rest.LeaderRequest
import app.donghaeng.protocol.rest.MemberDto
import app.donghaeng.protocol.rest.MemberLeftReason
import app.donghaeng.protocol.rest.MyGroupDto
import app.donghaeng.protocol.rest.PassengerMessageRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 그룹 수명주기 (docs/03 §2): 생성·초대·합류·조회·나가기·종료·방장 위임·동승자 메시지.
 *
 * 규칙(T14 확정, docs/03 §2.5):
 * - 차량 하나는 **활성 그룹 하나**에만 있다. 다른 그룹을 만들거나 다른 초대로 합류하면 이전 그룹에서 자동으로 나간다(`MEMBER_LEFT`).
 *   같은 그룹에 다시 합류하면 409 `ALREADY_MEMBER`.
 * - 방장이 나가면 가장 먼저 합류한 남은 멤버가 방장이 된다(`LEADER_CHANGED`). 마지막 멤버가 나가면 그룹은 종료된다(reason `ALL_LEFT`).
 * - 초대 토큰은 10분, 사용 횟수 `3 × (maxMembers - 1)`(정원은 GROUP_FULL 이 지킨다). 재발급하면 이전 초대는 즉시 만료된다. 그룹이 끝나면 초대도 끝난다.
 * - 잠금 순서: 차량 행(`FOR KEY SHARE`, 생성·합류) → 차량 advisory lock → 그룹 행(FOR UPDATE, 여러 개면 ID 순) → 초대 행.
 *   차량 행을 먼저 잡는 이유(C108): 재페어링(`VehicleRegistry.register`)이 차량 행 `FOR UPDATE` → `evictVehicle`(차량 advisory) 순서라, 합류·생성이
 *   advisory 를 쥔 채 `group_member`/`travel_group` INSERT 의 FK 검사(차량 행 KEY SHARE)로 차량 행을 기다리면 교착(40P01 → 500)이었다.
 *   또 잠금 뒤 차량을 **다시 읽어** 그 사이 커밋된 해제·이전을 본다(해제된 차가 그룹에 들어가지 않게). 나가기·해제의 축출은 차량 행을 잡지 않는다.
 * - Redis 소속 캐시·차량 CMD 는 커밋 뒤에만 내보낸다([afterCommit]).
 */
@Service
class GroupService(
    private val groups: GroupRepository,
    private val members: GroupMemberRepository,
    private val invites: GroupInviteRepository,
    private val identity: IdentityApi,
    private val events: GroupEventSink,
    private val eventReader: GroupEventReader,
    private val speech: SpeechComposer,
    private val cache: GroupMembershipCache,
    private val commands: VehicleCommandPort,
    private val presence: PresenceApi,
    private val props: DhProperties,
    private val idempotency: Idempotency,
    private val appEvents: ApplicationEventPublisher,
    private val clock: Clock,
) {

    // ---------------------------------------------------------------- §2.1 생성 (차량 JWT)

    @Transactional
    fun create(caller: Caller.Vehicle, req: GroupCreateRequest): GroupCreateResponse {
        // 그룹 이름은 모든 차의 화면·음성에 나간다 — 제어·서식 문자를 지운 뒤 길이를 본다(C105)
        val name = TextSanitizer.normalize(req.name)
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) throw GroupErrors.validation("name: 1~${MAX_NAME_LENGTH}자여야 해요")
        if (req.maxMembers !in MIN_MEMBERS..MAX_MEMBERS) throw GroupErrors.validation("maxMembers: ${MIN_MEMBERS}~${MAX_MEMBERS} 사이여야 해요")
        val destination = validDestination(req.destination)
        requireActiveVehicle(caller) // 잠그기 전 빠른 거절(해제된 차량 403)

        // C108: 차량 행(KEY SHARE) → 차량 advisory → 다시 읽어 확인 (클래스 설명의 잠금 순서)
        identity.lockVehicleForShare(caller.vehicleId)
        members.lockVehicle(caller.vehicleId)
        val vehicle = requireActiveVehicle(caller)
        val now = clock.instant()
        leavePreviousGroupIfAny(vehicle, now, reason = LEFT_REASON_SWITCHED)

        val group = groups.insert(Ids.group(), name, vehicle.vehicleId, destination, req.maxMembers)
        members.upsertJoin(group.groupId, vehicle.vehicleId, MemberRole.LEADER, MemberColors.next(emptyList()), vehicle.ownerId, now)
        val invite = issueInvite(group, now)
        afterCommit { cache.joined(group.groupId, vehicle.vehicleId) }
        log.info("group created groupId={} leaderVehicleId={} maxMembers={}", group.groupId, vehicle.vehicleId, group.maxMembers)
        return GroupCreateResponse(
            groupId = group.groupId,
            leaderVehicleId = vehicle.vehicleId,
            invite = invite,
            mqttTopics = topicsOf(group.groupId),
        )
    }

    /** `POST /groups/{id}/invites` — 방장(차량 또는 그 소유자)만. 이전 초대는 즉시 만료 */
    @Transactional
    fun reissueInvite(caller: Caller, groupId: String): InviteDto {
        val group = lockActiveGroup(groupId)
        requireLeader(caller, group)
        val now = clock.instant()
        val expired = invites.expireAllForGroup(groupId, now)
        log.info("invite reissued groupId={} expiredPrevious={}", groupId, expired)
        return issueInvite(group, now)
    }

    /** `GET /j/{token}` (JSON) — 합류 전 미리보기. 없음·형식 오류·만료·사용 횟수 초과·그룹 종료는 모두 410 */
    @Transactional(readOnly = true)
    fun previewInvite(rawToken: String): InvitePreviewResponse {
        val token = InviteTokens.normalize(rawToken) ?: throw GroupErrors.inviteExpired()
        val invite = invites.findByHash(InviteTokens.hash(token)) ?: throw GroupErrors.inviteExpired()
        val now = clock.instant()
        val group = groups.findById(invite.groupId) ?: throw GroupErrors.inviteExpired()
        if (!group.active || invite.expired(now) || invite.exhausted) throw GroupErrors.inviteExpired()
        val current = members.listActive(group.groupId)
        val leader = identity.vehicle(group.leaderVehicleId)
        return InvitePreviewResponse(
            name = group.name,
            leaderVehicleId = group.leaderVehicleId,
            leaderNickname = leader?.nickname ?: group.leaderVehicleId,
            destination = checkNotNull(group.destination) { "그룹에 목적지가 없어요" },
            memberCount = current.size,
            maxMembers = group.maxMembers,
            expiresAt = invite.expiresAt.toEpochMilli(),
        )
    }

    // ---------------------------------------------------------------- §2.2 합류 (사용자 JWT)

    @Transactional
    fun join(caller: Caller.User, req: GroupJoinRequest): GroupJoinResponse {
        // 1) 합류할 내 차 (vehicleId 생략 시 대표 차량) — docs/03 §2.2 검사 순서: 차량(404) → 토큰(410)
        val candidate = resolveJoinVehicle(caller.userId, req.vehicleId)
        val token = InviteTokens.normalize(req.inviteToken) ?: throw GroupErrors.inviteExpired()
        val tokenHash = InviteTokens.hash(token)
        // C108: 차량 행(KEY SHARE) → 차량 advisory 를 잡은 뒤 **다시 읽는다** — 그 사이 커밋된 해제·소유권 이전이면 404(해제된 차가 멤버가 되지 않게)
        identity.lockVehicleForShare(candidate.vehicleId)
        members.lockVehicle(candidate.vehicleId)
        val vehicle = identity.vehicle(candidate.vehicleId)?.takeIf { it.active && it.ownerId == caller.userId }
            ?: throw GroupErrors.notMyVehicle()

        // 2) 초대를 잠금 없이 읽어 그룹을 알아낸 뒤, 그룹 행(이전 그룹 포함, ID 순) → 초대 행 순서로 잠근다
        val peek = invites.findByHash(tokenHash) ?: throw GroupErrors.inviteExpired()
        val previousPeek = members.findActiveMembershipOf(vehicle.vehicleId)
        if (previousPeek != null && previousPeek.groupId == peek.groupId) throw GroupErrors.alreadyMember()
        val locked = lockGroups(listOfNotNull(peek.groupId, previousPeek?.groupId))
        val group = locked[peek.groupId] ?: throw GroupErrors.inviteExpired()
        val invite = invites.findByHashForUpdate(tokenHash) ?: throw GroupErrors.inviteExpired()
        // 잠금 뒤 다시 읽는다: 그 사이 이전 그룹에서 역할이 바뀌었을 수 있다(예: 방장 승계). 차량 잠금을 쥐고 있어 그룹 자체는 바뀌지 않는다
        val previous = previousPeek?.let { members.findActive(it.groupId, vehicle.vehicleId) }
        val now = clock.instant()
        if (!group.active || invite.expired(now)) throw GroupErrors.inviteExpired()

        // 3) 정원(409 가 사용 횟수 초과 410 보다 먼저 — 사용자에게 더 유용한 이유) → 이전 그룹에서 자동 이탈 → 합류
        val current = members.listActive(group.groupId)
        if (current.size >= group.maxMembers) throw GroupErrors.groupFull(group.maxMembers)
        if (invite.exhausted) throw GroupErrors.inviteExpired()
        if (previous != null) {
            val previousGroup = locked[previous.groupId]
            if (previousGroup != null && previousGroup.active) removeMember(previousGroup, previous, vehicle, now, LEFT_REASON_SWITCHED)
        }
        val color = MemberColors.next(current.map { it.color })
        members.upsertJoin(group.groupId, vehicle.vehicleId, MemberRole.MEMBER, color, caller.userId, now)
        invites.incrementUse(invite.tokenHash)
        events.record(
            groupId = group.groupId,
            type = GroupEventType.MEMBER_JOINED,
            sourceVehicleId = vehicle.vehicleId,
            severity = Severity.INFO,
            speech = speech.memberJoined(vehicle.nickname),
            payload = buildJsonObject {
                put("vehicleId", vehicle.vehicleId)
                put("nickname", vehicle.nickname)
                put("color", color)
                put("role", MemberRole.MEMBER.name)
            },
        )
        val joinedCmd = joinedCmdOf(group)
        afterCommit {
            cache.joined(group.groupId, vehicle.vehicleId)
            commands.groupJoined(vehicle.vehicleId, joinedCmd)
            // C032: GROUP_JOINED 를 보낸 **뒤** — 그룹 경로에 경유지가 있으면 routesync 가 이 차에만 경로 제안을 다시 보낸다
            appEvents.publishEvent(VehicleJoinedGroup(group.groupId, vehicle.vehicleId, group.leaderVehicleId, joinedCmd.destination, group.waypoints))
        }
        log.info("member joined groupId={} vehicleId={} members={} switchedFrom={}", group.groupId, vehicle.vehicleId, current.size + 1, previous?.groupId)
        return GroupJoinResponse(groupId = group.groupId, name = group.name, members = memberDtos(members.listActive(group.groupId)))
    }

    // ---------------------------------------------------------------- §2.3 조회

    @Transactional(readOnly = true)
    fun detail(caller: Caller, groupId: String): GroupDetailResponse {
        if (caller is Caller.Vehicle) requireActiveVehicle(caller)
        val group = groups.findById(groupId) ?: throw GroupErrors.groupNotFound()
        val current = members.listActive(groupId)
        requireViewer(caller, current)
        return detailOf(group, current)
    }

    /**
     * `GET /v1/vehicles/me/group` (계약 1, docs/03 §2.3) — 차량 JWT 의 차가 **지금** 들어가 있는 진행 중 그룹을 cmd `GROUP_JOINED` 본문과 같은 모양으로.
     * 없으면 null(→ 204). 차량 앱이 앱 시작·MQTT 재접속 때 로컬 세션을 서버와 맞춘다(놓친 GROUP_ENDED·MEMBER_LEFT·GROUP_JOINED 복구).
     * 진실(DB) 기준으로 답하고, Redis 소속 캐시가 다르면 커밋 뒤 고친다(C101 read-repair). 해제·이전된 차량 토큰은 403.
     */
    @Transactional(readOnly = true)
    fun currentGroupOf(caller: Caller.Vehicle): GroupJoined? {
        val vehicle = requireActiveVehicle(caller)
        val membership = members.findActiveMembershipOf(vehicle.vehicleId)
        val group = membership?.let { groups.findById(it.groupId) }?.takeIf { it.active }
        val cached = cache.activeGroupOf(vehicle.vehicleId)
        if (cached != group?.groupId) afterCommit { cache.repair(vehicle.vehicleId, cached, group?.groupId) }
        return group?.let(::joinedCmdOf)
    }

    /** `GET /me/groups?active=` — 내 활성 차량이 멤버인 그룹. ACTIVE 먼저, 그다음 최근 생성순 */
    @Transactional(readOnly = true)
    fun myGroups(caller: Caller.User, active: Boolean?): List<MyGroupDto> {
        val myVehicleIds = identity.activeVehiclesOf(caller.userId).map { it.vehicleId }
        val memberships = members.listActiveMembershipsOf(myVehicleIds)
        if (memberships.isEmpty()) return emptyList()
        val byGroup = memberships.groupBy { it.groupId }
        val wanted = groups.findAllByIds(byGroup.keys).filter { g ->
            when (active) {
                null -> true
                true -> g.status == GroupStatus.ACTIVE
                false -> g.status == GroupStatus.ENDED
            }
        }
        val counts = members.countActiveByGroups(wanted.map { it.groupId })
        return wanted
            .sortedWith(compareBy<GroupRecord> { it.status != GroupStatus.ACTIVE }.thenByDescending { it.createdAt })
            .map { g ->
                MyGroupDto(
                    groupId = g.groupId,
                    name = g.name,
                    leaderVehicleId = g.leaderVehicleId,
                    status = g.status.name,
                    destination = g.destination,
                    memberCount = counts[g.groupId] ?: 0,
                    myVehicleIds = byGroup.getValue(g.groupId).map { it.vehicleId }.sorted(),
                    createdAt = g.createdAt.toEpochMilli(),
                    endedAt = g.endedAt?.toEpochMilli(),
                )
            }
    }

    // ---------------------------------------------------------------- §2.3 나가기 · 종료 · 위임

    /** `POST /groups/{id}/leave` — 차량 토큰: 자기 자신. 사용자 토큰: `vehicleId`(내 차) 또는 생략 시 이 그룹의 내 차 전부 */
    @Transactional
    fun leave(caller: Caller, groupId: String, req: GroupLeaveRequest?) {
        val now = clock.instant()
        val leaving: List<VehicleInfo> = when (caller) {
            is Caller.Vehicle -> listOf(requireActiveVehicle(caller))
            is Caller.User -> {
                val mine = identity.activeVehiclesOf(caller.userId)
                val requested = req?.vehicleId?.trim()?.takeIf { it.isNotEmpty() }
                if (requested != null) {
                    listOf(mine.firstOrNull { it.vehicleId == requested } ?: throw GroupErrors.notMyMemberVehicle())
                } else {
                    mine
                }
            }
        }
        leaving.map { it.vehicleId }.sorted().forEach { members.lockVehicle(it) }
        val group = lockActiveGroup(groupId)
        val current = members.listActive(groupId).associateBy { it.vehicleId }
        val targets = leaving.filter { current.containsKey(it.vehicleId) }
        if (targets.isEmpty()) {
            throw if (caller is Caller.Vehicle) GroupErrors.memberNotFound() else GroupErrors.notMyMemberVehicle()
        }
        for (vehicle in targets) {
            val refreshed = groups.findById(group.groupId) ?: break
            if (!refreshed.active) break // 앞선 멤버 이탈로 그룹이 끝났다
            val member = members.findActive(groupId, vehicle.vehicleId) ?: continue
            removeMember(refreshed, member, vehicle, now, LEFT_REASON_LEFT)
        }
    }

    /** `POST /groups/{id}/end` — 방장(차량 또는 그 소유자)만. 그룹 종료 + 위치 데이터(Redis) 삭제 */
    @Transactional
    fun end(caller: Caller, groupId: String) {
        val group = lockActiveGroup(groupId)
        requireLeader(caller, group)
        endGroup(group, SpeechComposer.REASON_LEADER_ENDED, clock.instant())
    }

    /** `POST /groups/{id}/leader` — 방장만. 대상은 현재 멤버 */
    @Transactional
    fun delegateLeader(caller: Caller, groupId: String, req: LeaderRequest): GroupDetailResponse {
        val group = lockActiveGroup(groupId)
        requireLeader(caller, group)
        val target = req.vehicleId.trim()
        if (target.isEmpty()) throw GroupErrors.validation("vehicleId: 비어 있어요")
        if (target == group.leaderVehicleId) throw GroupErrors.validation("vehicleId: 이미 방장이에요")
        val member = members.findActive(groupId, target) ?: throw GroupErrors.memberNotFound()
        changeLeader(group, from = group.leaderVehicleId, to = member.vehicleId, speechText = true)
        log.info("leader delegated groupId={} from={} to={}", groupId, group.leaderVehicleId, member.vehicleId)
        val refreshed = groups.findById(groupId)!!
        return detailOf(refreshed, members.listActive(groupId))
    }

    // ---------------------------------------------------------------- §2.3 동승자 메시지 (사용자 JWT)

    /**
     * `POST /groups/{id}/messages` → `PASSENGER_MESSAGE` 그룹 이벤트. 보낸 차 = `fromVehicleId`(내 멤버 차) 또는 이 그룹의 내 첫 차.
     *
     * - 재시도 안전(계약 3): [idempotencyKey](`Idempotency-Key` 헤더)가 있으면 같은 (사용자, 경로, 키)의 첫 201 응답을 24시간 기억해 다시 오면 그대로 돌려준다
     *   (다시 저장·팬아웃하지 않는다 — 운전자가 같은 메시지를 두 번 듣지 않게).
     * - 빈도 제한(C106): 같은 그룹의 동승자 메시지는 `dh.rate-limit.passenger-message-interval-ms`(2 s)에 한 건 — 넘으면 429 `RATE_LIMITED`(+`Retry-After`).
     *   그룹 행 `FOR UPDATE` 뒤 마지막 메시지 시각(DB)과 비교하므로 동시에 보내도 한 건만 통과한다.
     */
    @Transactional
    fun postMessage(caller: Caller.User, groupId: String, req: PassengerMessageRequest, idempotencyKey: String? = null): GroupEvent {
        val key = Idempotency.parseKey(idempotencyKey)
        return idempotency.run(caller.userId, ApiPaths.groupMessages(groupId), key, 201, GroupEvent.serializer()) {
            recordMessage(caller, groupId, req)
        }
    }

    private fun recordMessage(caller: Caller.User, groupId: String, req: PassengerMessageRequest): GroupEvent {
        val text = normalizeMessage(req.text)
        val group = groups.findByIdForUpdate(groupId) ?: throw GroupErrors.groupNotFound()
        val current = members.listActive(groupId)
        val mine = identity.activeVehiclesOf(caller.userId).filter { v -> current.any { it.vehicleId == v.vehicleId } }
        if (mine.isEmpty()) throw GroupErrors.groupNotFound() // 멤버가 아니면 그룹 존재도 숨긴다
        if (!group.active) throw GroupErrors.groupEnded()
        val from = req.fromVehicleId?.trim()?.takeIf { it.isNotEmpty() }
        val sender = if (from == null) mine.first() else mine.firstOrNull { it.vehicleId == from } ?: throw GroupErrors.notMyMemberVehicle()
        requireMessageInterval(groupId)
        val event = events.record(
            groupId = groupId,
            type = GroupEventType.PASSENGER_MESSAGE,
            sourceVehicleId = sender.vehicleId,
            severity = Severity.INFO,
            speech = speech.passengerMessage(sender.nickname, text),
            payload = buildJsonObject { put("text", text) },
        )
        // 본문·닉네임은 로그에 남기지 않는다 (CLAUDE.md 규칙 8)
        log.info("passenger message groupId={} fromVehicleId={} length={}", groupId, sender.vehicleId, text.length)
        return event
    }

    // ---------------------------------------------------------------- identity 연동

    /**
     * 페어링 해제·소유권 이전된 차량을 활성 그룹에서 뺀다 ([VehicleLifecycleListener]). 방장이면 승계, 마지막이면 종료.
     * identity 의 트랜잭션 안에서 불리므로 별도 트랜잭션을 열지 않는다(REQUIRED 전파). 그룹이 없으면 null.
     *
     * @param previousNickname 해제·이전 **전** 별명(C104) — 이전이면 차량 행이 이미 새 소유자의 별명이라 옛 그룹의 `MEMBER_LEFT` 문구·payload 에 이 값을 쓴다
     */
    @Transactional
    fun evictVehicle(vehicleId: String, reason: String, previousNickname: String? = null): String? {
        members.lockVehicle(vehicleId)
        val membership = members.findActiveMembershipOf(vehicleId) ?: return null
        val group = groups.findByIdForUpdate(membership.groupId) ?: return null
        if (!group.active) return null
        val member = members.findActive(group.groupId, vehicleId) ?: return null
        val info = identity.vehicle(vehicleId)
        val vehicle = VehicleInfo(
            vehicleId = vehicleId,
            ownerId = info?.ownerId ?: membership.joinedBy.orEmpty(),
            nickname = previousNickname ?: info?.nickname ?: vehicleId,
            brand = info?.brand,
            model = info?.model,
            isPrimary = false,
            active = false,
        )
        removeMember(group, member, vehicle, clock.instant(), reason)
        return group.groupId
    }

    // ---------------------------------------------------------------- 내부

    private fun validDestination(place: Place): Place {
        // 목적지 이름은 음성 문구·화면에 들어간다 — 제어·서식 문자를 지운 뒤 길이를 본다(C105)
        val name = TextSanitizer.normalize(place.name)
        if (name.isEmpty() || name.length > MAX_PLACE_NAME_LENGTH) throw GroupErrors.validation("destination.name: 1~${MAX_PLACE_NAME_LENGTH}자여야 해요")
        if (place.lat.isNaN() || place.lat !in -90.0..90.0) throw GroupErrors.validation("destination.lat: -90~90 사이여야 해요")
        if (place.lon.isNaN() || place.lon !in -180.0..180.0) throw GroupErrors.validation("destination.lon: -180~180 사이여야 해요")
        val poiId = TextSanitizer.normalize(place.poiId).takeIf { it.isNotEmpty() }
        if (poiId != null && poiId.length > MAX_POI_ID_LENGTH) throw GroupErrors.validation("destination.poiId: 너무 길어요")
        val address = TextSanitizer.normalize(place.address).takeIf { it.isNotEmpty() }
        if (address != null && address.length > MAX_ADDRESS_LENGTH) throw GroupErrors.validation("destination.address: 너무 길어요")
        return Place(lat = place.lat, lon = place.lon, name = name, poiId = poiId, address = address)
    }

    private fun normalizeMessage(raw: String): String {
        val text = TextSanitizer.normalize(raw)
        if (text.isEmpty()) throw GroupErrors.invalidMessage("text: 비어 있어요")
        if (text.length > MAX_MESSAGE_LENGTH) throw GroupErrors.invalidMessage("text: ${MAX_MESSAGE_LENGTH}자까지 보낼 수 있어요")
        return text
    }

    /** 같은 그룹의 마지막 동승자 메시지가 간격 안이면 429 (C106). 그룹 행을 잠근 뒤 부른다 */
    private fun requireMessageInterval(groupId: String) {
        val intervalMs = props.rateLimit.passengerMessageIntervalMs
        if (intervalMs <= 0) return
        val last = eventReader.lastOccurredAt(groupId, GroupEventType.PASSENGER_MESSAGE) ?: return
        val sinceMs = Duration.between(last, clock.instant()).toMillis()
        if (sinceMs in 0 until intervalMs) {
            val retryAfterS = ((intervalMs - sinceMs + 999) / 1000).coerceAtLeast(1)
            log.info("passenger message rate-limited groupId={} sinceLastMs={}", groupId, sinceMs)
            throw RateLimitedException(retryAfterS, "메시지는 그룹에 ${(intervalMs + 999) / 1000}초에 한 번 보낼 수 있어요. 잠시 뒤 다시 보내 주세요.")
        }
    }

    /** cmd `GROUP_JOINED` 본문 = `GET /v1/vehicles/me/group` 응답 — 그룹의 **최신** 목적지(C032) */
    private fun joinedCmdOf(group: GroupRecord) = GroupJoined(
        groupId = group.groupId,
        name = group.name,
        leaderVehicleId = group.leaderVehicleId,
        destination = checkNotNull(group.destination) { "그룹에 목적지가 없어요" },
        topics = topicsOf(group.groupId),
    )

    /**
     * 차량 토큰의 차량이 아직 연결(페어링)된 상태이고, 토큰의 `owner` 클레임이 현재 소유자와 같은지 — 아니면 403.
     * (다른 계정이 같은 차를 다시 페어링하면 이전 소유자 때 발급된 24h 토큰은 서명은 유효해도 여기서 막힌다)
     */
    private fun requireActiveVehicle(caller: Caller.Vehicle): VehicleInfo {
        val vehicle = identity.vehicle(caller.vehicleId) ?: throw GroupErrors.vehicleRevoked()
        if (!vehicle.active) throw GroupErrors.vehicleRevoked()
        if (caller.ownerId != null && caller.ownerId != vehicle.ownerId) throw GroupErrors.vehicleRevoked()
        return vehicle
    }

    /** 합류할 차: `vehicleId` 가 있으면 내 활성 차량이어야 하고, 없으면 대표 차량(없으면 첫 활성 차량) */
    private fun resolveJoinVehicle(userId: String, requested: String?): VehicleInfo {
        val mine = identity.activeVehiclesOf(userId)
        val id = requested?.trim()?.takeIf { it.isNotEmpty() }
        if (id != null) return mine.firstOrNull { it.vehicleId == id } ?: throw GroupErrors.notMyVehicle()
        return mine.firstOrNull { it.isPrimary } ?: mine.firstOrNull() ?: throw GroupErrors.noPairedVehicle()
    }

    /** 그룹 행을 ID 순으로 잠근다 (교착 방지). 없는 그룹은 결과에서 빠진다 */
    private fun lockGroups(groupIds: List<String>): Map<String, GroupRecord> =
        groupIds.distinct().sorted().mapNotNull { groups.findByIdForUpdate(it) }.associateBy { it.groupId }

    private fun lockActiveGroup(groupId: String): GroupRecord {
        val group = groups.findByIdForUpdate(groupId) ?: throw GroupErrors.groupNotFound()
        if (!group.active) throw GroupErrors.groupEnded()
        return group
    }

    /** 방장 확인: 차량 토큰이면 방장 차량 자신, 사용자 토큰이면 방장 차량의 현재 소유자 */
    private fun requireLeader(caller: Caller, group: GroupRecord) {
        when (caller) {
            is Caller.Vehicle -> {
                requireActiveVehicle(caller)
                if (caller.vehicleId != group.leaderVehicleId) throw GroupErrors.leaderOnly()
            }
            is Caller.User -> {
                val leader = identity.vehicle(group.leaderVehicleId)
                if (leader == null || !leader.active || leader.ownerId != caller.userId) throw GroupErrors.leaderOnly()
            }
        }
    }

    /** 조회 권한: 현재 멤버 차량 자신, 또는 현재 멤버 차량의 소유자. 아니면 404 (존재 숨김) */
    private fun requireViewer(caller: Caller, current: List<MemberRecord>) {
        val allowed = when (caller) {
            is Caller.Vehicle -> current.any { it.vehicleId == caller.vehicleId }
            is Caller.User -> identity.vehicles(current.map { it.vehicleId }).values.any { it.ownerId == caller.userId && it.active }
        }
        if (!allowed) throw GroupErrors.groupNotFound()
    }

    private fun leavePreviousGroupIfAny(vehicle: VehicleInfo, now: Instant, reason: String) {
        val previous = members.findActiveMembershipOf(vehicle.vehicleId) ?: return
        val group = groups.findByIdForUpdate(previous.groupId) ?: return
        if (!group.active) return
        removeMember(group, previous, vehicle, now, reason)
    }

    /**
     * 멤버 한 대를 그룹에서 뺀다. 방장이었으면 가장 먼저 합류한 남은 멤버가 승계하고, 마지막 멤버였으면 그룹을 종료한다.
     * 그룹 행은 호출자가 이미 잠갔다.
     */
    private fun removeMember(group: GroupRecord, member: MemberRecord, vehicle: VehicleInfo, now: Instant, reason: String) {
        if (members.leave(group.groupId, member.vehicleId, now) == 0) return
        val remaining = members.listActive(group.groupId)
        afterCommit { cache.left(group.groupId, member.vehicleId) }
        if (remaining.isEmpty()) {
            recordLeft(group, vehicle, reason, newLeader = null)
            log.info("member left groupId={} vehicleId={} reason={} remaining=0 → group ends", group.groupId, member.vehicleId, reason)
            endGroup(group, SpeechComposer.REASON_ALL_LEFT, now, endedMembers = emptyList())
            return
        }
        // 방장이었으면 가장 먼저 합류한 남은 멤버가 승계한다. 이벤트 순서: MEMBER_LEFT → LEADER_CHANGED
        val successor = if (member.role == MemberRole.LEADER) remaining.first() else null
        val newLeader = successor?.let { identity.vehicle(it.vehicleId) }
        recordLeft(group, vehicle, reason, newLeader)
        if (successor != null) changeLeader(group, from = member.vehicleId, to = successor.vehicleId, speechText = false)
        log.info("member left groupId={} vehicleId={} reason={} remaining={} newLeader={}", group.groupId, member.vehicleId, reason, remaining.size, successor?.vehicleId)
    }

    private fun recordLeft(group: GroupRecord, vehicle: VehicleInfo, reason: String, newLeader: VehicleInfo?) {
        events.record(
            groupId = group.groupId,
            type = GroupEventType.MEMBER_LEFT,
            sourceVehicleId = vehicle.vehicleId,
            severity = Severity.INFO,
            speech = speech.memberLeft(vehicle.nickname, newLeader?.nickname),
            payload = buildJsonObject {
                put("vehicleId", vehicle.vehicleId)
                put("nickname", vehicle.nickname)
                put("reason", reason)
                if (newLeader != null) put("newLeaderVehicleId", newLeader.vehicleId)
            },
        )
    }

    /** 방장 교체. [speechText] 가 true 면 `LEADER_CHANGED` 에 음성 문구를 넣는다(이탈 승계는 MEMBER_LEFT 문구에 이미 포함) */
    private fun changeLeader(group: GroupRecord, from: String, to: String, speechText: Boolean) {
        members.setRole(group.groupId, from, MemberRole.MEMBER)
        members.setRole(group.groupId, to, MemberRole.LEADER)
        groups.setLeader(group.groupId, to)
        val nickname = identity.vehicle(to)?.nickname
        events.record(
            groupId = group.groupId,
            type = GroupEventType.LEADER_CHANGED,
            sourceVehicleId = to,
            severity = Severity.INFO,
            speech = if (speechText && nickname != null) speech.leaderChanged(nickname) else null,
            payload = buildJsonObject {
                put("leaderVehicleId", to)
                put("previousLeaderVehicleId", from)
            },
        )
    }

    /** 그룹 종료: 상태 ENDED, 초대 만료, `GROUP_ENDED` 이벤트, 커밋 뒤 Redis 삭제 + 멤버 차량에 CMD */
    private fun endGroup(group: GroupRecord, reason: String, now: Instant, endedMembers: List<MemberRecord>? = null) {
        if (groups.end(group.groupId, now) == 0) return
        val memberIds = (endedMembers ?: members.listActive(group.groupId)).map { it.vehicleId }
        invites.expireAllForGroup(group.groupId, now)
        val cmd = GroupEnded(groupId = group.groupId, reason = reason)
        events.record(
            groupId = group.groupId,
            type = GroupEventType.GROUP_ENDED,
            sourceVehicleId = null,
            severity = Severity.INFO,
            speech = if (memberIds.isEmpty()) null else speech.groupEnded(reason),
            payload = DhJson.payloadOf(cmd),
        )
        afterCommit {
            cache.ended(group.groupId, memberIds)
            memberIds.forEach { commands.groupEnded(it, cmd) }
        }
        log.info("group ended groupId={} reason={} members={}", group.groupId, reason, memberIds.size)
    }

    private fun issueInvite(group: GroupRecord, now: Instant): InviteDto {
        val token = InviteTokens.generate()
        val expiresAt = now.plus(INVITE_TTL)
        invites.insert(InviteTokens.hash(token), group.groupId, expiresAt, (INVITE_USES_PER_SEAT * (group.maxMembers - 1)).coerceAtLeast(1))
        return InviteDto(token = token, url = ApiPaths.joinUrl(props.publicBaseUrl, token), expiresAt = expiresAt.toEpochMilli())
    }

    private fun detailOf(group: GroupRecord, current: List<MemberRecord>) = GroupDetailResponse(
        groupId = group.groupId,
        name = group.name,
        leaderVehicleId = group.leaderVehicleId,
        status = group.status.name,
        destination = group.destination,
        members = memberDtos(current),
        snapshot = if (group.active) presence.snapshotOf(group.groupId) else null,   // presence(T24)
        recentEvents = eventReader.recent(group.groupId, RECENT_EVENTS),
    )

    /** 멤버 행 + identity 의 차량 정보(별명·브랜드·모델). 차량 행이 없으면(비정상) ID 를 별명으로 */
    private fun memberDtos(current: List<MemberRecord>): List<MemberDto> {
        val info = identity.vehicles(current.map { it.vehicleId })
        return current.map { m ->
            val v = info[m.vehicleId]
            MemberDto(
                vehicleId = m.vehicleId,
                nickname = v?.nickname ?: m.vehicleId,
                color = m.color,
                role = m.role.name,
                joinedAt = m.joinedAt.toEpochMilli(),
                brand = v?.brand,
                model = v?.model,
            )
        }
    }

    private fun topicsOf(groupId: String) = GroupTopics(state = Topics.groupState(groupId), event = Topics.groupEvent(groupId))

    companion object {
        private val log = LoggerFactory.getLogger(GroupService::class.java)
        const val MIN_MEMBERS = 2
        const val MAX_MEMBERS = 10
        const val MAX_NAME_LENGTH = 30
        const val MAX_PLACE_NAME_LENGTH = 60
        const val MAX_POI_ID_LENGTH = 64
        const val MAX_ADDRESS_LENGTH = 200
        const val MAX_MESSAGE_LENGTH = 60
        const val RECENT_EVENTS = 20
        val INVITE_TTL: Duration = Duration.ofMinutes(10)

        /** 초대 사용 횟수 = 이 값 × (maxMembers - 1). 정원은 GROUP_FULL 이 지키고, 이 한도는 재사용(나가고 다시 들어오기) 여유를 둔 재생 상한 */
        const val INVITE_USES_PER_SEAT = 3

        /** `MEMBER_LEFT.payload.reason` — 값은 protocol [MemberLeftReason] (docs/03 §5.4, CLAUDE.md 규칙 7) */
        const val LEFT_REASON_LEFT = MemberLeftReason.LEFT
        const val LEFT_REASON_SWITCHED = MemberLeftReason.SWITCHED_GROUP
        const val LEFT_REASON_UNPAIRED = MemberLeftReason.UNPAIRED
        const val LEFT_REASON_TRANSFERRED = MemberLeftReason.OWNER_CHANGED
    }
}
