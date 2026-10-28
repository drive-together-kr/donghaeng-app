package app.donghaeng.car.domain.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.donghaeng.car.core.common.ClearRecord
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.DiagPrefs
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupJoined
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** 그룹 초대(방장 차량만 가짐) — 서버 `invite` 그대로. QR 내용은 [url] */
data class GroupInvite(val token: String, val url: String, val expiresAtMs: Long)

/**
 * 차량이 지금 들어가 있는 그룹. 방장은 `POST /groups` 응답(T17)으로, 멤버는 cmd `GROUP_JOINED`(T16/T18)로 만들어진다.
 * 앱 재시작 뒤 자동 재구독에 쓴다(docs/04 §3 `activeGroupId`).
 */
data class GroupSession(
    val groupId: String,
    val name: String,
    val leaderVehicleId: String,
    val destination: Place,
    val topics: GroupTopics,
    val joinedAtMs: Long,
    /** 이 차가 만든(방장) 그룹인가 */
    val isLeader: Boolean = false,
    /** 방장 차량의 현재 초대(QR). 멤버는 null */
    val invite: GroupInvite? = null,
) {
    val subscribeTopics: List<String> get() = listOf(topics.state, topics.event)

    companion object {
        fun from(cmd: GroupJoined, nowMs: Long) = GroupSession(cmd.groupId, cmd.name, cmd.leaderVehicleId, cmd.destination, cmd.topics, nowMs)
    }
}

/** 그룹 세션 삭제 사유 (T33). [ClearRecord.reason] 에 이름으로 남는다 */
enum class SessionClearReason {
    /** 토큰 저장소가 Empty(연결 해제·TOKEN_REVOKED·refresh 거부). 읽기 실패(Unavailable)는 해당하지 않는다 */
    UNPAIRED,

    /** cmd `GROUP_ENDED` */
    GROUP_ENDED,

    /** 그룹 이벤트 `MEMBER_LEFT` 의 대상이 이 차(폰·REST 가 내보냄, T30) */
    MEMBER_LEFT_ME,

    /** 이 차가 직접 나가기·종료 */
    LEFT_LOCALLY,

    /** `group_session` 파일 손상·해석 불가 → 비움 */
    STORE_CORRUPT,
}

/** 활성 그룹 저장소. 구현: [DataStoreGroupSessionStore](기기) / [InMemoryGroupSessionStore](테스트) */
interface GroupSessionStore {
    /** Loading 구분이 필요 없어 null = 없음. [load] 전에는 null */
    val session: StateFlow<GroupSession?>

    /** 마지막 세션 삭제 기록(프로세스를 넘어 유지, `dh_diag`). 없으면 null */
    val lastClear: StateFlow<ClearRecord?>

    suspend fun load(): GroupSession?
    suspend fun save(session: GroupSession)

    /** 세션을 지우고 사유·시각·마스킹 groupId 를 기록한다 */
    suspend fun clear(reason: SessionClearReason)
}

class InMemoryGroupSessionStore(
    initial: GroupSession? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : GroupSessionStore {
    private val _session = MutableStateFlow(initial)
    override val session: StateFlow<GroupSession?> = _session.asStateFlow()
    private val _lastClear = MutableStateFlow<ClearRecord?>(null)
    override val lastClear: StateFlow<ClearRecord?> = _lastClear.asStateFlow()

    /** 테스트: [clear] 에 들어온 사유 순서 */
    val clearReasons = mutableListOf<SessionClearReason>()

    override suspend fun load(): GroupSession? = _session.value
    override suspend fun save(session: GroupSession) { _session.value = session }
    override suspend fun clear(reason: SessionClearReason) {
        clearReasons += reason
        _lastClear.value = ClearRecord(reason.name, clock(), _session.value?.groupId?.let(TextMasking::maskVehicleId))
        _session.value = null
    }
}

/** 손상 핸들러가 파일을 갈아 끼웠다는 표시 — 다음 load() 가 STORE_CORRUPT 로 기록한다(핸들러 람다에는 Context 가 없다) */
private val groupSessionCorruptionSeen = AtomicBoolean(false)

private val Context.groupSessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "group_session",
    // T33: 손상(CorruptionException)이면 빈 값으로 갈아 끼운다 — 예전에는 load() 가 예외를 던져 MqttSessionManager 시작 코루틴이 죽었다
    corruptionHandler = ReplaceFileCorruptionHandler { e ->
        groupSessionCorruptionSeen.set(true)
        DhLog.w("group session store corrupted → replaced with empty", e)
        emptyPreferences()
    },
)

/** DataStore(Preferences) 구현 — 좌표(목적지)가 들어가므로 로그에는 groupId 만 남긴다 */
class DataStoreGroupSessionStore(context: Context) : GroupSessionStore {

    private val store = context.applicationContext.groupSessionDataStore
    private val diag = DiagPrefs(context.applicationContext)
    private val _session = MutableStateFlow<GroupSession?>(null)
    override val session: StateFlow<GroupSession?> = _session.asStateFlow()
    private val _lastClear = MutableStateFlow<ClearRecord?>(null)
    override val lastClear: StateFlow<ClearRecord?> = _lastClear.asStateFlow()

