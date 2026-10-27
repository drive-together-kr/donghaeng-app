package app.donghaeng.car.domain.session

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupJoined
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.Place
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

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

/** 활성 그룹 저장소. 구현: [DataStoreGroupSessionStore](기기) / [InMemoryGroupSessionStore](테스트) */
interface GroupSessionStore {
    /** Loading 구분이 필요 없어 null = 없음. [load] 전에는 null */
    val session: StateFlow<GroupSession?>
    suspend fun load(): GroupSession?
    suspend fun save(session: GroupSession)
    suspend fun clear()
}

class InMemoryGroupSessionStore(initial: GroupSession? = null) : GroupSessionStore {
    private val _session = MutableStateFlow(initial)
    override val session: StateFlow<GroupSession?> = _session.asStateFlow()
    override suspend fun load(): GroupSession? = _session.value
    override suspend fun save(session: GroupSession) { _session.value = session }
    override suspend fun clear() { _session.value = null }
}

private val Context.groupSessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "group_session")

/** DataStore(Preferences) 구현 — 좌표(목적지)가 들어가므로 로그에는 groupId 만 남긴다 */
class DataStoreGroupSessionStore(context: Context) : GroupSessionStore {

    private val store = context.applicationContext.groupSessionDataStore
    private val _session = MutableStateFlow<GroupSession?>(null)
    override val session: StateFlow<GroupSession?> = _session.asStateFlow()

    override suspend fun load(): GroupSession? {
        val prefs = store.data.first()
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

    override suspend fun clear() {
        store.edit { it.clear() }
        _session.value = null
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
