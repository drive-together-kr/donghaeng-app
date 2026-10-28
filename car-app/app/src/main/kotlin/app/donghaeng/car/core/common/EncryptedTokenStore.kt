package app.donghaeng.car.core.common

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 기기 저장소 구현 — `androidx.security:security-crypto:1.0.0` EncryptedSharedPreferences(값 AES256_GCM, 키 AES256_SIV),
 * 마스터 키는 Android Keystore 의 AES256_GCM(`MasterKeys.AES256_GCM_SPEC`). 파일명 `dh_tokens`.
 *
 * T33 오류 처리 (docs/10 R13):
 * - **생성·읽기 예외**(Keystore 손상, `SecurityException`("Could not decrypt value"), `GeneralSecurityException`, `IOException` 등)는
 *   [TokenStoreState.Unavailable] 로 알린다 — 미페어링(Empty)과 구분해 그룹 세션을 지우지 않는다. 생성 실패를 프로세스 동안 고정하지 않고
 *   다음 [load]/[save] 에서 다시 만든다(예전에는 첫 실패 뒤 메모리 전용으로 고정돼 저장이 조용히 사라졌다).
 * - **쓰기 실패**는 이번 프로세스 동안만 메모리에 두고(Present 유지 — 지금 받은 토큰으로 계속 동작) E 로그와 진단 기록을 남긴다.
 * - [clear] 는 사유·시각·마스킹 vehicleId 를 일반 SharedPreferences `dh_diag` 에 남긴다([DiagPrefs]). 암호화 파일을 고칠 수 없으면 파일째 지운다.
 */
class EncryptedTokenStore(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) : TokenStore {

    private val appContext = context.applicationContext
    private val diag = DiagPrefs(appContext)
    private val mutex = Mutex()
    private var prefs: SharedPreferences? = null

    /** 디스크 쓰기에 실패한 자격 증명 — 이번 프로세스 동안만 */
    private var memoryOnly: StoredCredentials? = null
    private var loaded = false
    private var diagLoaded = false

    private val _state = MutableStateFlow<TokenStoreState>(TokenStoreState.Loading)
    override val state: StateFlow<TokenStoreState> = _state.asStateFlow()

    private val _lastClear = MutableStateFlow<ClearRecord?>(null)
    override val lastClear: StateFlow<ClearRecord?> = _lastClear.asStateFlow()

    override suspend fun load(): StoredCredentials? = mutex.withLock {
        if (!diagLoaded) {
            _lastClear.value = withContext(io) { diag.lastTokenClear() }
            diagLoaded = true
        }
        if (loaded) return currentUnlocked()
        when (val read = withContext(io) { readFromDisk() }) {
            is Read.Ok -> {
                loaded = true
                publish(read.credentials)
                read.credentials
            }
            is Read.Failed -> {
                // loaded 는 false 로 둔다 → 다음 load() 가 다시 읽는다
                _state.value = TokenStoreState.Unavailable(read.reason)
                withContext(io) { diag.recordStoreError(ClearRecord("read:${read.reason}", clock(), null)) }
                DhLog.e("token store: unavailable reason=${read.reason} — 그룹 세션은 유지하고 다시 시도한다")
                null
            }
        }
    }

    override suspend fun save(credentials: StoredCredentials) = mutex.withLock {
        persistUnlocked(credentials)
        loaded = true
        publish(credentials)
        // vehicleId 도 마스킹(2026-10-28) — 시연 닉네임 맞춤(debug `nickname`·그룹 스냅샷 동기화)이 save 를 부르는데, 그 흐름은 id 원문을 로그에 남기지 않기로 했다(`is_me`)
        DhLog.i("token store: saved vehicleId=${TextMasking.maskVehicleId(credentials.vehicleId)} token=${TextMasking.maskToken(credentials.vehicleAccessToken)}")
    }

    override suspend fun updateTokens(expectedRefreshToken: String, accessToken: String, refreshToken: String): StoredCredentials? = mutex.withLock {
        val current = currentUnlocked() ?: return null
        if (current.refreshToken != expectedRefreshToken) return null
        val updated = current.copy(vehicleAccessToken = accessToken, refreshToken = refreshToken)
        persistUnlocked(updated)
        publish(updated)
        DhLog.i("token store: tokens rotated vehicleId=${TextMasking.maskVehicleId(updated.vehicleId)} token=${TextMasking.maskToken(accessToken)}")
        updated
    }

    override suspend fun clear(reason: TokenClearReason) = mutex.withLock { clearUnlocked(reason) }

    override suspend fun clearIf(expectedRefreshToken: String, reason: TokenClearReason): Boolean = mutex.withLock {
        val current = currentUnlocked() ?: return false
        if (current.refreshToken != expectedRefreshToken) {
            DhLog.i("token store: clear($reason) skipped — credentials changed meanwhile vehicleId=${TextMasking.maskVehicleId(current.vehicleId)}")
            return false
        }
        clearUnlocked(reason)
        true
    }

