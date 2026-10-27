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
 * Keystore 가 깨진 기기(드물게 에뮬레이터 이미지 재생성 후)에서는 생성이 예외를 던진다 → 크래시 대신
 * **메모리 전용**으로 내려가고 경고 로그만 남긴다(다음 실행에서 다시 페어링하면 된다).
 */
class EncryptedTokenStore(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TokenStore {

    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var prefs: SharedPreferences? = null
    private var prefsResolved = false
    private var memoryOnly: StoredCredentials? = null
    private var loaded = false

    private val _state = MutableStateFlow<TokenStoreState>(TokenStoreState.Loading)
    override val state: StateFlow<TokenStoreState> = _state.asStateFlow()

    override suspend fun load(): StoredCredentials? = mutex.withLock {
        if (loaded) return currentUnlocked()
        val creds = withContext(io) { readFromDisk() }
        loaded = true
        publish(creds)
        creds
    }

    override suspend fun save(credentials: StoredCredentials) = mutex.withLock {
        withContext(io) {
            val p = resolvePrefs()
            if (p == null) {
                memoryOnly = credentials
            } else {
                p.edit()
                    .putString(KEY_ACCESS, credentials.vehicleAccessToken)
                    .putString(KEY_REFRESH, credentials.refreshToken)
                    .putString(KEY_VEHICLE_ID, credentials.vehicleId)
                    .putString(KEY_NICKNAME, credentials.nickname)
                    .putString(KEY_OWNER_ID, credentials.ownerId)
                    .putString(KEY_MQTT_URI, credentials.mqttUri)
                    .putString(KEY_MQTT_CLIENT_ID, credentials.mqttClientId)
                    .putString(KEY_MQTT_USERNAME, credentials.mqttUsername)
                    .commit()
            }
        }
        loaded = true
        publish(credentials)
        DhLog.i("token store: saved vehicleId=${credentials.vehicleId} token=${TextMasking.maskToken(credentials.vehicleAccessToken)}")
    }

    override suspend fun clear() = mutex.withLock {
        withContext(io) {
            memoryOnly = null
            resolvePrefs()?.edit()?.clear()?.commit()
        }
        loaded = true
        publish(null)
        DhLog.i("token store: cleared")
    }

    private fun currentUnlocked(): StoredCredentials? = (_state.value as? TokenStoreState.Present)?.credentials

    private fun publish(credentials: StoredCredentials?) {
        _state.value = if (credentials == null) TokenStoreState.Empty else TokenStoreState.Present(credentials)
    }

    private fun readFromDisk(): StoredCredentials? {
        val p = resolvePrefs() ?: return memoryOnly
        val access = p.getString(KEY_ACCESS, null) ?: return null
        val vehicleId = p.getString(KEY_VEHICLE_ID, null) ?: return null
        return StoredCredentials(
            vehicleAccessToken = access,
            refreshToken = p.getString(KEY_REFRESH, "") ?: "",
            vehicleId = vehicleId,
            nickname = p.getString(KEY_NICKNAME, "") ?: "",
            ownerId = p.getString(KEY_OWNER_ID, "") ?: "",
            mqttUri = p.getString(KEY_MQTT_URI, "") ?: "",
            mqttClientId = p.getString(KEY_MQTT_CLIENT_ID, "") ?: "",
            mqttUsername = p.getString(KEY_MQTT_USERNAME, "") ?: "",
        )
    }

    /** 첫 호출에서만 생성한다(MasterKey 생성은 수백 ms). 실패하면 null → 메모리 전용. */
    private fun resolvePrefs(): SharedPreferences? {
        if (prefsResolved) return prefs
        prefsResolved = true
        prefs = try {
            // security-crypto 1.0.0: MasterKeys.getOrCreate(AES256_GCM_SPEC) → Keystore 별칭 문자열 (MasterKey.Builder 는 1.1.x)
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                FILE_NAME,
                masterKeyAlias,
                appContext,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            DhLog.e("EncryptedSharedPreferences 생성 실패 — 이번 실행은 토큰을 메모리에만 둔다", e)
            null
        }
        return prefs
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
    }
}
