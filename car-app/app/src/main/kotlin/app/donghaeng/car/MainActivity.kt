package app.donghaeng.car

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.donghaeng.car.ui.navigation.DhNavHost
import app.donghaeng.car.ui.theme.DongHaengTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 단일 액티비티. 화면 전환은 Navigation Compose(`DhNavHost`)가 담당한다.
 *
 * 인텐트 extra `screen`(home | drive | events | create | settings | diagnostics)이 오면 그 화면으로 이동한다 — 시연 스크립트(`tools/demo`, debug 리시버 `open`)와
 * 알림 탭에서 쓴다. 없으면 홈.
 * B125: 헤드업 카드에서 온 인텐트(`from=card`)는 **주행 중이면** 그룹 주행 화면으로 가지 않고 순정 내비를 앞에 둔다(규칙 6 — 앞 화면은 늘 순정 내비).
 * 정차 중 올린 카드(탭 가능)를 출발 직후 5초 수명 안에 눌러도 여기서 막힌다([CardTapPolicy]).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** F16 무전 ③ — 동행 화면이 앞일 때 핸들 키(SEARCH·HEADSETHOOK·MEDIA_PLAY_PAUSE·MEDIA_PLAY) */
    @javax.inject.Inject
    lateinit var voiceTrigger: app.donghaeng.car.adapter.trigger.VoiceTriggerAdapter

    /** F16: 화면이 앞에 올 때 포그라운드 서비스 타입(microphone·location)을 권한에 맞춘다 */
    @javax.inject.Inject
    lateinit var driveSession: app.donghaeng.car.service.DriveSessionController

    @javax.inject.Inject
    lateinit var sessionManager: app.donghaeng.car.domain.session.MqttSessionManager

    /** B125: 카드 탭 때 지금 주행 중인지(표면 상태의 주행 값) */
    @javax.inject.Inject
    lateinit var surfaceState: app.donghaeng.car.surface.SurfaceState

    private val requestedRoute = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedRoute.value = routeOf(intent)
        setContent {
            DongHaengTheme {
                DhNavHost(requestedRoute = requestedRoute, onRouteConsumed = { requestedRoute.value = null })
            }
        }
    }

    /**
     * F16: 백그라운드에서 시작·재시작된 그룹 서비스(폰 합류·START_STICKY)는 microphone·location 이 빠져 있을 수 있다 — 화면이 앞인 지금 다시 맞춘다.
     * 권한이 '새로 생긴' 순간만 보면 이미 권한이 있던 차는 끝까지 microphone 없이 남는다(순정 내비가 앞일 때 무전 녹음·듣기가 무음).
     */
    override fun onResume() {
        super.onResume()
        driveSession.ensureType(sessionManager.session.value)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedRoute.value = routeOf(intent)
    }

    private fun routeOf(intent: android.content.Intent?): String? {
        val from = intent?.getStringExtra(EXTRA_FROM)
        val driving = runCatching { surfaceState.isDriving.value }.getOrDefault(false)
        if (!CardTapPolicy.opensScreen(from, driving)) {
            // 주행 중 카드 탭 — 동행 화면으로 덮지 않고 순정 내비를 앞으로(실패하면 그대로 둔다)
            app.donghaeng.car.core.common.DhLog.i("card tap while driving → 순정 내비 유지(B125)")
            if (!app.donghaeng.car.surface.NaviLauncher.bringToFront(this, pleosBuild = BuildConfig.ADAPTER == "pleos")) moveTaskToBack(true)
            return null
        }
        return screenRouteOf(intent)
    }

    private fun screenRouteOf(intent: android.content.Intent?): String? = when (intent?.getStringExtra(EXTRA_SCREEN)) {
        "home" -> app.donghaeng.car.ui.navigation.DhRoutes.HOME
        "drive", "group_drive" -> app.donghaeng.car.ui.navigation.DhRoutes.GROUP_DRIVE
        "events" -> app.donghaeng.car.ui.navigation.DhRoutes.EVENTS
        "create", "create_group" -> app.donghaeng.car.ui.navigation.DhRoutes.CREATE_GROUP
        "settings" -> app.donghaeng.car.ui.navigation.DhRoutes.SETTINGS
        "diagnostics", "diag" -> app.donghaeng.car.ui.navigation.DhRoutes.DIAGNOSTICS
        "design" -> app.donghaeng.car.ui.navigation.DhRoutes.DESIGN + "/" + (intent.getStringExtra(EXTRA_VARIANT) ?: "drive_landscape")
        else -> null
    }

    /**
     * F16 무전 ③: 동행 화면이 앞일 때 핸들 키를 무전으로 쓴다(그룹이 있고 '핸들 버튼으로 무전'이 켜져 있을 때만 — 아니면 평소처럼 흘려보낸다).
     * down 1회만 누름, 반복·up 은 소비([app.donghaeng.car.adapter.trigger.PttKeyFilter]). 키 조사 로그는 debug 빌드만.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (BuildConfig.DEBUG && event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            app.donghaeng.car.core.common.DhLog.i("key: ${android.view.KeyEvent.keyCodeToString(event.keyCode)} source=${event.source} device=${event.deviceId}")
        }
        if (voiceTrigger.onActivityKey(event.keyCode, event.action, event.repeatCount, event.eventTime)) return true
        return super.dispatchKeyEvent(event)
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_VARIANT = "variant"

        /** 인텐트 출처 — 헤드업 카드면 [FROM_CARD](B125) */
        const val EXTRA_FROM = "from"
        const val FROM_CARD = "card"
    }
}

/** 카드 탭 인텐트 처리 규칙(B125, 순수 — 단위 테스트): 카드에서 왔고 지금 주행 중이면 화면을 열지 않는다 */
object CardTapPolicy {
    fun opensScreen(from: String?, drivingNow: Boolean): Boolean = !(from == MainActivity.FROM_CARD && drivingNow)
}