    private suspend fun clearUnlocked(reason: TokenClearReason) {
        val vehicleId = currentUnlocked()?.vehicleId ?: memoryOnly?.vehicleId
        val record = ClearRecord(reason.name, clock(), vehicleId?.let(TextMasking::maskVehicleId))
        withContext(io) {
            memoryOnly = null
            val cleared = if (reason == TokenClearReason.STORE_RESET) {
                false
            } else {
                runCatching { resolvePrefs().edit().clear().commit() }.getOrDefault(false)
            }
            if (!cleared) {
                // 저장소 초기화 또는 암호화 파일을 열 수 없음 → 파일째 지운다(키셋 포함, 다음 생성 때 새로 만든다)
                prefs = null
                val deleted = runCatching { appContext.deleteSharedPreferences(FILE_NAME) }.getOrDefault(false)
                DhLog.w("token store: ${FILE_NAME} 파일 삭제 deleted=$deleted reason=$reason")
            }
            diag.recordTokenClear(record)
        }
        loaded = true
        publish(null)
        _lastClear.value = record
        DhLog.i("token store: cleared reason=$reason vehicleId=${record.idMasked ?: "-"}")
    }

    private fun currentUnlocked(): StoredCredentials? = (_state.value as? TokenStoreState.Present)?.credentials

    private fun publish(credentials: StoredCredentials?) {
        _state.value = if (credentials == null) TokenStoreState.Empty else TokenStoreState.Present(credentials)
    }

    /** 한 번의 commit 으로 전부 쓴다(회전된 토큰 쌍이 반쯤 저장되는 일이 없게). 실패하면 메모리에만 둔다 */
    private suspend fun persistUnlocked(credentials: StoredCredentials) {
        val failure = withContext(io) {
            try {
                val ok = resolvePrefs().edit()
                    .putString(KEY_ACCESS, credentials.vehicleAccessToken)
                    .putString(KEY_REFRESH, credentials.refreshToken)
                    .putString(KEY_VEHICLE_ID, credentials.vehicleId)
                    .putString(KEY_NICKNAME, credentials.nickname)
                    .putString(KEY_OWNER_ID, credentials.ownerId)
                    .putString(KEY_MQTT_URI, credentials.mqttUri)
                    .putString(KEY_MQTT_CLIENT_ID, credentials.mqttClientId)
                    .putString(KEY_MQTT_USERNAME, credentials.mqttUsername)
                    .commit()
                if (ok) null else "commit=false"
            } catch (e: Exception) {
                prefs = null
                describe(e)
            }
        }
        if (failure == null) {
            memoryOnly = null
        } else {
            memoryOnly = credentials
            withContext(io) { diag.recordStoreError(ClearRecord("write:$failure", clock(), TextMasking.maskVehicleId(credentials.vehicleId))) }
            DhLog.e("token store: save failed reason=$failure — 이번 실행 동안만 메모리에 둔다(재시작하면 다시 연결해야 할 수 있다)")
        }
    }

    private sealed interface Read {
        data class Ok(val credentials: StoredCredentials?) : Read
        data class Failed(val reason: String) : Read
    }

    private fun readFromDisk(): Read {
        memoryOnly?.let { return Read.Ok(it) }
        return try {
            val p = resolvePrefs()
            val access = p.getString(KEY_ACCESS, null) ?: return Read.Ok(null)
            val vehicleId = p.getString(KEY_VEHICLE_ID, null) ?: return Read.Ok(null)
            Read.Ok(
                StoredCredentials(
                    vehicleAccessToken = access,
                    refreshToken = p.getString(KEY_REFRESH, "") ?: "",
                    vehicleId = vehicleId,
                    nickname = p.getString(KEY_NICKNAME, "") ?: "",
                    ownerId = p.getString(KEY_OWNER_ID, "") ?: "",
                    mqttUri = p.getString(KEY_MQTT_URI, "") ?: "",
                    mqttClientId = p.getString(KEY_MQTT_CLIENT_ID, "") ?: "",
                    mqttUsername = p.getString(KEY_MQTT_USERNAME, "") ?: "",
                ),
            )
        } catch (e: Exception) {
            // security-crypto 1.0.0: 값 복호화 실패는 SecurityException, 키셋·Keystore 문제는 GeneralSecurityException/IOException/ProviderException
            prefs = null
            Read.Failed(describe(e))
        }
    }

    /** 만들어 둔 게 있으면 그대로, 없으면 새로 만든다(MasterKey 생성은 수백 ms). 실패하면 예외 — 다음 호출에서 다시 시도한다 */
    private fun resolvePrefs(): SharedPreferences {
        prefs?.let { return it }
        // security-crypto 1.0.0: MasterKeys.getOrCreate(AES256_GCM_SPEC) → Keystore 별칭 문자열 (MasterKey.Builder 는 1.1.x)
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        val created = EncryptedSharedPreferences.create(
            FILE_NAME,
            masterKeyAlias,
            appContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        prefs = created
        return created
    }

    private companion object {
        const val FILE_NAME = "dh_tokens"
        const val KEY_ACCESS = "vehicle_access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_VEHICLE_ID = "vehicle_id"
        const val KEY_NICKNAME = "nickname"
        const val KEY_OWNER_ID = "owner_id"
        const val KEY_MQTT_URI = "mqtt_uri"
        const val KEY_MQTT_CLIENT_ID = "mqtt_client_id"
        const val KEY_MQTT_USERNAME = "mqtt_username"

        /** 예외 사슬의 클래스 이름만(메시지에는 경로·키 별칭이 섞일 수 있다). 예: `SecurityException<AEADBadTagException` */
        fun describe(e: Throwable): String =
            generateSequence(e) { it.cause?.takeIf { c -> c !== it } }.take(3).joinToString("<") { it.javaClass.simpleName }
    }
}
