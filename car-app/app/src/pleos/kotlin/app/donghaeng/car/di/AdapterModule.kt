package app.donghaeng.car.di

import android.content.Context
import app.donghaeng.car.adapter.location.AndroidLocationSource
import app.donghaeng.car.adapter.location.CompositeLocationAdapter
import app.donghaeng.car.adapter.location.FakeLocationAdapter
import app.donghaeng.car.adapter.location.LocationAdapter
import app.donghaeng.car.adapter.location.NaviLocationSource
import app.donghaeng.car.adapter.location.PleosFusedLocationAdapter
import app.donghaeng.car.adapter.navi.NaviAdapter
import app.donghaeng.car.adapter.navi.PleosNaviAdapter
import app.donghaeng.car.adapter.sdk.PleosSdkProbe
import app.donghaeng.car.adapter.uxr.CarUxDrivingStateMonitor
import app.donghaeng.car.adapter.sdk.SdkProbe
import app.donghaeng.car.adapter.vehicle.PleosVehicleAdapter
import app.donghaeng.car.adapter.vehicle.VehicleAdapter
import app.donghaeng.car.adapter.voice.AndroidTtsVoiceAdapter
import app.donghaeng.car.adapter.voice.ChainedVoiceAdapter
import app.donghaeng.car.adapter.voice.PleosTtsVoiceAdapter
import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.core.common.DrivingStateMonitor
import app.donghaeng.car.domain.navi.NaviEventMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * pleos flavor 어댑터 바인딩.
 *
 * - T11/T22 `PleosVehicleAdapter`: CarInfo 차량 ID + checkXxxCapabilities + Fuel·EvBattery·Brake·TurnSignal·Airbag 구독, 속도/기어는 Car API(CarApiSignals).
 * - T20 `PleosNaviAdapter`: NaviHelper 콜백 → NaviEvent. 초기화 실패 시 이벤트 없음(크래시 없음).
 * - T21 `CompositeLocationAdapter`: Fused(권한+isSupported 일 때만) → Navi → Android → Fake.
 * - T29 `CarUxDrivingStateMonitor`: CarUxRestrictionsManager → 주행 중 입력 제한.
 * - R19 음성 체인 `ChainedVoiceAdapter`: `AndroidTtsVoiceAdapter` → `PleosTtsVoiceAdapter`(ON_DEVICE, ko_KR) → 화면 표시만.
 */
@Module
@InstallIn(SingletonComponent::class)
object AdapterModule {

    @Provides
    @Singleton
    fun provideVehicleAdapter(@ApplicationContext context: Context): VehicleAdapter =
        PleosVehicleAdapter(context)

    @Provides
    @Singleton
    fun provideNaviAdapter(@ApplicationContext context: Context): NaviAdapter = PleosNaviAdapter(context)

    @Provides
    @Singleton
    fun provideLocationAdapter(@ApplicationContext context: Context, naviMonitor: NaviEventMonitor): LocationAdapter {
        // 시연(Pleos AVD): 순정 내비 위치가 기본값에 고정되므로(docs/10 R17) adb emu geo fix 를 쓰려면 Android 소스를 먼저 — DemoPrefs.locationOrder
        val order = app.donghaeng.car.core.common.DemoPrefs.locationOrder(context)
        val sources = if (order == app.donghaeng.car.core.common.DemoPrefs.ORDER_ANDROID_FIRST) {
            listOf(AndroidLocationSource(context), NaviLocationSource(naviMonitor), FakeLocationAdapter())
        } else {
            listOf(PleosFusedLocationAdapter(context), NaviLocationSource(naviMonitor), AndroidLocationSource(context), FakeLocationAdapter())
        }
        app.donghaeng.car.core.common.DhLog.i("location: 소스 순서 $order")
        return CompositeLocationAdapter(sources)
    }

    /** 음성 체인(R19 해결): Android TTS(한국어 엔진·ko_KR) → Pleos TTS SDK ON_DEVICE → 화면 표시만 — 둘을 나란히 초기화하고 체인이 고른다 */
    @Provides
    @Singleton
    fun provideVoiceAdapter(@ApplicationContext context: Context): VoiceAdapter =
        ChainedVoiceAdapter.create(listOf(AndroidTtsVoiceAdapter(context), PleosTtsVoiceAdapter(context)))

    @Provides
    @Singleton
    fun provideDrivingStateMonitor(@ApplicationContext context: Context): DrivingStateMonitor = CarUxDrivingStateMonitor(context)

    @Provides
    @Singleton
    fun provideSdkProbe(probe: PleosSdkProbe): SdkProbe = probe
}
