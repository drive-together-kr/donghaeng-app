package app.donghaeng.companion.sdk.internal

import android.util.Log
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.VoiceMessagePayload
import app.donghaeng.protocol.rest.ApiPaths
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException

/*
 * F16 무전 수신 (docs/03 §2.6·§5.4, docs/07 §3.1).
 *
 * 공개 API 는 늘리지 않는다(docs/07 §3 — 6개). 무전은 기존 [app.donghaeng.companion.sdk.DongHaeng.observeGroup] 의
 * `GroupSnapshot.events`(protocol [GroupEvent]) 로 그대로 나가고, 모듈은 `VOICE_MESSAGE` 를 알아보면
 * 오디오를 **동행 JWT(사용자 토큰)로 미리 내려받아** 앱 전용 캐시에 두고, 그 이벤트의 payload 에 두 키를 덧붙인다:
 *
 *   localAudioStatus = READY | PENDING | EXPIRED | FAILED
 *   localAudioPath   = 내려받은 파일의 절대 경로 (READY 일 때만)
 *
 * 호스트 앱(공식 앱)은 동행 토큰을 모르므로 직접 `GET /groups/{id}/voice/{voiceId}` 를 부를 수 없다 — 그래서 모듈이 받아 둔다.
 * 원래 payload 키(voiceId·durationMs·mime·sizeBytes·expiresAtMs)는 그대로 두므로 `DhJson.payloadAs<VoiceMessagePayload>` 해석도 그대로 된다.
 */

/** `VOICE_MESSAGE` 해석·경로·payload 덧붙이기 — 상태 없는 순수 함수(단위 테스트 대상) */
internal object VoiceMessages {

    /** payload 에 모듈이 덧붙이는 키 — 호스트 앱이 읽는다(docs/07 §3.1, GroupSnapshot KDoc) */
    const val KEY_STATUS: String = "localAudioStatus"
    const val KEY_PATH: String = "localAudioPath"

    /** 서버 상한(docs/03 §2.6 — 512 KB)에 여유를 둔 내려받기 상한. 넘으면 버린다(FAILED) */
    const val MAX_AUDIO_BYTES: Long = 1024L * 1024L

    /**
     * 내려받은 오디오의 상태.
     * - READY: 파일이 있다([KEY_PATH]) — 바로 재생 가능
     * - PENDING: 아직 못 받음(이번 폴링 몫을 넘었거나 일시 오류) — 다음 폴링에 다시 시도
     * - EXPIRED: 서버 보관이 끝남(`expiresAtMs` 지남·404·그룹 종료) — 재생할 수 없다
     * - FAILED: 다시 시도하지 않음(형식 오류·크기 초과·재시도 한도·저장 실패)
     */
    enum class Status { READY, PENDING, EXPIRED, FAILED }

    /** 경로·파일 이름에 그대로 써도 되는 ID 만(서버 ID 형식 `vm_…`·`grp_…`). 경로 조작(`../`) 방지 */
    private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,128}$")

    fun isSafeId(id: String): Boolean = SAFE_ID.matches(id)

    fun isVoice(event: GroupEvent): Boolean = event.type == GroupEventType.VOICE_MESSAGE.name

    /** `VOICE_MESSAGE` 의 payload. 다른 종류·형식 오류·안전하지 않은 voiceId·길이 0 이하면 null */
    fun parse(event: GroupEvent): VoiceMessagePayload? {
        if (!isVoice(event)) return null
        val payload = runCatching { DhJson.payloadAs(event.payload, VoiceMessagePayload.serializer()) }.getOrNull() ?: return null
        if (!isSafeId(payload.voiceId) || payload.durationMs <= 0) return null
        return payload
    }

    /** `GET /v1/groups/{groupId}/voice/{voiceId}` (docs/03 §2.6 — 차량 JWT 또는 멤버 차량 소유자의 사용자 JWT) */
    fun downloadPath(groupId: String, voiceId: String): String = "${ApiPaths.group(groupId)}/voice/$voiceId"

    /** 캐시 파일 확장자 — 재생기(MediaPlayer)가 컨테이너를 고르는 데 도움이 된다 */
    fun extensionFor(mime: String): String = when (mime.substringBefore(';').trim().lowercase()) {
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
        "audio/aac", "audio/aacp" -> "aac"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        else -> "bin"
    }

    /** [event] payload 에 [KEY_STATUS](+READY 면 [KEY_PATH])를 덧붙인 사본. 원래 키는 건드리지 않는다 */
    fun annotate(event: GroupEvent, status: Status, file: File? = null): GroupEvent {
        val content = LinkedHashMap(event.payload)
        content.remove(KEY_PATH)
        content[KEY_STATUS] = JsonPrimitive(status.name)
        if (status == Status.READY && file != null) content[KEY_PATH] = JsonPrimitive(file.absolutePath)
        return event.copy(payload = JsonObject(content))
    }
}

