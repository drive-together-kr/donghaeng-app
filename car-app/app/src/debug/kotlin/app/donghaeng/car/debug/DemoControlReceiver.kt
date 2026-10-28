package app.donghaeng.car.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.donghaeng.car.adapter.navi.NaviAdapter
import app.donghaeng.car.adapter.voice.debugTokens
import app.donghaeng.car.core.common.DemoPrefs
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.TextMasking
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.domain.model.Place
import app.donghaeng.car.domain.model.Priority
import app.donghaeng.car.domain.model.toLogString
import app.donghaeng.car.domain.routesync.RouteSyncEngine
import app.donghaeng.car.domain.session.GroupSessionStore
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.car.domain.voice.VoiceAnnouncer
import app.donghaeng.car.domain.session.ReceivedGroupEvent
import app.donghaeng.car.domain.time.ClockSync
import app.donghaeng.car.surface.DriveSurfaceController
import app.donghaeng.car.surface.EventCardNotifier
import app.donghaeng.car.surface.OverlayPermissionMonitor
import app.donghaeng.car.surface.PanelCorner
import app.donghaeng.car.surface.SurfaceState
import app.donghaeng.car.surface.TurnProbe
import app.donghaeng.protocol.EventAudience
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
import java.util.Base64

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
 * | `voice_sample` | | 진단 '예시 안내 듣기'와 같은 문장("아빠 차가 휴게소에 도착했어요.")을 음성 체인으로 — `demo: voice_sample tts=… ttsPath=…` |
 * | `navi_route` | | 그룹 목적지로 `requestRoute` |
 * | `gpx` | `role` = `lead`/`follow` | fake flavor GPX 역할 저장 — **앱 재시작 후 적용** |
 * | `is_me` | `vid`, `tag`(선택, 영숫자·`-` 16자) | 이 차의 vehicleId 인가 — `demo: is_me=true\|false tag=…` 만 남긴다(id 원문 없음). demo.py preflight 가 `GET /me/vehicles` 의 id 를 차마다 물어 차 A/B 를 맞춘다 |
 * | `nickname` | `name_b64`(UTF-8 base64, URL-safe 가능) 또는 `name` | 저장된 내 차 닉네임을 바꾼다(서버 `PATCH /me/vehicles/{id}` 와 같은 값으로 — 그룹이 없어도 홈·그룹 기본 이름이 바로 따라오게). 로그는 `demo: nickname changed=… len=N` |
 * | `status` | | 세션·제안·음성 상태 한 줄 로그 |
 * | `open` | `screen` = home/drive/events/create/settings/diagnostics | 앱을 전면으로 올리고 그 화면으로 이동(MainActivity extra `screen`) |
 * | `surface` | | 주행 중 표면 상태 한 줄 — `demo: surface mode=PANEL panelVisible=true canDraw=true appVisible=false … panelShown=true keeper=starts=1 … cards=posted=… held=<미룬 카드 수> … lastTbt=<마지막 TBT> … tbt=override=… lookups=…` |
 * | `surface_pos` | `corner` = top_end/top_start/bottom_end/bottom_start | 패널 모서리 강제 지정(저장됨) |
 * | `event` | `type`(기본 LOW_ENERGY), `speech`, `source`(`ahead`/`behind` = 스냅샷에서 내 바로 앞/뒤 멤버, 또는 vehicleId. 기본: **급제동이고 `audience` 도 안 주면 `ahead`** — 앞 차가 없으면(내가 선두·위치 모름) 경고만 남기고 주입하지 않는다; 그 밖은 `veh_DEMO` = 명단 밖이라 앞뒤를 몰라 '대상'), `audience`(`veh_1,veh_2` · `me` = 내 차 · `none` = 빈 목록; 없으면 audience 없음 → 급제동은 스냅샷 앞뒤 폴백), `age_s`(봉투 나이 — 61 이상이면 소식 줄·띠·카드에서 빠진다), `voice`(`--ez`, 기본 true) | 그룹 이벤트 한 건을 로컬로 흘려 패널 이벤트 줄·알림 카드·음성을 확인. 수신 대상 판정(`EventAudience`)을 실제 수신과 똑같이 거치고 `forMe=true/false basis=…` 를 남긴다(급제동: 뒤차에서 기본 주입 → forMe=true basis=SNAPSHOT, 선두에서는 `앞 차 없음 → 주입 안 함`, `--es source behind` → 앞차인 나는 forMe=false; 선두에 카드를 억지로 띄워 자리만 볼 때는 `--es audience me`). 카드는 같은 종류·출처 30초에 한 장·8초에 2장까지(`source` 를 바꿔 반복) |
 * | `tbt` | `m`(`--ei`, 다음 회전까지 m, -1 = 해제), `for_s`(`--ei`, 기본 30) | 카드 TBT 게이트가 읽는 다음 회전 거리를 `for_s` 초 동안 덮어쓴다(AVD 순정 내비는 위치가 고정돼 TBT 가 늘 같다). 예(CARDS_ONLY): `--es action tbt --ei m 120 --ei for_s 60` → `--es action event --es type HARD_BRAKE` → `surface: card deferred type=HARD_BRAKE tbt=120m` → `--es action tbt --ei m 800` → 1초 안에 `surface: card released … reason=far` |
 * | `ptt_press` | | F16 무전 버튼 한 번 — 핸들 키·패널 탭과 같은 경로(트리거 DEBUG → 재생 중이면 끊고 → PttController). 녹음 중이면 끝내기, 확인 중이면 취소 |
 * | `ptt_inject_record` | `file`(앱 files 기준 상대 경로 또는 절대 경로, `.wav`·`.m4a`·`.aac`) | 다음 녹음 한 번을 이 파일로 대신(마이크를 열지 않음, 길이 = 파일 길이). `demo: ptt_inject_record armed …` |
 * | `ptt_answer` | `answer` = `yes`/`no`/`none` 또는 `file`(16 kHz 모노 16 bit WAV) | '보낼까요?' 답 주입 — 듣는 중이면 바로 끝내고 카운트다운 중에도 받는다(네 = 보냄, 아니 = 취소, none = 보내지 않은 무전). `file` 은 마이크 대신 그 WAV 를 인식기(Vosk 모델이 있어야)에 흘린다 → `ptt: recognizer=VOSK answer=…` |
 * | `ptt_status` | | 무전 한 줄 — 단계·남은 초·인식기 상태(`recognizers=[PLEOS_STT=… ANDROID_STT=… VOSK=…]`)·누적 수 + 트리거(그룹·설정·다른 앱 미디어·세션·누름 수) + 재생(재생 중·대기·지난 무전 상태별 수) + 설정·마이크·Vosk 모델 + `fgs=`(그룹 서비스에 붙은 포그라운드 타입 `location+microphone` 등 — 백그라운드에서는 바꾸지 않는다, 동행 화면이 앞에 오면 맞춘다) |
 * | `ptt_unsent` | `op` = `resend`/`delete`(선택), `id`(기본 `first`) | '보내지 않은 무전' 목록 한 줄, 선택하면 다시 보내기·지우기(정차 중에만 — 주행 중이면 false) |
 * | `ptt_handle` | `on`(ez) | 설정 '핸들 버튼으로 무전' — 끄면 MediaSession 을 내리고 화면 키도 흘려보낸다 |
 * | `ptt_countdown` | `on`(ez, 기본 true) | 인식기를 모두 건너뛰어 카운트다운 모드("5초 뒤 보낼게요")를 강제(Vosk 모델이 있어도 장면을 찍게). 주입 답은 그대로 받는다 |
 * | `vosk_install` | `dir`(모델 폴더, 기본 = 앱 외부 files `vosk-ko-small`), `remove`(ez) | Vosk 모델을 앱 `files/vosk-ko-small` 로 복사(따로 돌고 끝나면 `demo: vosk_install done files=… bytes=…`) / 지우기. `ptt.py` 는 보통 `run-as` 로 바로 넣는다 |
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
        fun sessionManager(): MqttSessionManager
        fun voiceAdapter(): app.donghaeng.car.adapter.voice.VoiceAdapter
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
    private lateinit var sessionManager: MqttSessionManager
    private lateinit var voiceAdapter: app.donghaeng.car.adapter.voice.VoiceAdapter

    override fun onReceive(context: Context, intent: Intent) {
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java)
        routeSync = deps.routeSync(); voice = deps.voice(); navi = deps.navi(); sessionStore = deps.sessionStore()
        surfaceController = deps.surfaceController(); surfaceState = deps.surfaceState(); surfaceCards = deps.surfaceCards()
        surfaceOverlay = deps.surfaceOverlay(); clockSync = deps.clockSync(); turnProbe = deps.turnProbe(); sessionManager = deps.sessionManager()
        voiceAdapter = deps.voiceAdapter()
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
            "voice_sample" -> {
                // 진단 '예시 안내 듣기'와 같은 문장을 체인으로 — 심사 환경 재현(docs/09 §1.4). release 는 진단 화면 버튼으로
                voice.speakTest(app.donghaeng.car.feature.settings.DiagnosticsViewModel.SAMPLE_SENTENCE, type = "SAMPLE")
                DhLog.i("demo: voice_sample ${voiceAdapter.status.value.debugTokens()}")
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
                // 카드 TBT 게이트 확인용: 다음 회전 거리를 잠시 덮어쓴다(m=-1 해제). 미룬 카드는 1초마다 다시 확인해 다음 회전이 300 m 밖이 되면(·안내 끝) 올라간다 — 300 m 안 값으로 바꾸면 계속 미룬다
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
                // 그룹 이벤트 한 건을 로컬로 흘려 표면(패널 이벤트 줄·알림 카드·음성)을 확인한다 — 서버를 거치지 않는다.
                // 표면·카드·음성이 각자 EventAudience 로 대상 여부를 다시 본다(실제 수신과 같은 경로)
                val type = intent.getStringExtra("type") ?: "LOW_ENERGY"
                val speech = intent.getStringExtra("speech") ?: demoSpeech(type)
                val me = sessionManager.myVehicleId
                val members = sessionManager.groupState.value?.members
                val audienceRaw = intent.getStringExtra("audience")
                // 급제동 기본 출처 = 내 바로 앞 차(명단 밖 veh_DEMO 로 넣으면 선두에도 '앞쪽 급제동'이 떠 고치기 전 impl_02 가 재현된다, docs/10 R18)
                val (source, sourceArg) = when (val pick = DemoEventSource.pick(type, intent.getStringExtra("source"), audienceRaw != null, me, members)) {
                    is DemoEventSource.Pick.Source -> pick.vehicleId to pick.arg
                    is DemoEventSource.Pick.NoNeighbour -> {
                        val side = if (pick.which == DemoEventSource.AHEAD) "앞" else "뒤"
                        DhLog.w(
                            "demo: event type=$type source=${pick.which}${if (pick.defaulted) "(기본)" else ""} — 스냅샷에 내 $side 차가 없음(선두이거나 내 차·상대 위치 모름, members=${members?.size ?: 0}) → 주입 안 함" +
                                if (pick.defaulted) ". 급제동은 출처보다 뒤차만 받는다 — 뒤차에서 주입하거나, 자리만 볼 때는 --es audience me" else "",
                        )
                        return
                    }
                }
                val audience = audienceRaw?.let { raw ->
                    if (raw.trim() == "none") emptyList() else raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { if (it == "me") me ?: it else it }
                }
                val severity = when (type) { "AIRBAG_DEPLOYED" -> Severity.ALERT; "HARD_BRAKE", "LOW_ENERGY", "MEMBER_OFFLINE", "DEVIATION" -> Severity.WARN; else -> Severity.INFO }
                val now = System.currentTimeMillis()
                val e = ReceivedGroupEvent(
                    GroupEvent(eventId = "demo-$now", type = type, sourceVehicleId = source, severity = severity, speech = speech, payload = JsonObject(emptyMap()), audience = audience),
                    envelopeTsMs = clockSync.serverNow() - intent.getIntExtra("age_s", 0) * 1_000L,
                    receivedAtMs = now,
                )
                val verdict = EventAudience.decide(e.event, me, members)
                val noted = surfaceState.noteEvent(e)
                val carded = surfaceCards.onEvent(e)
                if (intent.getBooleanExtra("voice", true)) voice.onEvent(e)
                // 닉네임·좌표 없이: 출처 마스킹, 대상은 수만, 앞뒤 관계는 판정 근거(basis)로만
                DhLog.i(
                    "demo: event type=$type source=${TextMasking.maskVehicleId(source)}${if (sourceArg != source) "($sourceArg)" else ""} " +
                        "audience=${audience?.size ?: "none"} forMe=${verdict.forMe} basis=${verdict.basis} noted=$noted card=$carded mode=${surfaceState.mode.value} len=${speech.length}",
                )
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
                        "autoFollow=${r.autoFollow} routeChangedSent=${r.routeChangedSent} voice=${voice.state.value.enabled} gpx=${DemoPrefs.gpxRole(context)} " +
                        // 음성 체인(R19 — demo.py preflight 가 본다): tts=ready|init|off(ready = 어느 경로든 소리 경로 있음) ttsEngine=… ttsPath=ANDROID_TTS|PLEOS_TTS|DISPLAY_ONLY ttsSelecting=…
                        voiceAdapter.status.value.debugTokens(),
                )
            }
            "is_me" -> {
                // 시연 preflight 가 서버 차량 목록의 id 를 하나씩 물어 이 차가 어느 vehicleId 인지 맞춘다 — 로그에는 참/거짓과 요청 꼬리표만(CLAUDE.md 8)
                val vid = intent.getStringExtra("vid").orEmpty()
                val mine = identityDeps(context).tokenStore().load()?.vehicleId
                val tag = intent.getStringExtra("tag")?.filter { it.isLetterOrDigit() || it == '-' }?.take(16)?.takeIf { it.isNotEmpty() }
                DhLog.i("demo: is_me=${vid.isNotEmpty() && vid == mine}" + (tag?.let { " tag=$it" } ?: ""))
            }
            "nickname" -> {
                // 서버 닉네임을 바꾼 뒤(api.py rename / demo.py preflight) 저장값을 같은 값으로 — 그룹이 있으면 스냅샷이 알아서 맞추지만(MqttSessionManager) 그룹 전에는 알 길이 없다
                val tokens = identityDeps(context).tokenStore()
                val name = demoNickname(intent)?.trim().orEmpty()
                val creds = tokens.load()
                when {
                    creds == null -> DhLog.w("demo: nickname 페어링 없음")
                    name.isEmpty() || name.length > NICKNAME_MAX -> DhLog.w("demo: nickname 길이 ${name.length} — 1~${NICKNAME_MAX}자여야 한다")
                    name == creds.nickname -> DhLog.i("demo: nickname changed=false len=${name.length}")
                    else -> {
                        tokens.save(creds.copy(nickname = name))
                        DhLog.i("demo: nickname changed=true len=${name.length}")
                    }
                }
            }
            // F16 무전 디버그(ptt_press·ptt_inject_record·ptt_answer·ptt_status·ptt_unsent·ptt_handle·ptt_countdown·vosk_install) — DemoPttActions.kt
            in DemoPttActions.ACTIONS -> DemoPttActions.handle(context, action, intent)
            // 네트워크·지연 진단(net_rtt·net_mqtt_rtt·latency_dump·latency_reset, docs/09 §4.2) — DemoNetActions.kt
            in DemoNetActions.ACTIONS -> DemoNetActions.handle(context, action, intent)
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

