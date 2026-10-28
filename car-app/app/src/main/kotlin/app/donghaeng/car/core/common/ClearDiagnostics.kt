package app.donghaeng.car.core.common

import android.content.Context
import android.content.SharedPreferences

/**
 * 저장소 삭제 기록 한 건 (T33, docs/10 R13 — "누가·왜 지웠나" 를 사후에 알 수 있게).
 *
 * @param reason   삭제 사유 enum 이름([TokenClearReason] / `SessionClearReason`)
 * @param atMs     삭제 시각(epoch ms, 기기 시계)
 * @param idMasked 지운 대상 ID 마스킹(토큰 = vehicleId, 그룹 세션 = groupId). 없으면 null
 */
data class ClearRecord(val reason: String, val atMs: Long, val idMasked: String?)

/**
 * 진단 기록 — **일반** SharedPreferences `dh_diag`(암호화 안 함: 사유·시각·마스킹 ID 만 담는다, 토큰·좌표 없음).
 * 토큰 저장소(`dh_tokens`)가 망가져도 이 기록은 남아야 하므로 별도 파일이다. 쓰기는 `commit()`(프로세스가 바로 죽어도 남게) — 호출은 IO 스레드에서.
 */
class DiagPrefs(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun lastTokenClear(): ClearRecord? = read(PREFIX_TOKEN)

    fun lastSessionClear(): ClearRecord? = read(PREFIX_SESSION)

    /** 마지막 저장소 오류(읽기·쓰기 실패) — reason 은 예외 클래스 이름 요약 */
    fun lastStoreError(): ClearRecord? = read(PREFIX_STORE_ERROR)

    fun recordTokenClear(record: ClearRecord) = write(PREFIX_TOKEN, record)

    fun recordSessionClear(record: ClearRecord) = write(PREFIX_SESSION, record)

    fun recordStoreError(record: ClearRecord) = write(PREFIX_STORE_ERROR, record)

    private fun read(prefix: String): ClearRecord? = runCatching {
        val reason = prefs.getString("${prefix}_reason", null) ?: return null
        ClearRecord(reason, prefs.getLong("${prefix}_at", 0L), prefs.getString("${prefix}_id", null))
    }.getOrNull()

    private fun write(prefix: String, record: ClearRecord) {
        runCatching {
            prefs.edit()
                .putString("${prefix}_reason", record.reason)
                .putLong("${prefix}_at", record.atMs)
                .putString("${prefix}_id", record.idMasked)
                .commit()
        }.onFailure { DhLog.w("diag prefs write failed key=$prefix cause=${it.javaClass.simpleName}") }
    }

    private companion object {
        const val FILE_NAME = "dh_diag"
        const val PREFIX_TOKEN = "last_token_clear"
        const val PREFIX_SESSION = "last_session_clear"
        const val PREFIX_STORE_ERROR = "last_token_store_error"
    }
}