/**
 * 무전 오디오 캐시 — [HttpDongHaengApi.observeGroup] 이 폴링마다 [resolve] 를 불러 `VOICE_MESSAGE` 이벤트에 상태를 붙인다.
 *
 * - 저장 위치: `{java.io.tmpdir}/donghaeng-voice/{groupId}/{voiceId}.{m4a|aac|wav}`. 안드로이드는 앱 시작 때 `java.io.tmpdir` 를
 *   앱 전용 캐시 디렉터리(`Context.getCacheDir()`)로 맞춰 두므로 모듈이 Context 없이도 앱 전용 저장소에 쓴다(다른 앱은 못 읽는다).
 *   프로세스가 다시 떠도 파일이 있으면 다시 받지 않는다.
 * - 만료: **서버 시계** 기준 `expiresAtMs` 가 지나면 EXPIRED + 파일 삭제. 서버 시계는 응답 `Date` 헤더로 맞춘다([observeServerDate]) —
 *   폰 시계가 틀려도 서버 보관 기간과 어긋나지 않게. 404 도 EXPIRED(서버: 만료·삭제 404).
 * - 그룹 종료: 그룹 상태가 `ENDED` 이거나 이벤트에 `GROUP_ENDED` 가 보이면 그 그룹 파일을 바로 지운다(서버가 그룹 종료 때 무전을 지우는 것과 같은 원칙).
 *   그룹을 더 볼 수 없게 되면(나간 뒤 404) 전송 계층이 [forgetGroup] 으로 지운다.
 * - 한 번 폴링에 최대 [maxDownloadsPerPoll] 개(최신부터)만 받는다 — 나머지는 PENDING 으로 다음 폴링에. 일시 오류는 [maxAttempts] 번까지.
 * - 디스크: 시작 후 첫 처리 때 하루 넘은 파일을 지우고, 전체가 [maxFiles] 개를 넘으면 오래된 것부터 지운다.
 * - 로그: voiceId·크기·길이·상태만(닉네임·토큰·내용 없음, CLAUDE.md 규칙 8).
 *
 * @param rootDir 캐시 루트(테스트에서 임시 폴더로 바꾼다)
 * @param clock 이 기기 시각(ms)
 */