/** 시연 닉네임 액션(`is_me`·`nickname`) 전용 의존성 — 다른 액션의 [DemoControlReceiver.Deps] 와 따로 둔다 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DemoIdentityDeps {
    fun tokenStore(): TokenStore
}

private fun identityDeps(context: Context): DemoIdentityDeps =
    EntryPointAccessors.fromApplication(context.applicationContext, DemoIdentityDeps::class.java)

/** 서버 닉네임 한도(docs/03 §1.2 — 1~30자)와 같다 */
private const val NICKNAME_MAX = 30

/**
 * `nickname` 액션의 새 닉네임. `adb shell am broadcast --es name 아빠 차` 는 원격 셸이 공백에서 잘라 버리므로 demo.py 는
 * UTF-8 base64(`name_b64`)로 보낸다. 손으로 칠 때는 `--es name` 도 받는다(공백이 있으면 `adb shell "am broadcast … --es name '아빠 차'"` 처럼 셸 명령 전체를 한 번 더 따옴표로).
 */
private fun demoNickname(intent: Intent): String? {
    intent.getStringExtra("name_b64")?.let { b64 ->
        return runCatching { String(Base64.getUrlDecoder().decode(b64.trim().replace('+', '-').replace('/', '_')), Charsets.UTF_8) }.getOrNull()
    }
    return intent.getStringExtra("name")
}
