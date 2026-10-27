package app.donghaeng.car.core.common

import android.util.Log
import app.donghaeng.car.BuildConfig

/**
 * 앱 공용 로그 래퍼. 태그는 "DongHaeng" (logcat 필터: `adb logcat -s DongHaeng`),
 * capability 점검 결과만 별도 태그 "DH_CAP" (`adb logcat -s DH_CAP`, docs/09 §3 체크리스트 기록용).
 *
 * 좌표·닉네임·토큰 원문은 로그에 남기지 않는다(CLAUDE.md 규칙 8) — 호출 측에서
 * [CoordinateMasking] / [TextMasking] 을 거친 문자열만 넘길 것.
 * `android.util.Log` 는 이 객체에서만 직접 호출한다(앱 전체의 단일 창구).
 */
object DhLog {
    const val TAG = "DongHaeng"

    /** capability 점검 전용 태그 — 값은 SDK 지원 여부뿐이라 개인정보가 없다 */
    const val CAP_TAG = "DH_CAP"

    fun i(message: String) {
        Log.i(TAG, message)
    }

    /** 디버그 빌드에서만 출력한다. */
    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    fun w(message: String, t: Throwable? = null) {
        if (t == null) Log.w(TAG, message) else Log.w(TAG, message, t)
    }

    fun e(message: String, t: Throwable? = null) {
        if (t == null) Log.e(TAG, message) else Log.e(TAG, message, t)
    }

    /** capability 한 항목 — `CapabilityEntry.format()` 결과를 그대로 넘긴다(예: `Fuel supported=true detail=accessType=READ source=PLEOS_SDK`). */
    fun cap(line: String) {
        Log.i(CAP_TAG, line)
    }
}
