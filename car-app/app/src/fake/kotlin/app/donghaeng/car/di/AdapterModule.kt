package app.donghaeng.car.di

import android.content.Context
import app.donghaeng.car.adapter.location.AndroidLocationSource
import app.donghaeng.car.adapter.location.CompositeLocationAdapter
import app.donghaeng.car.adapter.location.FakeLocationAdapter
import app.donghaeng.car.adapter.location.LocationAdapter
import app.donghaeng.car.adapter.location.NaviLocationSource
import app.donghaeng.car.adapter.navi.FakeNaviAdapter
import app.donghaeng.car.adapter.navi.NaviAdapter
import app.donghaeng.car.adapter.sdk.NoSdkProbe
import app.donghaeng.car.adapter.sdk.SdkProbe
import app.donghaeng.car.adapter.vehicle.FakeVehicleAdapter
import app.donghaeng.car.adapter.vehicle.VehicleAdapter
import app.donghaeng.car.adapter.voice.AndroidTtsVoiceAdapter
import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.core.common.AlwaysParkedDrivingStateMonitor
import app.donghaeng.car.core.common.DemoPrefs
import app.donghaeng.car.core.common.DrivingStateMonitor
import app.donghaeng.car.domain.navi.NaviEventMonitor
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
    fun provideNaviAdapter(@ApplicationContext context: Context): NaviAdapter {
        // T30: 시연 역할(DemoPrefs, 디버그 리시버 `gpx follow|lead` 로 바꾸고 재시작). 후속차는 목적지가 생길 때까지 출발하지 않는다.
        val follow = DemoPrefs.gpxRole(context) == DemoPrefs.ROLE_FOLLOW
        return if (follow) FakeNaviAdapter.fromAsset(context, FakeNaviAdapter.ASSET_FOLLOW, destination = null, holdUntilRouted = true)
        else FakeNaviAdapter.fromAsset(context, FakeNaviAdapter.ASSET_LEAD)
    }

    @Provides
    @Singleton
    fun provideLocationAdapter(@ApplicationContext context: Context, naviMonitor: NaviEventMonitor): LocationAdapter =
        CompositeLocationAdapter(listOf(NaviLocationSource(naviMonitor), AndroidLocationSource(context), FakeLocationAdapter()))   // Navi = FakeNaviAdapter GPX 재생

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
