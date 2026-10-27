package app.donghaeng.car.core.common

import android.content.Context

/**
 * 시연 보조 설정 (T30, docs/09 §1). 어댑터 바인딩 시점에 읽으므로 바꾼 뒤에는 앱을 다시 시작해야 적용된다.
 *
 * - `gpxRole`: fake flavor 의 [app.donghaeng.car.adapter.navi.FakeNaviAdapter] 가 재생할 GPX — `lead`(선두, 기본) / `follow`(후속: 목적지가 생길 때까지 대기).
 *   debug 빌드의 `DemoControlReceiver`(`am broadcast … --es action gpx --es role follow`) 로 바꾼다.
 */
object DemoPrefs {
    const val ROLE_LEAD = "lead"
    const val ROLE_FOLLOW = "follow"

    private const val FILE = "demo"
    private const val KEY_GPX_ROLE = "gpxRole"

    fun gpxRole(context: Context): String =
        prefs(context).getString(KEY_GPX_ROLE, ROLE_LEAD).let { if (it == ROLE_FOLLOW) ROLE_FOLLOW else ROLE_LEAD }

    fun setGpxRole(context: Context, role: String) {
        prefs(context).edit().putString(KEY_GPX_ROLE, if (role == ROLE_FOLLOW) ROLE_FOLLOW else ROLE_LEAD).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
