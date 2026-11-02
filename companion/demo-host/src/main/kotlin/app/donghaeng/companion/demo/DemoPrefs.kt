package app.donghaeng.companion.demo

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.util.Random

/**
 * 이 폰(설치)에 남기는 시연 호스트의 작은 상태. 토큰은 여기 두지 않는다(메모리 전용 — [DemoSession]).
 *
 * - **로그인 이메일 기본값**(D026·D031): 심사위원마다 다른 dev 계정이 되도록 처음 실행 때 `judge-<8hex>@dev.local` 을 만들어 둔다.
 *   같은 이메일 = 같은 서버 계정이라, 모두가 같은 기본값을 쓰면 서로의 차·그룹이 섞인다. 로그인한 이메일은 다음 기본값이 된다.
 * - **진행 중 그룹**(D024): 앱이 다시 떠도(프로세스 종료·로그아웃 후 같은 계정 로그인) 그룹 관찰을 이어 간다.
 * - **차량별 참여 끔**(D078·D092·D116): 이 폰의 '그룹 합류'에서 고르지 않을 차.
 */
interface DemoStorage {
    fun loadActiveGroup(): SavedGroup?
    fun saveActiveGroup(group: SavedGroup?)
    fun loadParticipationOff(): Set<String>
    fun saveParticipationOff(vehicleIds: Set<String>)
}

/**
 * 다시 띄운 뒤 관찰을 이어 가는 데 필요한 최소 정보. [ownerUserId] 가 지금 로그인한 계정과 같을 때만 되살린다.
 * 멤버 목록은 저장하지 않는다(첫 폴링 스냅샷이 채운다).
 */
data class SavedGroup(
    val ownerUserId: String,
    val groupId: String,
    val name: String,
    val myVehicleId: String,
    val myVehicleNickname: String,
    val myVehicleConfirmed: Boolean,
    val destinationName: String,
    val leaderVehicleId: String?,
    val joinedAtMs: Long,
)

/** 메모리 구현 — 단위 테스트·[DemoPrefs.init] 전 */
class MemoryDemoStorage : DemoStorage {
    @Volatile
    private var group: SavedGroup? = null

    @Volatile
    private var off: Set<String> = emptySet()

    override fun loadActiveGroup(): SavedGroup? = group
    override fun saveActiveGroup(group: SavedGroup?) {
        this.group = group
    }

    override fun loadParticipationOff(): Set<String> = off
    override fun saveParticipationOff(vehicleIds: Set<String>) {
        off = vehicleIds.toSet()
    }
}

/** 설치마다 다른 시연 계정 이메일 */
object DemoIdentity {
    private const val HEX = "0123456789abcdef"
    private val FORMAT = Regex("^judge-[0-9a-f]{8}@dev\\.local$")

    fun newJudgeEmail(random: Random = SecureRandom()): String =
        "judge-" + String(CharArray(8) { HEX[random.nextInt(16)] }) + "@dev.local"

    fun isGeneratedEmail(email: String): Boolean = FORMAT.matches(email)
}

/** SharedPreferences(앱 전용) 구현. [DemoApplication.onCreate] 에서 [init] 한다 */
object DemoPrefs : DemoStorage {
    private const val FILE = "donghaeng_demo"
    private const val KEY_EMAIL = "login_email"
    private const val KEY_PARTICIPATION_OFF = "participation_off"
    private const val G = "active_group."

    @Volatile
    private var prefs: SharedPreferences? = null
    private val memory = MemoryDemoStorage()

    @Volatile
    private var memoryEmail: String? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    /** 로그인 화면의 이메일 기본값. 없으면 이 설치의 시연 계정 이메일을 만들어 저장한다 */
    fun loginEmail(): String {
        val p = prefs ?: return memoryEmail ?: DemoIdentity.newJudgeEmail().also { memoryEmail = it }
        p.getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val created = DemoIdentity.newJudgeEmail()
        p.edit().putString(KEY_EMAIL, created).apply()
        return created
    }

    /** 로그인에 성공한 이메일을 다음 기본값으로 */
    fun rememberLoginEmail(email: String) {
        val p = prefs ?: run { memoryEmail = email; return }
        p.edit().putString(KEY_EMAIL, email).apply()
    }

    override fun loadActiveGroup(): SavedGroup? {
        val p = prefs ?: return memory.loadActiveGroup()
        val groupId = p.getString(G + "groupId", null) ?: return null
        val owner = p.getString(G + "owner", null) ?: return null
        return SavedGroup(
            ownerUserId = owner,
            groupId = groupId,
            name = p.getString(G + "name", null).orEmpty(),
            myVehicleId = p.getString(G + "myVehicleId", null).orEmpty(),
            myVehicleNickname = p.getString(G + "myVehicleNickname", null).orEmpty(),
            myVehicleConfirmed = p.getBoolean(G + "myVehicleConfirmed", false),
            destinationName = p.getString(G + "destination", null).orEmpty(),
            leaderVehicleId = p.getString(G + "leader", null),
            joinedAtMs = p.getLong(G + "joinedAt", 0L),
        )
    }

    override fun saveActiveGroup(group: SavedGroup?) {
        val p = prefs ?: return memory.saveActiveGroup(group)
        val edit = p.edit()
        listOf("groupId", "owner", "name", "myVehicleId", "myVehicleNickname", "myVehicleConfirmed", "destination", "leader", "joinedAt")
            .forEach { edit.remove(G + it) }
        if (group != null) {
            edit.putString(G + "groupId", group.groupId)
                .putString(G + "owner", group.ownerUserId)
                .putString(G + "name", group.name)
                .putString(G + "myVehicleId", group.myVehicleId)
                .putString(G + "myVehicleNickname", group.myVehicleNickname)
                .putBoolean(G + "myVehicleConfirmed", group.myVehicleConfirmed)
                .putString(G + "destination", group.destinationName)
                .putString(G + "leader", group.leaderVehicleId)
                .putLong(G + "joinedAt", group.joinedAtMs)
        }
        edit.apply()
    }

    override fun loadParticipationOff(): Set<String> {
        val p = prefs ?: return memory.loadParticipationOff()
        return p.getStringSet(KEY_PARTICIPATION_OFF, emptySet()).orEmpty().toSet()
    }

    override fun saveParticipationOff(vehicleIds: Set<String>) {
        val p = prefs ?: return memory.saveParticipationOff(vehicleIds)
        p.edit().putStringSet(KEY_PARTICIPATION_OFF, vehicleIds.toSet()).apply()
    }
}
