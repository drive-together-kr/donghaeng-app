package app.donghaeng.car.domain.ptt

import app.donghaeng.car.adapter.audio.AudioMime
import app.donghaeng.car.core.common.DhLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * '보내지 않은 무전' 저장소 (F16, 사용자 결정 2026-10-28 — 앱 재시작·프로세스 종료 뒤에도 남고, 다시 보냄 성공·지우기·그룹 종료 때 지운다).
 *
 * - 기기: [FileUnsentVoiceStore] — 앱 내부 저장소 `files/ptt_unsent/`(오디오 `<id>.<ext>` + 메타 `<id>.json`).
 * - 테스트: [InMemoryUnsentVoiceStore].
 *
 * PttController 의 액터·사이클(한 번에 하나)에서만 부른다. 구현은 그래도 스레드 안전하게 둔다(디버그 리시버가 [count] 를 읽는다).
 * 어떤 메서드도 예외를 밖으로 던지지 않는다 — 저장이 안 되면 그 프로세스 동안 메모리로만 들고 있는다(크래시 금지).
 */
interface UnsentVoiceStore {
    /** 저장된 것 전부(최신 먼저, 상한까지). 손상·짝 없는 파일·쓰다 만 임시 파일은 지우고 건너뛴다 */
    fun load(): List<UnsentVoice>

    /** 새 항목 — 오디오를 저장소로 옮기고 메타를 쓴다. 저장된 항목(파일 경로가 저장소 안)을 돌려주고, 실패하면 null(원래 파일은 그대로) */
    fun add(item: UnsentVoice): UnsentVoice?

    /** 메타만 갱신(다시 보내기 실패 사유) — 성공하면 true */
    fun update(item: UnsentVoice): Boolean

    /** 하나 지우기(오디오·메타) — 있었으면 true */
    fun remove(id: String): Boolean

    /** 전부 지우기(그룹 종료·바뀜) — 지운 항목 수 */
    fun clear(): Int

    /** 지금 저장된 항목 수(디버그 `persisted=`) */
    fun count(): Int
}

/** 메타 파일 한 개(`<id>.json`) — 그룹 id 가 들어가므로 앱 내부 저장소에만 둔다. 로그에는 남기지 않는다 */
@Serializable
internal data class UnsentMeta(
    val v: Int = 1,
    val id: String,
    val groupId: String,
    /** 같은 폴더 안 오디오 파일 이름 */
    val audio: String,
    val mime: String,
    val durationMs: Long,
    val createdAtMs: Long,
    val reason: String,
    val failure: String? = null,
)

/**
 * 파일 구현 — `dir`(기기: `context.filesDir/ptt_unsent`).
 *
 * - 쓰기는 원자적으로: 오디오를 `<id>.<ext>.tmp` 로 옮긴 뒤 rename → 메타를 `<id>.json.tmp` 에 쓴 뒤 rename. **메타가 있어야 항목이다**
 *   (오디오만 있고 메타가 없으면 쓰다 만 것 → [load] 가 지운다).
 * - [load]: 해석 안 되는 JSON·id 와 파일 이름이 다른 것·오디오가 없는 것은 메타·오디오를 지우고 건너뛴다. 메타가 가리키지 않는 파일·`.tmp` 도 지운다.
 *   [maxItems] 를 넘으면 오래된 것부터 지운다.
 */
