package app.donghaeng.car.di

import android.app.Application
import android.content.Context
import android.os.SystemClock
import app.donghaeng.car.adapter.navi.NaviAdapter
import app.donghaeng.car.core.common.DrivingStateMonitor
import app.donghaeng.car.core.network.mqtt.MqttGateway
import app.donghaeng.car.core.network.mqtt.MqttState
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.car.domain.time.ClockSync
import app.donghaeng.car.domain.voice.VoiceCueTracker
import app.donghaeng.car.surface.AndroidCardPoster
import app.donghaeng.car.surface.AppForegroundTracker
import app.donghaeng.car.surface.DriveSurfaceController
import app.donghaeng.car.surface.EventCardNotifier
import app.donghaeng.car.surface.OverlayPermissionMonitor
import app.donghaeng.car.surface.PanelPositionStore
import app.donghaeng.car.surface.SharedPrefsPanelPositionStore
import app.donghaeng.car.surface.SurfaceFeed
import app.donghaeng.car.surface.SurfaceState
import app.donghaeng.car.surface.TurnProbe
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

/** 주행 중 표면(플로팅 패널·알림 카드) 바인딩 — docs/05 §5.2 */
@Module
@InstallIn(SingletonComponent::class)
object SurfaceModule {

    @Provides
    @Singleton
    fun provideOverlayPermissionMonitor(@ApplicationContext context: Context, sessionManager: MqttSessionManager): OverlayPermissionMonitor =
        // 5초 주기 확인은 그룹이 있는 동안만(표면 OFF 면 쓸 데가 없다) — 시작·포그라운드 복귀 때의 확인은 항상
        OverlayPermissionMonitor(check = OverlayPermissionMonitor.androidCheck(context), active = sessionManager.session.map { it != null })

    @Provides
    @Singleton
    fun provideAppForegroundTracker(): AppForegroundTracker = AppForegroundTracker()

    @Provides
    @Singleton
    fun providePanelPositionStore(@ApplicationContext context: Context): PanelPositionStore = SharedPrefsPanelPositionStore(context)

    @Provides
    @Singleton
    fun provideSurfaceState(
        sessionManager: MqttSessionManager,
        clockSync: ClockSync,
        mqtt: MqttGateway,
        driving: DrivingStateMonitor,
        overlay: OverlayPermissionMonitor,
        tracker: AppForegroundTracker,
        positions: PanelPositionStore,
        voiceCues: VoiceCueTracker,
    ): SurfaceState = SurfaceState(
        session = sessionManager.session,
        groupState = sessionManager.groupState,
        events = sessionManager.groupEventsReceived,
        myVehicleId = { sessionManager.myVehicleId },
        connected = mqtt.status.map { it.state == MqttState.CONNECTED },
        driving = driving.isDriving(),
        canDrawOverlays = overlay.canDraw,
        appVisible = tracker.appVisible,
        positions = positions,
        // 소식 줄·대시보드 띠의 신선도(60 s)·"N초 전"은 서버 시계 기준(T31) — 재접속 때 밀려 온 옛 이벤트가 '방금'으로 보이지 않게
        serverClock = { clockSync.serverNow() },
        // 연속 주행은 단조 시계로 잰다 — 부팅 뒤 시각 보정으로 벽시계가 뛰어도 주행 시간이 부풀거나 사라지지 않게
        driveClock = { android.os.SystemClock.elapsedRealtime() },
        // 위험 이벤트(급제동·에어백) 강조: 음성 시작이 3초를 넘기면 소식 줄을 빨간 강조 + 깜빡임으로 먼저(docs/05 §5.2)
        voiceCues = voiceCues.state,
    )

    @Provides
    fun provideSurfaceFeed(state: SurfaceState): SurfaceFeed = state

    /** 카드 TBT 게이트의 '다음 회전' — NaviHelper getTBTInfo(pleos) / GPX 가상 회전(fake). 디버그 `tbt` 액션이 덮어쓴다 */
    @Provides
    @Singleton
    fun provideTurnProbe(navi: NaviAdapter): TurnProbe = TurnProbe(lookup = { timeoutMs -> navi.nextTurn(timeoutMs) }, clock = SystemClock::elapsedRealtime)

    /** 신선도(60 s)는 서버 시계 기준(T31 ClockSync), 빈도 제한·미룬 시간은 단조 시계 */
    @Provides
    @Singleton
    fun provideEventCardNotifier(
        @ApplicationContext context: Context,
        sessionManager: MqttSessionManager,
        clockSync: ClockSync,
        state: SurfaceState,
        turns: TurnProbe,
    ): EventCardNotifier = EventCardNotifier(
        events = sessionManager.groupEventsReceived,
        myVehicleId = { sessionManager.myVehicleId },
        serverNow = { clockSync.serverNow() },
        mode = state.mode,
        groupName = { sessionManager.session.value?.name },
        poster = AndroidCardPoster(context),
        nextTurn = { turns.read() },
        clock = SystemClock::elapsedRealtime,
        groupState = { sessionManager.groupState.value },
    )

    @Provides
    @Singleton
    fun provideDriveSurfaceController(
        @ApplicationContext context: Context,
        state: SurfaceState,
        overlay: OverlayPermissionMonitor,
        tracker: AppForegroundTracker,
        cards: EventCardNotifier,
    ): DriveSurfaceController = DriveSurfaceController(context as Application, state, overlay, tracker, cards)
}
