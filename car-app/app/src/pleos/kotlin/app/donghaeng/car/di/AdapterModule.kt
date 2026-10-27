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
import app.donghaeng.car.adapter.sdk.SdkProbe
import app.donghaeng.car.adapter.vehicle.FakeVehicleAdapter
import app.donghaeng.car.adapter.vehicle.PleosVehicleAdapter
import app.donghaeng.car.adapter.vehicle.VehicleAdapter
import app.donghaeng.car.adapter.voice.AndroidTtsVoiceAdapter
import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.core.common.AlwaysParkedDrivingStateMonitor
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
 * - T11 `PleosVehicleAdapter`: CarInfo 차량 ID + checkXxxCapabilities. 나머지 Flow 는 T22 까지 내부에서 Fake 에 위임.
 * - T20 `PleosNaviAdapter`: NaviHelper 콜백 → NaviEvent. 초기화 실패 시 이벤트 없음(크래시 없음).
 * - T21 `CompositeLocationAdapter`: Fused(권한+isSupported 일 때만) → Navi → Android → Fake.
 * 교체 예정:
 * - T22     `PleosVehicleAdapter` 의 Flow 들(Fuel·EvBattery·Brake·TurnSignal·Safety + Car API 속도/기어)
 * - T29     `CarUxDrivingStateMonitor`
 */
@Module
@InstallIn(SingletonComponent::class)
object AdapterModule {

    @Provides
    @Singleton
    fun provideVehicleAdapter(@ApplicationContext context: Context): VehicleAdapter =
        PleosVehicleAdapter(context, fallback = FakeVehicleAdapter())   // TODO(T22): fallback 제거

    @Provides
    @Singleton
    fun provideNaviAdapter(@ApplicationContext context: Context): NaviAdapter = PleosNaviAdapter(context)

    @Provides
    @Singleton
    fun provideLocationAdapter(@ApplicationContext context: Context, naviMonitor: NaviEventMonitor): LocationAdapter =
        CompositeLocationAdapter(listOf(PleosFusedLocationAdapter(context), NaviLocationSource(naviMonitor), AndroidLocationSource(context), FakeLocationAdapter()))

    @Provides
    @Singleton
    fun provideVoiceAdapter(@ApplicationContext context: Context): VoiceAdapter = AndroidTtsVoiceAdapter(context)

    @Provides
    @Singleton
    fun provideDrivingStateMonitor(): DrivingStateMonitor = AlwaysParkedDrivingStateMonitor() // TODO(T29)

    @Provides
    @Singleton
    fun provideSdkProbe(probe: PleosSdkProbe): SdkProbe = probe
}
