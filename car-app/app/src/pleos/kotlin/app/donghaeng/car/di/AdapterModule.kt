package app.donghaeng.car.di

import android.content.Context
import app.donghaeng.car.adapter.location.FakeLocationAdapter
import app.donghaeng.car.adapter.location.LocationAdapter
import app.donghaeng.car.adapter.navi.FakeNaviAdapter
import app.donghaeng.car.adapter.navi.NaviAdapter
import app.donghaeng.car.adapter.sdk.PleosSdkProbe
import app.donghaeng.car.adapter.sdk.SdkProbe
import app.donghaeng.car.adapter.vehicle.FakeVehicleAdapter
import app.donghaeng.car.adapter.vehicle.VehicleAdapter
import app.donghaeng.car.adapter.voice.AndroidTtsVoiceAdapter
import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.core.common.AlwaysParkedDrivingStateMonitor
import app.donghaeng.car.core.common.DrivingStateMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * pleos flavor 어댑터 바인딩.
 *
 * T04 시점에는 실 SDK 어댑터가 아직 없어 Fake 를 바인딩하고, [PleosSdkProbe] 로 SDK 존재만 확인한다.
 * 교체 예정:
 * - T11/T22 `PleosVehicleAdapter` (CarInfo → Fuel·EvBattery·Brake·TurnSignal·Safety + Car API 속도/기어)
 * - T20     `PleosNaviAdapter`
 * - T21     `CompositeLocationAdapter` (Fused → Navi → Android → Fake)
 * - T29     `CarUxDrivingStateMonitor`
 */
@Module
@InstallIn(SingletonComponent::class)
object AdapterModule {

    @Provides
    @Singleton
    fun provideVehicleAdapter(): VehicleAdapter = FakeVehicleAdapter()   // TODO(T11/T22): PleosVehicleAdapter

    @Provides
    @Singleton
    fun provideNaviAdapter(): NaviAdapter = FakeNaviAdapter()            // TODO(T20): PleosNaviAdapter

    @Provides
    @Singleton
    fun provideLocationAdapter(): LocationAdapter = FakeLocationAdapter() // TODO(T21): CompositeLocationAdapter

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
