package app.donghaeng.companion.demo.donghaeng

import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.MemberState
import app.donghaeng.protocol.VoiceMessagePayload
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * F16 무전 — 호스트 쪽 목록 모델. 동행 모듈(companion/sdk)이 `observeGroup` 의 `VOICE_MESSAGE` 이벤트 payload 에
 * 오디오 상태·파일 경로를 덧붙여 주므로(GroupSnapshot KDoc, docs/07 §3.1) 호스트는 그것만 읽어 재생한다.
 * 공식 앱 팀은 이 파일과 [VoicePlayer]·VoiceMessagesCard 를 가져가면 된다.
 */

/** 모듈이 덧붙이는 오디오 상태 (sdk `localAudioStatus`) */
enum class VoiceAudioStatus {
    /** 파일이 있다 — 재생 가능 */
    READY,

    /** 아직 받는 중(다음 스냅샷에서 바뀐다) */
    PENDING,

    /** 서버 보관 끝(24시간)·그룹 종료 */
    EXPIRED,

    /** 받을 수 없음 */
    FAILED,
}

/**
 * 무전 목록(동행 메인 C·알림 F) 한 줄.
 *
 * @param sentAtMs 보낸 시각(서버 시계) — 이벤트에 시각이 없으므로 `expiresAtMs − 24시간`(docs/03 §2.6 보관 규칙)으로 계산한다
 * @param mine 내 차(이 폰이 합류시킨 차)가 보낸 무전
 */
data class VoiceItem(
    val eventId: String,
    val voiceId: String,
    val senderVehicleId: String?,
    val senderNickname: String,
    val senderColor: String,
    val durationMs: Int,
    val status: VoiceAudioStatus,
    val localPath: String?,
    val expiresAtMs: Long,
    val sentAtMs: Long,
    val mine: Boolean,
)

object VoiceEvents {
    /** companion/sdk 가 `VOICE_MESSAGE` payload 에 덧붙이는 키 (sdk internal VoiceMessages 와 같은 값) */
    const val KEY_STATUS = "localAudioStatus"
    const val KEY_PATH = "localAudioPath"

    /** docs/03 §2.6 — 업로드 + 24시간 보관 */
    const val RETENTION_MS = 24L * 60 * 60 * 1000

    /** 목록에 남길 최대 개수(최신부터) */
    const val MAX_ITEMS = 20

    /**
     * 서버 `recentEvents` 밖으로 밀려난 무전만 폰 시계로 보관 끝을 판정할 때 두는 여유 — 폰 시계와 서버 시계가 조금 어긋나도 일찍 끝나 보이지 않게.
     * 실제 파일은 모듈이 서버 시계로 만료 때 지운다(그 사이 [재생] 을 누르면 VoicePlayer 가 "보관 기간이 지났거나…" 로 안내).
     */
    const val CARRIED_EXPIRY_GRACE_MS = 60L * 60 * 1000