internal class VoiceInbox(
    private val rootDir: File = defaultRoot(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxDownloadsPerPoll: Int = 3,
    private val maxAttempts: Int = 3,
    private val maxFiles: Int = 30,
) {

    /** 오디오 한 건 내려받기 결과 — 전송 계층([HttpDongHaengApi])이 만든다 */
    sealed interface Fetch {
        class Ok(val bytes: ByteArray) : Fetch

        /** 404·410 — 서버에서 만료·삭제됨 */
        object Gone : Fetch

        /** @param retryable true 면 다음 폴링에 다시 시도([maxAttempts] 까지) */
        class Failed(val reason: String, val retryable: Boolean) : Fetch
    }

    private class Entry(
        var status: VoiceMessages.Status,
        var file: File?,
        val expiresAtMs: Long,
        var attempts: Int = 0,
    )

    private val mutex = Mutex()
    private val entries = HashMap<String, Entry>()

    /** 서버 시각 − 이 기기 시각(ms). 응답 Date 헤더가 없으면 0 */
    @Volatile
    private var serverOffsetMs: Long = 0L

    @Volatile
    private var swept = false

    /** 서버 응답 `Date` 헤더(epoch ms, 초 단위 정밀도)로 서버 시계 차이를 맞춘다. null 이면 그대로 */
    fun observeServerDate(serverDateMs: Long?) {
        if (serverDateMs == null) return
        serverOffsetMs = serverDateMs - clock()
    }

    /** 서버 시계 기준 지금 */
    fun serverNow(): Long = clock() + serverOffsetMs

    /**
     * [events](오래된 것부터) 중 `VOICE_MESSAGE` 에 상태를 붙여 돌려준다. 다른 이벤트·순서는 그대로다.
     * 무전도 그룹 종료도 없으면 아무 일도 하지 않는다(파일 시스템을 건드리지 않음).
     *
     * @param groupEnded 서버 그룹 상태가 `ENDED`(이벤트 `GROUP_ENDED` 가 최근 20건 밖으로 밀려나도 종료를 안다)
     * @param fetch [VoiceMessages.downloadPath] 경로를 받아 오디오를 내려받는다(인증·401 재교환은 전송 계층 몫)
     */
    suspend fun resolve(
        groupId: String,
        events: List<GroupEvent>,
        groupEnded: Boolean = false,
        fetch: suspend (path: String) -> Fetch,
    ): List<GroupEvent> {
        val ended = groupEnded || events.any { it.type == GroupEventType.GROUP_ENDED.name }
        if (!ended && events.none(VoiceMessages::isVoice)) return events
        return mutex.withLock {
            sweepOnce()
            if (!VoiceMessages.isSafeId(groupId)) {
                Log.w(TAG, "voice: unsafe groupId — skip download")
                return@withLock events.map { if (VoiceMessages.isVoice(it)) VoiceMessages.annotate(it, VoiceMessages.Status.FAILED) else it }
            }
            if (ended) purgeGroup(groupId)
            val now = serverNow()
            var budget = maxDownloadsPerPoll
            val out = arrayOfNulls<GroupEvent>(events.size)
            // 최신 무전부터 받는다(events 는 오래된 것부터)
            for (i in events.indices.reversed()) {
                val event = events[i]
                if (!VoiceMessages.isVoice(event)) {
                    out[i] = event
                    continue
                }
                val payload = VoiceMessages.parse(event)
                if (payload == null) {
                    Log.w(TAG, "voice: bad payload eventId=${event.eventId}")
                    out[i] = VoiceMessages.annotate(event, VoiceMessages.Status.FAILED)
                    continue
                }
                val key = "$groupId/${payload.voiceId}"
                val entry = entries.getOrPut(key) { Entry(VoiceMessages.Status.PENDING, null, payload.expiresAtMs) }
                when {
                    ended -> expire(key, entry, "group_ended")
                    now >= payload.expiresAtMs -> expire(key, entry, "clock")
                    entry.status == VoiceMessages.Status.READY && entry.file?.isFile == true -> Unit
                    entry.status == VoiceMessages.Status.EXPIRED || entry.status == VoiceMessages.Status.FAILED -> Unit
                    else -> {
                        val file = fileFor(groupId, payload)
                        if (file.isFile && file.length() > 0) {
                            // 이전 프로세스가 받아 둔 파일
                            entry.status = VoiceMessages.Status.READY
                            entry.file = file
                        } else if (budget > 0) {
                            budget--
                            download(key, entry, groupId, payload, file, fetch)
                        } else {
                            entry.status = VoiceMessages.Status.PENDING
                        }
                    }
                }
                out[i] = VoiceMessages.annotate(event, entry.status, entry.file)
            }
            pruneEntries(now)
            out.map { requireNotNull(it) }
        }
    }

    private suspend fun download(
        key: String,
        entry: Entry,
        groupId: String,
        payload: VoiceMessagePayload,
        file: File,
        fetch: suspend (path: String) -> Fetch,
    ) {
        when (val result = fetch(VoiceMessages.downloadPath(groupId, payload.voiceId))) {
            is Fetch.Ok -> {
                if (result.bytes.isEmpty() || result.bytes.size > VoiceMessages.MAX_AUDIO_BYTES) {
                    entry.status = VoiceMessages.Status.FAILED
                    Log.w(TAG, "voice: rejected voiceId=${payload.voiceId} bytes=${result.bytes.size}")
                    return
                }
                try {
                    writeAtomically(file, result.bytes)
                    entry.status = VoiceMessages.Status.READY
                    entry.file = file
                    if (payload.sizeBytes > 0 && payload.sizeBytes != result.bytes.size) {
                        Log.w(TAG, "voice: size mismatch voiceId=${payload.voiceId} payload=${payload.sizeBytes} got=${result.bytes.size}")
                    }
                    Log.i(TAG, "voice: cached voiceId=${payload.voiceId} bytes=${result.bytes.size} durMs=${payload.durationMs} mime=${payload.mime}")
                    trimToMaxFiles()
                } catch (e: IOException) {
                    retryOrFail(entry, payload.voiceId, "write:${e.javaClass.simpleName}")
                } catch (e: SecurityException) {
                    entry.status = VoiceMessages.Status.FAILED
                    Log.w(TAG, "voice: cache not writable voiceId=${payload.voiceId}")
                }
            }
            Fetch.Gone -> expire(key, entry, "404")
            is Fetch.Failed -> if (result.retryable) {
                retryOrFail(entry, payload.voiceId, result.reason)
            } else {
                entry.status = VoiceMessages.Status.FAILED
                Log.w(TAG, "voice: download failed voiceId=${payload.voiceId} reason=${result.reason} final=true")
            }
        }
    }

    private fun retryOrFail(entry: Entry, voiceId: String, reason: String) {
        entry.attempts++
        entry.status = if (entry.attempts >= maxAttempts) VoiceMessages.Status.FAILED else VoiceMessages.Status.PENDING
        Log.w(TAG, "voice: download failed voiceId=$voiceId reason=$reason attempt=${entry.attempts}/$maxAttempts status=${entry.status}")
    }

    private fun expire(key: String, entry: Entry, basis: String) {
        if (entry.status != VoiceMessages.Status.EXPIRED) {
            Log.i(TAG, "voice: expired ${key.substringAfter('/')} basis=$basis")
        }
        entry.file?.delete()
        entry.file = null
        entry.status = VoiceMessages.Status.EXPIRED
    }

    /** 그룹을 더 볼 수 없음(나간 그룹 404 등) — 그 그룹의 캐시를 지운다 */
    suspend fun forgetGroup(groupId: String) {
        if (!VoiceMessages.isSafeId(groupId)) return
        mutex.withLock {
            if (File(rootDir, groupId).exists() || entries.keys.any { it.startsWith("$groupId/") }) purgeGroup(groupId)
        }
    }

    /** 그룹 종료 — 그 그룹의 캐시 파일을 모두 지운다 */
    private fun purgeGroup(groupId: String) {
        val dir = File(rootDir, groupId)
        val files = dir.listFiles().orEmpty()
        if (files.isNotEmpty()) Log.i(TAG, "voice: group ended or gone — delete files=${files.size}")
        files.forEach { it.delete() }
        dir.delete()
        entries.filterKeys { it.startsWith("$groupId/") }.forEach { (_, e) ->
            e.file = null
            e.status = VoiceMessages.Status.EXPIRED
        }
    }

    /** 만료가 지난 항목은 상태 표에서 뺀다(파일은 [expire] 가 지움) */
    private fun pruneEntries(now: Long) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            if (now >= e.expiresAtMs + PRUNE_GRACE_MS) {
                e.file?.delete()
                it.remove()
            }
        }
    }

    private fun fileFor(groupId: String, payload: VoiceMessagePayload): File =
        File(File(rootDir, groupId), "${payload.voiceId}.${VoiceMessages.extensionFor(payload.mime)}")

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val dir = file.parentFile ?: throw IOException("no parent")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("mkdirs")
        val part = File(dir, file.name + ".part")
        part.writeBytes(bytes)
        if (!part.renameTo(file)) {
            file.delete()
            if (!part.renameTo(file)) {
                part.delete()
                throw IOException("rename")
            }
        }
    }

    /** 첫 처리 때 한 번 — 하루 넘은 파일·남은 `.part`·빈 폴더 정리 */
    private fun sweepOnce() {
        if (swept) return
        swept = true
        val cutoff = clock() - STALE_FILE_MS
        var removed = 0
        rootDir.listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
            dir.listFiles().orEmpty().forEach { f ->
                if (f.name.endsWith(".part") || f.lastModified() < cutoff) {
                    if (f.delete()) removed++
                }
            }
            if (dir.listFiles().isNullOrEmpty()) dir.delete()
        }
        if (removed > 0) Log.i(TAG, "voice: swept stale files=$removed")
    }

    private fun trimToMaxFiles() {
        val all = rootDir.listFiles().orEmpty().filter { it.isDirectory }.flatMap { it.listFiles().orEmpty().toList() }.filter { it.isFile }
        if (all.size <= maxFiles) return
        all.sortedBy { it.lastModified() }.take(all.size - maxFiles).forEach { f ->
            f.delete()
            entries.values.filter { it.file == f }.forEach { e ->
                e.file = null
                e.status = VoiceMessages.Status.PENDING
            }
        }
    }

    internal companion object {
        private const val TAG = "DongHaeng"
        private const val DIR_NAME = "donghaeng-voice"

        /** 서버 보관(24시간) + 여유 — 이보다 오래된 캐시 파일은 시작 때 지운다 */
        private const val STALE_FILE_MS = 25L * 60 * 60 * 1000

        /** 만료 뒤 상태 표에서 빼기까지의 여유 — 같은 이벤트가 다시 와도 EXPIRED 로 판정되게 */
        private const val PRUNE_GRACE_MS = 60L * 60 * 1000

        fun defaultRoot(): File = File(System.getProperty("java.io.tmpdir") ?: ".", DIR_NAME)
    }
}