    override suspend fun load(): GroupSession? {
        if (_lastClear.value == null) _lastClear.value = withContext(Dispatchers.IO) { diag.lastSessionClear() }
        // 읽기 I/O 오류는 '이번엔 못 읽음' — 파일은 지우지 않는다
        val prefs = store.data
            .catch { e ->
                if (e !is IOException) throw e
                DhLog.w("group session read failed: ${e.javaClass.simpleName}")
                emit(emptyPreferences())
            }
            .first()
        if (groupSessionCorruptionSeen.getAndSet(false)) record(SessionClearReason.STORE_CORRUPT, groupId = null)
        val groupId = prefs[KEY_GROUP_ID]
        val session = if (groupId == null) {
            null
        } else {
            runCatching {
                GroupSession(
                    groupId = groupId,
                    name = prefs[KEY_NAME].orEmpty(),
                    leaderVehicleId = prefs[KEY_LEADER].orEmpty(),
                    destination = DhJson.json.decodeFromString(Place.serializer(), prefs[KEY_DEST] ?: error("no destination")),
                    topics = GroupTopics(state = prefs[KEY_TOPIC_STATE].orEmpty(), event = prefs[KEY_TOPIC_EVENT].orEmpty()),
                    joinedAtMs = prefs[KEY_JOINED_AT] ?: 0L,
                    isLeader = prefs[KEY_IS_LEADER] ?: false,
                    invite = prefs[KEY_INVITE_TOKEN]?.let { token ->
                        GroupInvite(token, prefs[KEY_INVITE_URL].orEmpty(), prefs[KEY_INVITE_EXPIRES] ?: 0L)
                    },
                )
            }.onFailure { DhLog.w("group session load failed: ${it.javaClass.simpleName}") }.getOrNull()
        }
        if (groupId != null && session == null) {
            // 해석할 수 없는 세션(필드 누락·형식 변경) — 남겨 두면 매번 실패하므로 비우고 사유를 남긴다
            try {
                store.edit { it.clear() }
            } catch (e: IOException) {
                DhLog.w("group session clear (corrupt) failed: ${e.javaClass.simpleName}")
            }
            record(SessionClearReason.STORE_CORRUPT, groupId)
        }
        _session.value = session
        return session
    }

    override suspend fun save(session: GroupSession) {
        store.edit { p ->
            p[KEY_GROUP_ID] = session.groupId
            p[KEY_NAME] = session.name
            p[KEY_LEADER] = session.leaderVehicleId
            p[KEY_DEST] = DhJson.json.encodeToString(Place.serializer(), session.destination)
            p[KEY_TOPIC_STATE] = session.topics.state
            p[KEY_TOPIC_EVENT] = session.topics.event
            p[KEY_JOINED_AT] = session.joinedAtMs
            p[KEY_IS_LEADER] = session.isLeader
            val invite = session.invite
            if (invite == null) {
                p.remove(KEY_INVITE_TOKEN); p.remove(KEY_INVITE_URL); p.remove(KEY_INVITE_EXPIRES)
            } else {
                p[KEY_INVITE_TOKEN] = invite.token
                p[KEY_INVITE_URL] = invite.url
                p[KEY_INVITE_EXPIRES] = invite.expiresAtMs
            }
        }
        _session.value = session
    }

    override suspend fun clear(reason: SessionClearReason) {
        val groupId = _session.value?.groupId
        try {
            store.edit { it.clear() }
        } catch (e: IOException) {
            DhLog.w("group session clear failed: ${e.javaClass.simpleName}")
        }
        _session.value = null
        record(reason, groupId)
    }

    private suspend fun record(reason: SessionClearReason, groupId: String?) {
        val record = ClearRecord(reason.name, System.currentTimeMillis(), groupId?.let(TextMasking::maskVehicleId))
        withContext(Dispatchers.IO) { diag.recordSessionClear(record) }
        _lastClear.value = record
        DhLog.i("group session cleared reason=$reason groupId=${record.idMasked ?: "-"}")
    }

    private companion object {
        val KEY_GROUP_ID = stringPreferencesKey("groupId")
        val KEY_NAME = stringPreferencesKey("name")
        val KEY_LEADER = stringPreferencesKey("leaderVehicleId")
        val KEY_DEST = stringPreferencesKey("destination")
        val KEY_TOPIC_STATE = stringPreferencesKey("topicState")
        val KEY_TOPIC_EVENT = stringPreferencesKey("topicEvent")
        val KEY_JOINED_AT = longPreferencesKey("joinedAtMs")
        val KEY_IS_LEADER = booleanPreferencesKey("isLeader")
        val KEY_INVITE_TOKEN = stringPreferencesKey("inviteToken")
        val KEY_INVITE_URL = stringPreferencesKey("inviteUrl")
        val KEY_INVITE_EXPIRES = longPreferencesKey("inviteExpiresAtMs")
    }
}
