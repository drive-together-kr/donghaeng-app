package app.donghaeng.car.di

import android.content.Context
import app.donghaeng.car.adapter.location.FakeLocationAdapter
import app.donghaeng.car.adapter.location.LocationAdapter
import app.donghaeng.car.adapter.navi.FakeNaviAdapter
import app.donghaeng.car.adapter.navi.NaviAdapter
import app.donghaeng.car.adapter.sdk.NoSdkProbe
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
 * fake flavor 어댑터 바인딩 — 전부 Fake. 일반 폰·태블릿 AVD 에서도 실행된다.
 * 음성만은 Android TTS 가 어디에나 있으므로 실제 구현을 쓴다(미준비 시 로그 폴백).
 */
@Module
@InstallIn(SingletonComponent::class)
object AdapterModule {

    @Provides
    @Singleton
    fun provideVehicleAdapter(): VehicleAdapter = FakeVehicleAdapter()

    @Provides
    @Singleton
    fun provideNaviAdapter(): NaviAdapter = FakeNaviAdapter()

    @Provides
    @Singleton
    fun provideLocationAdapter(): LocationAdapter = FakeLocationAdapter()

    @Provides
    @Singleton
    fun provideVoiceAdapter(@ApplicationContext context: Context): VoiceAdapter = AndroidTtsVoiceAdapter(context)

    @Provides
    @Singleton
    fun provideDrivingStateMonitor(): DrivingStateMonitor = AlwaysParkedDrivingStateMonitor()

    @Provides
    @Singleton
    fun provideSdkProbe(probe: NoSdkProbe): SdkProbe = probe
}