    /**
     * `VOICE_MESSAGE` → 목록 항목. 다른 종류·형식 오류면 null. 보낸 차 이름·색은 스냅샷 멤버 → 합류 때 멤버 → 음성 문구 순으로 찾는다.
     * 상태는 모듈이 서버 시계로 정한 `localAudioStatus` 그대로다(폰 시계로 다시 판정하지 않는다).
     */
    fun toItem(event: GroupEvent, members: List<MemberState>, group: ActiveGroup?): VoiceItem? {
        if (event.eventType != GroupEventType.VOICE_MESSAGE) return null
        val payload = runCatching { DhJson.payloadAs(event.payload, VoiceMessagePayload.serializer()) }.getOrNull() ?: return null
        val statusText = (event.payload[KEY_STATUS] as? JsonPrimitive)?.contentOrNull
        // 모듈이 상태를 붙이지 않았으면(옛 모듈) 받는 중으로 본다 — 경로가 없으니 재생 버튼은 꺼진다
        val status = VoiceAudioStatus.entries.firstOrNull { it.name == statusText } ?: VoiceAudioStatus.PENDING
        val path = (event.payload[KEY_PATH] as? JsonPrimitive)?.contentOrNull?.takeIf { status == VoiceAudioStatus.READY && it.isNotBlank() }
        val source = event.sourceVehicleId
        val live = members.firstOrNull { it.vehicleId == source }
        val joined = group?.members?.firstOrNull { it.vehicleId == source }
        val nickname = live?.nickname ?: joined?.nickname ?: event.speech?.removeSuffix(" 무전")?.takeIf { it.isNotBlank() } ?: "그룹 차량"
        return VoiceItem(
            eventId = event.eventId,
            voiceId = payload.voiceId,
            senderVehicleId = source,
            senderNickname = nickname,
            senderColor = live?.color ?: joined?.color ?: "#6B7280",
            durationMs = payload.durationMs,
            status = if (status == VoiceAudioStatus.READY && path == null) VoiceAudioStatus.PENDING else status,
            localPath = path,
            expiresAtMs = payload.expiresAtMs,
            // 서버 시계 그대로(폰 시계로 자르지 않는다 — 정렬이 어긋나지 않게). "n분 전" 표시는 relativeTime 이 음수를 0 으로 본다
            sentAtMs = payload.expiresAtMs - RETENTION_MS,
            mine = source != null && source == group?.myVehicleId,
        )
    }

    /**
     * 지금까지의 목록([existing])에 새 스냅샷의 무전([incoming])을 합친다 — 같은 eventId 는 새 상태로 바꾸고, 최신(보낸 시각)부터 [MAX_ITEMS] 개.
     * - [incoming](이번 스냅샷에 있는 무전): 모듈이 **서버 시계**(응답 Date 헤더)로 정한 상태를 그대로 쓴다 — 폰 시계가 틀려도 READY 를 EXPIRED 로 덮지 않는다(docs/07 §3.1).
     * - 서버 `recentEvents`(최대 20) 밖으로 밀려난 무전: 모듈이 더는 상태를 주지 않으므로 마지막 상태를 유지하되, 그룹이 끝났거나
     *   폰 시계로 만료 시각 + [CARRIED_EXPIRY_GRACE_MS] 가 지났으면 EXPIRED 로 바꾼다.
     * - 그룹 종료면 모두 EXPIRED(모듈도 그 그룹 파일을 지운다).
     */
    fun merge(existing: List<VoiceItem>, incoming: List<VoiceItem>, groupEnded: Boolean, nowMs: Long = System.currentTimeMillis()): List<VoiceItem> {
        val fresh = incoming.mapTo(HashSet()) { it.eventId }
        val byId = LinkedHashMap<String, VoiceItem>()
        existing.forEach { byId[it.eventId] = it }
        incoming.forEach { byId[it.eventId] = it }
        return byId.values
            .map { item ->
                val gone = when {
                    groupEnded -> true
                    item.eventId in fresh -> false
                    else -> nowMs >= item.expiresAtMs + CARRIED_EXPIRY_GRACE_MS
                }
                if (gone && item.status != VoiceAudioStatus.EXPIRED) item.copy(status = VoiceAudioStatus.EXPIRED, localPath = null) else item
            }
            .sortedByDescending { it.sentAtMs }
            .take(MAX_ITEMS)
    }
}

/** 무전 길이 "0:07" */
fun durationLabel(ms: Int): String {
    val totalS = ((ms.coerceAtLeast(0) + 500) / 1000).coerceAtLeast(if (ms > 0) 1 else 0)
    return "%d:%02d".format(totalS / 60, totalS % 60)
}

/** 재생 버튼을 못 누르는 이유 — READY 면 null */
fun voiceStatusText(status: VoiceAudioStatus): String? = when (status) {
    VoiceAudioStatus.READY -> null
    VoiceAudioStatus.PENDING -> "받는 중…"
    VoiceAudioStatus.EXPIRED -> "보관 기간이 지났어요"
    VoiceAudioStatus.FAILED -> "받을 수 없어요"
}
