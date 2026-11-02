package app.donghaeng.voice

import app.donghaeng.common.id.Ids
import app.donghaeng.common.idempotency.Idempotency
import app.donghaeng.common.tx.afterCommit
import app.donghaeng.event.GroupEventSink
import app.donghaeng.event.SpeechComposer
import app.donghaeng.group.Caller
import app.donghaeng.group.GroupAccessApi
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.Severity
import app.donghaeng.protocol.VoiceMessagePayload
import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.VoiceUploadResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration

/** 업로드된 오디오 파트 (컨트롤러가 `MultipartFile` 을 감싼다). [bytes] 는 검사가 끝난 뒤에만 읽는다 */
interface VoiceAudioPart {
    /** 파트의 `Content-Type` (없으면 null) */
    val contentType: String?
    val size: Long
    fun bytes(): ByteArray
}

/**
 * F16 무전(녹음형 PTT) — docs/03 §2.6.
 *
 * - 업로드 `POST /groups/{id}/voice`(차량 JWT): 검사 순서 = (Spring multipart 상한 413) → 해제 차량 403 → 그룹 없음·멤버 아님 404 → 종료 409
 *   → audio 없음 400 → 크기 413 → mime 415 → durationMs 400 → 빈 파일 400 → 같은 차 3초 429. 통과하면 한 트랜잭션에서
 *   `VOICE_MESSAGE` 그룹 이벤트(speech `{닉네임} 무전`, audience 없음 = 그룹 전체)를 기록하고 무전을 저장한다 — 커밋 뒤 기존 경로
 *   (`GroupEventRecorded` → `GroupEventMqttFanout`)로 그룹 event 토픽에 팬아웃된다.
 * - 그룹 행은 `GroupAccessApi.lockActiveMemberVehicle` 이 `FOR SHARE` 로 잡아 종료와 직렬화한다 → 종료가 먼저 커밋됐으면 409,
 *   업로드가 먼저면 종료가 기다렸다가 `VoiceRetention` 이 지운다. 끝난 그룹에 무전·이벤트가 남지 않는다.
 * - 빈도 제한은 DB 기준(같은 차 advisory lock + 마지막 `created_at`) — 저장된(성공한) 업로드만 센다. 실패·롤백은 세지 않는다.
 *   보관 상한(C113): 같은 차 1시간 60건, 그룹의 만료 전 무전 합계 50 MB — 넘으면 같은 429 `VOICE_RATE_LIMITED`(문구로 구분).
 * - 재시도 안전(계약 3): `Idempotency-Key` 가 있으면 같은 (차량, 경로, 키)의 첫 201 응답을 24시간 기억해 다시 오면 저장·팬아웃 없이 그대로 돌려준다
 *   (보내지 못한 무전을 다시 보낼 때 같은 녹음이 두 번 재생되지 않게). 다시 받는 응답은 그룹 종료·만료 뒤에도 같다.
 * - 다운로드 `GET /groups/{id}/voice/{voiceId}`(멤버 차량 또는 멤버 차량 소유자): 진행 중 그룹 + 만료 전만. 끝난 그룹의 무전은
 *   지워진 것으로 본다(종료 시 삭제가 실패했어도 내주지 않는다).
 * - 로그에는 ID·크기·길이·mime 만 — 닉네임·오디오 내용 없음 (CLAUDE.md 규칙 8).
 */