class FileUnsentVoiceStore(
    private val dir: File,
    private val maxItems: Int = MAX_ITEMS,
) : UnsentVoiceStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Synchronized
    override fun load(): List<UnsentVoice> {
        val files = dir.listFiles()?.toList().orEmpty()
        if (files.isEmpty()) return emptyList()
        val items = ArrayList<UnsentVoice>()
        val keep = HashSet<String>()
        var corrupt = 0
        for (f in files.filter { it.isFile && it.name.endsWith(META_EXT) }) {
            val item = runCatching { parse(f) }.getOrNull()
            if (item == null) {
                corrupt++
                f.delete()
                continue
            }
            items += item
            keep += f.name
            keep += item.file.name
        }
        // 메타가 가리키지 않는 파일(쓰다 만 오디오·임시 파일·손상 메타의 오디오)
        var orphans = 0
        dir.listFiles()?.filter { it.isFile && it.name !in keep }?.forEach { if (it.delete()) orphans++ }
        val sorted = items.sortedByDescending { it.createdAtMs }
        val over = sorted.drop(maxItems)
        over.forEach { deleteFiles(it.id, it.file) }
        if (corrupt > 0 || orphans > 0 || over.isNotEmpty()) {
            DhLog.w("ptt: unsent store load corrupt=$corrupt orphans=$orphans over=${over.size} → 지움")
        }
        return sorted.take(maxItems)
    }

    private fun parse(metaFile: File): UnsentVoice? {
        val m = json.decodeFromString(UnsentMeta.serializer(), metaFile.readText(Charsets.UTF_8))
        if (m.id + META_EXT != metaFile.name || !SAFE_NAME.matches(m.id)) return null
        if (!SAFE_NAME.matches(m.audio.substringBeforeLast('.')) || m.audio.contains('/') || m.audio.contains('\\')) return null
        val audio = File(dir, m.audio)
        if (!audio.isFile) {
            return null
        }
        val reason = runCatching { UnsentReason.valueOf(m.reason) }.getOrNull() ?: return null
        if (m.durationMs <= 0 || m.groupId.isBlank()) return null
        return UnsentVoice(m.id, m.groupId, audio, m.mime, m.durationMs, m.createdAtMs, reason, m.failure)
    }

    @Synchronized
    override fun add(item: UnsentVoice): UnsentVoice? {
        if (!SAFE_NAME.matches(item.id)) {
            DhLog.w("ptt: unsent store add 거절(id 형식)")
            return null
        }
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("mkdirs")
            val target = File(dir, "${item.id}.${AudioMime.extensionOf(item.mime)}")
            if (item.file.canonicalPath != target.canonicalPath) {
                val tmp = File(dir, target.name + TMP_EXT)
                // 같은 파일 시스템이면 rename, 아니면 복사 + 지우기 → 그다음 원자적 rename
                Files.move(item.file.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
                atomicReplace(tmp, target)
            }
            val stored = item.copy(file = target)
            writeMeta(stored)
            stored
        } catch (e: Exception) {
            DhLog.w("ptt: unsent store add 실패 ${e.javaClass.simpleName} → 이번 프로세스 동안 메모리로만")
            // 오디오를 이미 옮겼다면 되돌려 호출 쪽 파일이 살아 있게
            val moved = File(dir, "${item.id}.${AudioMime.extensionOf(item.mime)}")
            if (!item.file.exists() && moved.isFile) runCatching { Files.move(moved.toPath(), item.file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            File(dir, item.id + META_EXT).delete()
            null
        }
    }

    @Synchronized
    override fun update(item: UnsentVoice): Boolean {
        if (!File(dir, item.id + META_EXT).isFile || item.file.parentFile?.canonicalPath != dir.canonicalPath) return false
        return try {
            writeMeta(item)
            true
        } catch (e: Exception) {
            DhLog.w("ptt: unsent store update 실패 ${e.javaClass.simpleName}")
            false
        }
    }

    @Synchronized
    override fun remove(id: String): Boolean {
        if (!SAFE_NAME.matches(id)) return false
        val meta = File(dir, id + META_EXT)
        val audio = runCatching { parse(meta)?.file }.getOrNull()
        val had = meta.isFile
        deleteFiles(id, audio)
        return had
    }

    @Synchronized
    override fun clear(): Int {
        val files = dir.listFiles()?.filter { it.isFile }.orEmpty()
        val n = files.count { it.name.endsWith(META_EXT) }
        files.forEach { it.delete() }
        return n
    }

    @Synchronized
    override fun count(): Int = dir.listFiles()?.count { it.isFile && it.name.endsWith(META_EXT) } ?: 0

    private fun deleteFiles(id: String, audio: File?) {
        File(dir, id + META_EXT).delete()
        audio?.delete()
        // 메타가 깨져 오디오 이름을 모를 때 — 같은 id 로 시작하는 오디오(확장자만 다름)도 지운다
        dir.listFiles()?.filter { it.isFile && it.name.substringBefore('.') == id }?.forEach { it.delete() }
    }

    private fun writeMeta(item: UnsentVoice) {
        val meta = UnsentMeta(
            id = item.id, groupId = item.groupId, audio = item.file.name, mime = item.mime, durationMs = item.durationMs,
            createdAtMs = item.createdAtMs, reason = item.reason.name, failure = item.failure,
        )
        val target = File(dir, item.id + META_EXT)
        val tmp = File(dir, target.name + TMP_EXT)
        tmp.writeText(json.encodeToString(UnsentMeta.serializer(), meta), Charsets.UTF_8)
        atomicReplace(tmp, target)
    }

    private fun atomicReplace(tmp: File, target: File) {
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /** 저장소 안전 상한 — 목록 상한([PttController.MAX_UNSENT])보다 넉넉히. 넘으면 [load] 가 오래된 것부터 지운다 */
        const val MAX_ITEMS = 10
        /** 앱 files 아래 폴더 이름 */
        const val DIR_NAME = "ptt_unsent"
        private const val META_EXT = ".json"
        private const val TMP_EXT = ".tmp"
        private val SAFE_NAME = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

/**
 * 메모리 구현(테스트) — 같은 인스턴스를 새 PttController 에 넘기면 '재시작 뒤 복원'을 흉내 낸다. 파일은 옮기지 않는다(호출 쪽 파일 그대로).
 * [failAdd] 면 add 가 실패(null)한다.
 */
class InMemoryUnsentVoiceStore : UnsentVoiceStore {
    private val items = LinkedHashMap<String, UnsentVoice>()
    var failAdd = false

    @Synchronized
    override fun load(): List<UnsentVoice> = items.values.filter { it.file.isFile }.sortedByDescending { it.createdAtMs }

    @Synchronized
    override fun add(item: UnsentVoice): UnsentVoice? {
        if (failAdd) return null
        items[item.id] = item
        return item
    }

    @Synchronized
    override fun update(item: UnsentVoice): Boolean {
        if (item.id !in items) return false
        items[item.id] = item
        return true
    }

    @Synchronized
    override fun remove(id: String): Boolean {
        val it = items.remove(id) ?: return false
        it.file.delete()
        return true
    }

    @Synchronized
    override fun clear(): Int {
        val n = items.size
        items.values.forEach { it.file.delete() }
        items.clear()
        return n
    }

    @Synchronized
    override fun count(): Int = items.size
}
