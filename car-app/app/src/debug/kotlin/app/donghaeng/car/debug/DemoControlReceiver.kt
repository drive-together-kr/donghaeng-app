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
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.domain.time.ClockSync
import app.donghaeng.car.surface.DriveSurfaceController
import app.donghaeng.car.surface.EventCardNotifier
import app.donghaeng.car.surface.OverlayPermissionMonitor
import app.donghaeng.car.surface.PanelCorner
import app.donghaeng.car.surface.SurfaceState
import app.donghaeng.car.surface.TurnProbe
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.Severity
import kotlinx.serialization.json.JsonObject
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
 * | `surface` | | 주행 중 표면 상태 한 줄 — `demo: surface mode=PANEL panelVisible=true canDraw=true appVisible=false … panelShown=true keeper=starts=1 … cards=posted=… held=<미룬 카드 수> … lastTbt=<마지막 TBT> … tbt=override=… lookups=…` |
 * | `surface_pos` | `corner` = top_end/top_start/bottom_end/bottom_start | 패널 모서리 강제 지정(저장됨) |
 * | `event` | `type`(기본 LOW_ENERGY), `speech`, `source`(기본 veh_DEMO), `age_s`(봉투 나이 — 61 이상이면 소식 줄·띠·카드에서 빠진다), `voice`(`--ez`, 기본 true) | 그룹 이벤트 한 건을 로컬로 흘려 패널 이벤트 줄·알림 카드·음성을 확인. 카드는 '카드 + 음성' 모드(CARDS_ONLY)에서만(PANEL 이면 `card=SKIPPED`, 로그 `reason=panel-mode`), 같은 종류·출처 30초에 한 장·5초에 2장까지(`source` 를 바꿔 반복), 다음 회전 300 m 이내면 `card=DEFERRED` |
 * | `tbt` | `m`(`--ei`, 다음 회전까지 m, -1 = 해제), `for_s`(`--ei`, 기본 30) | 카드 TBT 게이트가 읽는 다음 회전 거리를 `for_s` 초 동안 덮어쓴다(AVD 순정 내비는 위치가 고정돼 TBT 가 늘 같다). 예(CARDS_ONLY): `--es action tbt --ei m 120 --ei for_s 60` → `--es action event --es type HARD_BRAKE` → `surface: card deferred type=HARD_BRAKE tbt=120m` → `--es action tbt --ei m 800` → 1초 안에 `surface: card released … reason=far` |
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
        fun surfaceController(): DriveSurfaceController
        fun surfaceState(): SurfaceState
        fun surfaceCards(): EventCardNotifier
        fun surfaceOverlay(): OverlayPermissionMonitor
        fun clockSync(): ClockSync
        fun turnProbe(): TurnProbe
    }

    private lateinit var routeSync: RouteSyncEngine
    private lateinit var voice: VoiceAnnouncer
    private lateinit var navi: NaviAdapter
    private lateinit var sessionStore: GroupSessionStore
    private lateinit var surfaceController: DriveSurfaceController
    private lateinit var surfaceState: SurfaceState
    private lateinit var surfaceCards: EventCardNotifier
    private lateinit var surfaceOverlay: OverlayPermissionMonitor
    private lateinit var clockSync: ClockSync
    private lateinit var turnProbe: TurnProbe

    override fun onReceive(context: Context, intent: Intent) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        routeSync = deps.routeSync(); voice = deps.voice(); navi = deps.navi(); sessionStore = deps.sessionStore()
        surfaceController = deps.surfaceController(); surfaceState = deps.surfaceState(); surfaceCards = deps.surfaceCards()
        surfaceOverlay = deps.surfaceOverlay(); clockSync = deps.clockSync(); turnProbe = deps.turnProbe()
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
            "ongoing" -> if (intent.getBooleanExtra("on", true)) ProbeNotifications.postOngoing(context, intent.getStringExtra("importance") ?: "min") else ProbeNotifications.cancelOngoing(context)
            "surface" -> {
                // 주행 중 표면 상태 한 줄: 모드(OFF/PANEL/CARDS_ONLY)·패널 표시 여부·권한·모서리·카드 수·TBT 게이트(미룬 카드 수·마지막 TBT·덮어쓰기)
                surfaceOverlay.recheck()
                DhLog.i("demo: surface ${surfaceController.describe()} tbt=${turnProbe.describe()}")
            }
            "tbt" -> {
                // 카드 TBT 게이트 확인용: 다음 회전 거리를 잠시 덮어쓴다(m=-1 해제). 미룬 카드는 1초마다 다시 확인해 회전 후(> 300 m 등)에 올라간다
                val m = intent.getIntExtra("m", -1)
                val forS = intent.getIntExtra("for_s", 30)
                if (m < 0) turnProbe.clearOverride() else turnProbe.setOverride(m, forS * 1_000L)
                DhLog.i("demo: tbt m=$m for_s=$forS → ${turnProbe.describe()} cards=${surfaceCards.state.value}")
            }
            "surface_pos" -> {
                val corner = PanelCorner.parse(intent.getStringExtra("corner"))
                if (corner == null) DhLog.w("demo: surface_pos corner=${intent.getStringExtra("corner")} 모름 (top_end|top_start|bottom_end|bottom_start)")
                else { surfaceState.setCorner(corner); DhLog.i("demo: surface_pos corner=$corner") }
            }
            "event" -> {
                // 그룹 이벤트 한 건을 로컬로 흘려 표면(패널 이벤트 줄·알림 카드·음성)을 확인한다 — 서버를 거치지 않는다
                val type = intent.getStringExtra("type") ?: "LOW_ENERGY"
                val speech = intent.getStringExtra("speech") ?: demoSpeech(type)
                val source = intent.getStringExtra("source") ?: "veh_DEMO"
                val severity = when (type) { "AIRBAG_DEPLOYED" -> Severity.ALERT; "HARD_BRAKE", "LOW_ENERGY", "MEMBER_OFFLINE", "DEVIATION" -> Severity.WARN; else -> Severity.INFO }
                val now = System.currentTimeMillis()
                val e = ReceivedGroupEvent(
                    GroupEvent(eventId = "demo-$now", type = type, sourceVehicleId = source, severity = severity, speech = speech, payload = JsonObject(emptyMap())),
                    envelopeTsMs = clockSync.serverNow() - intent.getIntExtra("age_s", 0) * 1_000L,
                    receivedAtMs = now,
                )
                surfaceState.noteEvent(e)
                val carded = surfaceCards.onEvent(e)
                if (intent.getBooleanExtra("voice", true)) voice.onEvent(e)
                DhLog.i("demo: event type=$type source=${TextMasking.maskVehicleId(source)} card=$carded mode=${surfaceState.mode.value} len=${speech.length}")
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

    /** 디버그 `event` 기본 문구(가상 닉네임) */
    private fun demoSpeech(type: String): String = when (type) {
        "HARD_BRAKE" -> "앞쪽 시연 차가 급제동했어요. 주의하세요."
        "AIRBAG_DEPLOYED" -> "시연 차의 에어백이 터졌어요. 확인이 필요해요."
        "WAYPOINT_ARRIVED" -> "시연 차가 덕평휴게소에 도착했어요."
        "DESTINATION_ARRIVED" -> "시연 차가 목적지에 도착했어요."
        "MEMBER_JOINED" -> "시연 차가 그룹에 들어왔어요."
        "MEMBER_OFFLINE" -> "시연 차와 연결이 끊겼어요."
        "PASSENGER_MESSAGE" -> "시연 차에서 온 메시지. 휴게소 들렀다 가요"
        else -> "시연 차 배터리가 12% 남았어요."
    }

    private companion object {
        const val EXTRA_ACTION = "action"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