@Service
class VoiceService(
    private val access: GroupAccessApi,
    private val store: VoiceStore,
    private val events: GroupEventSink,
    private val speech: SpeechComposer,
    private val idempotency: Idempotency,
    private val clock: Clock,
) {

    @Transactional
    fun upload(caller: Caller.Vehicle, groupId: String, audio: VoiceAudioPart?, durationMs: String?, idempotencyKey: String? = null): VoiceUploadResponse {
        val key = Idempotency.parseKey(idempotencyKey)
        return idempotency.run(caller.vehicleId, ApiPaths.groupVoice(groupId), key, 201, VoiceUploadResponse.serializer()) {
            storeUpload(caller, groupId, audio, durationMs)
        }
    }

    private fun storeUpload(caller: Caller.Vehicle, groupId: String, audio: VoiceAudioPart?, durationMs: String?): VoiceUploadResponse {
        val sender = access.lockActiveMemberVehicle(caller, groupId)
        val part = audio ?: throw VoiceErrors.invalid("audio 파일이 없어요")
        if (part.size > VoiceRules.MAX_BYTES) throw VoiceErrors.tooLarge()
        val mime = VoiceRules.normalizeMime(part.contentType) ?: throw VoiceErrors.unsupportedType()
        val duration = VoiceRules.parseDurationMs(durationMs)
            ?: throw VoiceErrors.invalid("durationMs: ${VoiceRules.MIN_DURATION_MS}~${VoiceRules.MAX_DURATION_MS} 정수여야 해요")
        val bytes = part.bytes()
        if (bytes.isEmpty()) throw VoiceErrors.invalid("audio 파일이 비었어요")
        if (bytes.size > VoiceRules.MAX_BYTES) throw VoiceErrors.tooLarge()

        store.lockSender(sender.vehicleId)
        val now = clock.instant()
        val last = store.lastCreatedAt(sender.vehicleId)
        if (last != null && Duration.between(last, now) < VoiceRules.RATE_WINDOW) {
            log.info("voice rejected rate-limited groupId={} vehicleId={} sinceLastMs={}", groupId, sender.vehicleId, Duration.between(last, now).toMillis())
            throw VoiceErrors.rateLimited()
        }
        // 보관 상한(C113) — 같은 차 잠금 안이라 차 단위 건수는 정확하다. 그룹 합계는 다른 차의 동시 업로드만큼 조금 넘을 수 있다
        val recent = store.countSince(sender.vehicleId, now.minus(VoiceRules.QUOTA_WINDOW))
        if (recent >= VoiceRules.MAX_UPLOADS_PER_WINDOW) {
            log.warn("voice rejected quota (vehicle) groupId={} vehicleId={} lastHour={}", groupId, sender.vehicleId, recent)
            throw VoiceErrors.quotaExceeded("한 시간에 ${VoiceRules.MAX_UPLOADS_PER_WINDOW}건까지")
        }
        val groupBytes = store.activeBytesOfGroup(groupId, now)
        if (groupBytes + bytes.size > VoiceRules.MAX_GROUP_ACTIVE_BYTES) {
            log.warn("voice rejected quota (group) groupId={} vehicleId={} activeBytes={}", groupId, sender.vehicleId, groupBytes)
            throw VoiceErrors.quotaExceeded("그룹 보관 용량 초과")
        }

        val voiceId = Ids.voice()
        val expiresAt = now.plus(VoiceRules.RETENTION)
        val payload = VoiceMessagePayload(
            voiceId = voiceId,
            durationMs = duration,
            mime = mime,
            sizeBytes = bytes.size,
            expiresAtMs = expiresAt.toEpochMilli(),
        )
        val event = events.record(
            groupId = groupId,
            type = GroupEventType.VOICE_MESSAGE,
            sourceVehicleId = sender.vehicleId,
            severity = Severity.INFO,
            speech = speech.voiceMessage(sender.nickname),
            payload = DhJson.payloadOf(payload),
            audience = null,
        )
        store.insert(
            VoiceMeta(
                voiceId = voiceId,
                groupId = groupId,
                senderVehicleId = sender.vehicleId,
                eventId = event.eventId,
                mime = mime,
                durationMs = duration,
                sizeBytes = bytes.size,
                createdAt = now,
                expiresAt = expiresAt,
            ),
            bytes,
        )
        afterCommit {
            log.info(
                "voice stored groupId={} vehicleId={} voiceId={} eventId={} bytes={} durationMs={} mime={}",
                groupId, sender.vehicleId, voiceId, event.eventId, bytes.size, duration, mime,
            )
        }
        return VoiceUploadResponse(voiceId = voiceId, eventId = event.eventId, durationMs = duration, expiresAtMs = payload.expiresAtMs)
    }

    @Transactional(readOnly = true)
    fun download(caller: Caller, groupId: String, voiceId: String): StoredVoice {
        val active = access.requireViewer(caller, groupId)
        if (!active || !VoiceRules.isVoiceId(voiceId)) throw VoiceErrors.notFound()
        return store.find(groupId, voiceId, clock.instant()) ?: throw VoiceErrors.notFound()
    }

    companion object {
        private val log = LoggerFactory.getLogger(VoiceService::class.java)
    }
}
