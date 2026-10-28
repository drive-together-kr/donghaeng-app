package app.donghaeng.car.core.common

import android.content.Context

/**
 * 시연 보조 설정 (T30, docs/09 §1). 어댑터 바인딩 시점에 읽으므로 바꾼 뒤에는 앱을 다시 시작해야 적용된다.
 *
 * - `gpxRole`: fake flavor 의 [app.donghaeng.car.adapter.navi.FakeNaviAdapter] 가 재생할 GPX — `lead`(선두, 기본) / `follow`(후속: 목적지가 생길 때까지 대기).
 *   debug 빌드의 `DemoControlReceiver`(`am broadcast … --es action gpx --es role follow`) 로 바꾼다.
 * - `locationOrder`: pleos flavor 위치 소스 순서 — `default`(Fused → 내비 → Android → Fake) / `android_first`(Android → 내비 → Fake).
 *   Pleos AVD 의 순정 내비는 에뮬레이터 GNSS 의 NMEA 체크섬 오류 때문에 위치가 기본값에 고정된다(docs/10 R17) → 시연에서 `adb emu geo fix`
 *   (tools/demo/replay_gpx.py)로 움직이려면 `android_first` 로 둔다. 앱 재시작 후 적용. 실차 기본값은 `default`.
 */
object DemoPrefs {
    const val ROLE_LEAD = "lead"
    const val ROLE_FOLLOW = "follow"

    private const val FILE = "demo"
    private const val KEY_GPX_ROLE = "gpxRole"
    private const val KEY_LOCATION_ORDER = "locationOrder"
    const val ORDER_DEFAULT = "default"
    const val ORDER_ANDROID_FIRST = "android_first"

    fun locationOrder(context: Context): String =
        prefs(context).getString(KEY_LOCATION_ORDER, ORDER_DEFAULT).let { if (it == ORDER_ANDROID_FIRST) ORDER_ANDROID_FIRST else ORDER_DEFAULT }

    fun setLocationOrder(context: Context, order: String) {
        prefs(context).edit().putString(KEY_LOCATION_ORDER, if (order == ORDER_ANDROID_FIRST) ORDER_ANDROID_FIRST else ORDER_DEFAULT).apply()
    }

    fun gpxRole(context: Context): String =
        prefs(context).getString(KEY_GPX_ROLE, ROLE_LEAD).let { if (it == ROLE_FOLLOW) ROLE_FOLLOW else ROLE_LEAD }

    fun setGpxRole(context: Context, role: String) {
        prefs(context).edit().putString(KEY_GPX_ROLE, if (role == ROLE_FOLLOW) ROLE_FOLLOW else ROLE_LEAD).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
