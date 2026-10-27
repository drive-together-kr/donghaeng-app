package app.donghaeng.car.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.donghaeng.car.adapter.navi.NaviAdapter
import app.donghaeng.car.core.common.DemoPrefs
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.domain.model.Place
import app.donghaeng.car.domain.model.Priority
import app.donghaeng.car.domain.model.toLogString
import app.donghaeng.car.domain.routesync.RouteSyncEngine
import app.donghaeng.car.domain.session.GroupSessionStore
import app.donghaeng.car.domain.voice.VoiceAnnouncer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 시연·확인용 adb 조작 리시버 (debug 빌드 전용, T30 — `tools/demo/demo.py` 가 쓴다).
 *
 * ```
 * adb -s <serial> shell am broadcast -n app.donghaeng.car/.debug.DemoControlReceiver --es action <action> [extras]
 * ```
 * | action | extras | 동작 |
 * |---|---|---|
 * | `route_test` | `name`,`lat`,`lon`(선택, 기본 덕평휴게소) | 선두차: 그룹 목적지 + 경유지 1개로 ROUTE_CHANGED 발행 |
 * | `accept` / `decline` | | 대기 중 제안 수락 / 거절 |
 * | `auto_follow` | `on`(ez) | 자동 따라가기 설정 |
 * | `speak` | `text` | 음성 큐에 문장 추가(NORMAL) |
 * | `voice` | `on`(ez) | 음성 안내 on/off |
 * | `navi_route` | | 그룹 목적지로 `requestRoute` |
 * | `gpx` | `role` = `lead`/`follow` | fake flavor GPX 역할 저장 — **앱 재시작 후 적용** |
 * | `status` | | 세션·제안·음성 상태 한 줄 로그 |
 * | `open` | `screen` = home/drive/events/create/settings/diagnostics | 앱을 전면으로 올리고 그 화면으로 이동(MainActivity extra `screen`) |
 *
 * 결과는 `logcat -s DongHaeng` 의 `demo:` 줄로 확인한다. 좌표·ID 는 마스킹 규칙(CLAUDE.md 8)을 따른다.
 * 의존성은 Hilt `@EntryPoint` 로 꺼낸다(BroadcastReceiver 의 `onReceive` 가 추상이라 `@AndroidEntryPoint` 의 super 호출을 쓸 수 없다).
 */
class DemoControlReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun routeSync(): RouteSyncEngine
        fun voice(): VoiceAnnouncer
        fun navi(): NaviAdapter
        fun sessionStore(): GroupSessionStore
    }

    private lateinit var routeSync: RouteSyncEngine
    private lateinit var voice: VoiceAnnouncer
    private lateinit var navi: NaviAdapter
    private lateinit var sessionStore: GroupSessionStore

    override fun onReceive(context: Context, intent: Intent) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        routeSync = deps.routeSync(); voice = deps.voice(); navi = deps.navi(); sessionStore = deps.sessionStore()
        val action = intent.getStringExtra(EXTRA_ACTION)
        if (action.isNullOrBlank()) {
            DhLog.w("demo: action 없음 (--es action …)")
            return
        }
        val pending = goAsync()
        scope.launch {
            try {
                handle(context, action, intent)
            } catch (e: Exception) {
                DhLog.w("demo: $action 실패 ${e.javaClass.simpleName}: ${e.message?.take(80)}")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, action: String, intent: Intent) {
        when (action) {
            "route_test" -> {
                val name = intent.getStringExtra("name")
                val lat = intent.getStringExtra("lat")?.toDoubleOrNull()
                val lon = intent.getStringExtra("lon")?.toDoubleOrNull()
                val waypoint = if (name != null && lat != null && lon != null) Place(lat = lat, lon = lon, name = name, poiId = null, address = null) else RouteSyncEngine.DEMO_WAYPOINT
                val sent = routeSync.publishTestRouteChange(waypoint)
                DhLog.i("demo: route_test waypoint=${waypoint.name} published=$sent (false = 그룹 없음·선두 아님·발행 실패)")
            }
            "accept" -> {
                val p = routeSync.state.value.pending
                routeSync.accept()
                DhLog.i("demo: accept pending=${p?.proposalId?.let(TextMasking::maskVehicleId) ?: "없음"} → ${routeSync.state.value.lastResult}")
            }
            "decline" -> {
                val p = routeSync.state.value.pending
                routeSync.decline()
                DhLog.i("demo: decline pending=${p?.proposalId?.let(TextMasking::maskVehicleId) ?: "없음"} → ${routeSync.state.value.lastResult}")
            }
            "auto_follow" -> {
                val on = intent.getBooleanExtra("on", true)
                routeSync.setAutoFollow(on)
                DhLog.i("demo: auto_follow=$on")
            }
            "speak" -> {
                val text = intent.getStringExtra("text").orEmpty().ifBlank { "시연 테스트 문장입니다." }
                voice.announce(text, Priority.NORMAL, "DEMO")
                DhLog.i("demo: speak len=${text.length} enabled=${voice.state.value.enabled}")
            }
            "voice" -> {
                val on = intent.getBooleanExtra("on", true)
                voice.setEnabled(on)
                DhLog.i("demo: voice=$on")
            }
            "navi_route" -> {
                val dest = sessionStore.session.value?.destination
                if (dest == null) {
                    DhLog.w("demo: navi_route 그룹 없음")
                } else {
                    navi.requestRoute(dest)
                    DhLog.i("demo: navi_route → ${dest.toLogString()}")
                }
            }
            "gpx" -> {
                val role = intent.getStringExtra("role") ?: DemoPrefs.ROLE_LEAD
                DemoPrefs.setGpxRole(context, role)
                DhLog.i("demo: gpx role=${DemoPrefs.gpxRole(context)} (fake flavor, 앱 재시작 후 적용)")
            }
            "open" -> {
                val screen = intent.getStringExtra("screen") ?: "home"
                val launch = Intent(context, app.donghaeng.car.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(app.donghaeng.car.MainActivity.EXTRA_SCREEN, screen)
                context.startActivity(launch)
                DhLog.i("demo: open screen=$screen")
            }
            "status" -> {
                val s = sessionStore.session.value
                val r = routeSync.state.value
                DhLog.i(
                    "demo: status group=${s?.groupId?.let(TextMasking::maskVehicleId) ?: "없음"} leader=${s?.isLeader} dest=${s?.destination?.name} " +
                        "pending=${r.pending?.proposalId?.let(TextMasking::maskVehicleId) ?: "없음"} accepted=${r.accepted} declined=${r.declined} autoDeclined=${r.autoDeclined} " +
                        "autoFollow=${r.autoFollow} routeChangedSent=${r.routeChangedSent} voice=${voice.state.value.enabled} gpx=${DemoPrefs.gpxRole(context)}",
                )
            }
            else -> DhLog.w("demo: 모르는 action=$action")
        }
    }

    private companion object {
        const val EXTRA_ACTION = "action"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
