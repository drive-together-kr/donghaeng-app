package app.donghaeng.car

import android.app.Application
import app.donghaeng.car.adapter.sdk.SdkProbe
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.capability.CapabilityReporter
import app.donghaeng.car.domain.event.VehicleEventEngine
import app.donghaeng.car.domain.voice.VoiceAnnouncer
import app.donghaeng.car.domain.routesync.RouteSyncEngine
import app.donghaeng.car.domain.location.LocationFeed
import app.donghaeng.car.domain.navi.NaviEventMonitor
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.car.domain.telemetry.TelemetryCollector
import app.donghaeng.car.service.DriveSessionController
import app.donghaeng.car.domain.vehicle.VehicleSignals
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * 동행 차량 앱 Application.
 *
 * 시작 로그 한 줄(`car app started …`)은 AVD 기동 확인용이다 — `adb logcat -s DongHaeng`.
 * 이어서 SDK 존재 점검([SdkProbe])과 차량 ID·capability 실측([CapabilityReporter], 태그 `DH_CAP`)을 백그라운드로 한 번 돌린다.
 * MQTT 는 [MqttSessionManager] 가 페어링 상태를 보고 스스로 붙는다(T16) — 그룹 전에도 cmd `GROUP_JOINED` 를 받아야 하므로
 * 페어링만 돼 있으면 프로세스가 사는 동안 연결을 유지한다. 백그라운드 지속은 T23 의 DriveSessionService 가 맡는다(docs/05 §6).
 * 내비 이벤트는 [NaviEventMonitor] 가 한 번만 구독해 진단 화면·후속 소비자에게 나눠 준다(T20). 위치는 [LocationFeed] 가 체인을 한 번만 구독한다(T21).
 */
@HiltAndroidApp
class DongHaengApp : Application() {

    @Inject
    lateinit var sdkProbe: SdkProbe

    @Inject
    lateinit var capabilityReporter: CapabilityReporter

    @Inject
    lateinit var mqttSessionManager: MqttSessionManager

    @Inject
    lateinit var naviEventMonitor: NaviEventMonitor

    @Inject
    lateinit var locationFeed: LocationFeed

    @Inject
    lateinit var vehicleSignals: VehicleSignals

    @Inject
    lateinit var telemetryCollector: TelemetryCollector

    @Inject
    lateinit var driveSessionController: DriveSessionController

    @Inject
    lateinit var vehicleEventEngine: VehicleEventEngine

    @Inject
    lateinit var voiceAnnouncer: VoiceAnnouncer

    @Inject
    lateinit var routeSyncEngine: RouteSyncEngine
    @Inject
    lateinit var clockSync: app.donghaeng.car.domain.time.ClockSync
    @Inject
    lateinit var latencyMeter: app.donghaeng.car.domain.latency.LatencyMeter
    @Inject lateinit var driveSurfaceController: app.donghaeng.car.surface.DriveSurfaceController

    override fun onCreate() {
        super.onCreate()
        // android.util.Log 는 DhLog 에서만 직접 호출한다(규칙 8 마스킹 단일 창구)
        DhLog.i("car app started flavor=${BuildConfig.FLAVOR} base=${BuildConfig.DH_PUBLIC_BASE_URL} mqtt=${BuildConfig.DH_MQTT_PUBLIC_URI}")
        DhLog.i("version=${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) adapter=${BuildConfig.ADAPTER} debug=${BuildConfig.DEBUG}")
        sdkProbe.runOnce()
        capabilityReporter.runOnce()
        mqttSessionManager.start()
        naviEventMonitor.start()
        locationFeed.start()
        vehicleSignals.start()
        telemetryCollector.start()        // T23: 활성 그룹 동안 1Hz 발행
        driveSessionController.start()    // T23: 그룹 활성 ↔ 포그라운드 서비스
        vehicleEventEngine.start()        // T26: 활성 그룹 동안 차량 이벤트 판정·발행
        voiceAnnouncer.start()            // T27: 그룹 이벤트 음성 안내
        routeSyncEngine.start()           // T28: 선두 경로 동기화
        clockSync.start()                 // T31: 서버 시각 동기화(5분)
        latencyMeter.start()              // T31: 그룹 EVENT/STATE 지연 측정
        driveSurfaceController.start()    // 주행 중 표면: 플로팅 패널·알림 카드(docs/05 §5.2)
    }
}
