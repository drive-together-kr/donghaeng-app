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
            "loc_order" -> {
                val order = intent.getStringExtra("order") ?: DemoPrefs.ORDER_DEFAULT
                DemoPrefs.setLocationOrder(context, order)
                DhLog.i("demo: loc_order=${DemoPrefs.locationOrder(context)} (pleos flavor, 앱 재시작 후 적용)")
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
                intent.getStringExtra("variant")?.let { launch.putExtra(app.donghaeng.car.MainActivity.EXTRA_VARIANT, it) }
                context.startActivity(launch)
                DhLog.i("demo: open screen=$screen")
            }
            "overlay" -> {
                val on = intent.getBooleanExtra("on", true)
                val svc = Intent(context, OverlayProbeService::class.java).putExtra("cmd", if (on) "show" else "hide")
                context.startService(svc)
                DhLog.i("demo: overlay on=$on canDraw=${android.provider.Settings.canDrawOverlays(context)}")
            }
            "notify" -> ProbeNotifications.post(context, intent.getStringExtra("kind") ?: "status")
            "panel" -> {
                val svc = Intent(context, FloatingPanelProbeService::class.java)
                    .putExtra("on", intent.getBooleanExtra("on", true))
                    .putExtra("variant", intent.getStringExtra("variant") ?: "base")
                context.startService(svc)
                DhLog.i("demo: panel variant=${intent.getStringExtra("variant") ?: "base"} on=${intent.getBooleanExtra("on", true)}")
            }
            "navi_screen" -> {
                // 지도 렌더링 확인용: 순정 내비의 경로 옵션 화면(전체 경로가 보이게 축소) 요청
                navi.showNavigation()
                DhLog.i("demo: navi_screen requested")
            }
            "navi_waypoint" -> {
                // 시안(a'): 그룹 휴게소를 순정 내비 경유지로 넣어 내비가 스스로 핀·경로를 그리게 한다
                val dest = sessionStore.session.value?.destination
                if (dest == null) DhLog.w("demo: navi_waypoint 그룹 없음")
                else { navi.requestRouteWithWaypoints(dest, listOf(RouteSyncEngine.DEMO_WAYPOINT)); DhLog.i("demo: navi_waypoint → ${dest.toLogString()} + ${RouteSyncEngine.DEMO_WAYPOINT.name}") }
            }
            "fgs_text" -> {
                // 상단 상시 카드(포그라운드 서비스 알림)가 내용 갱신을 반영하는지 — 같은 채널·ID 로 다시 올린다
                val text = intent.getStringExtra("text") ?: "아빠 앞 1.2 km · 삼촌 뒤 2.1 km · 둘째 뒤 5.7 km"
                val title = intent.getStringExtra("title") ?: "동행 · 강릉 가족 여행 · 그룹 도착 14:35"
                // 서비스가 같은 ID 로 startForeground 를 다시 호출하게 한다(알림만 바꾸면 상단 카드가 안 바뀜 — 실측)
                val svc = Intent(context, app.donghaeng.car.service.DriveSessionService::class.java)
                    .putExtra(app.donghaeng.car.service.DriveSessionService.EXTRA_STATUS_TITLE, title)
                    .putExtra(app.donghaeng.car.service.DriveSessionService.EXTRA_STATUS_TEXT, text)
                runCatching { context.startForegroundService(svc) }.onFailure { DhLog.w("demo: fgs_text startForegroundService 실패 ${it.javaClass.simpleName}") }
                DhLog.i("demo: fgs_text requested len=${text.length}")
            }
            "focus" -> ProbeAudioFocus.test(context, intent.getStringExtra("usage") ?: "nav")
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
