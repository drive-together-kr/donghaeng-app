package app.donghaeng.car.di

import android.content.Context
import app.donghaeng.car.adapter.vehicle.VehicleAdapter
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.TokenStoreState
import app.donghaeng.car.core.network.mqtt.MqttGateway
import app.donghaeng.car.domain.event.VehicleEventEngine
import app.donghaeng.car.domain.location.LocationFeed
import app.donghaeng.car.domain.location.MyFixSource
import app.donghaeng.car.domain.navi.NaviEventMonitor
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.car.domain.telemetry.TelemetryCollector
import app.donghaeng.car.domain.vehicle.VehicleSignals
import app.donghaeng.car.service.DriveSessionController
import app.donghaeng.protocol.MessageTypes
import kotlinx.serialization.json.JsonObject
import app.donghaeng.protocol.Telemetry
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** flavor 공통 도메인 수집기 바인딩 (T22). 어댑터는 flavor 별 AdapterModule 이 준다. */
@Module
@InstallIn(SingletonComponent::class)
object SignalsModule {

    @Provides
    @Singleton
    fun provideVehicleSignals(adapter: VehicleAdapter): VehicleSignals = VehicleSignals(adapter)

    /** T23: 활성 그룹 동안 1Hz 텔레메트리. 위치 = LocationFeed 최신 Fix, 속도 = 차량 신호 → 위치 계산, ETA = 내비 TripInfo */
    @Provides
    @Singleton
    fun provideTelemetryCollector(
        sessionManager: MqttSessionManager,
        tokenStore: TokenStore,
        mqtt: MqttGateway,
        locationFeed: LocationFeed,
        vehicleSignals: VehicleSignals,
        naviMonitor: NaviEventMonitor,
    ): TelemetryCollector = TelemetryCollector(
        session = sessionManager.session,
        vehicleId = { (tokenStore.state.value as? TokenStoreState.Present)?.credentials?.vehicleId },
        latestFix = { locationFeed.fixes.replayCache.firstOrNull() },
        fallbackSpeedKph = { locationFeed.speedKph.value },
        vehicle = vehicleSignals.state,
        navi = naviMonitor.state,
        publish = { topic, telemetry -> mqtt.publish(topic, MessageTypes.TELEMETRY, telemetry, Telemetry.serializer(), qos = 0) },
    )

    @Provides
    @Singleton
    fun provideMyFixSource(feed: LocationFeed): MyFixSource = feed

    /** T26: 활성 그룹 동안 1Hz 판정 → `dh/v1/vehicle/{vid}/event` QoS 1 */
    @Provides
    @Singleton
    fun provideVehicleEventEngine(
        sessionManager: MqttSessionManager,
        tokenStore: TokenStore,
        mqtt: MqttGateway,
        locationFeed: LocationFeed,
        vehicleSignals: VehicleSignals,
        naviMonitor: NaviEventMonitor,
    ): VehicleEventEngine = VehicleEventEngine(
        session = sessionManager.session,
        vehicleId = { (tokenStore.state.value as? TokenStoreState.Present)?.credentials?.vehicleId },
        latestFix = { locationFeed.fixes.replayCache.firstOrNull() },
        fallbackSpeedKph = { locationFeed.speedKph.value },
        vehicle = vehicleSignals.state,
        naviEvents = naviMonitor.events,
        publish = { topic, type, payload -> mqtt.publish(topic, type, payload, JsonObject.serializer(), qos = 1) },
    )

    @Provides
    @Singleton
    fun provideDriveSessionController(@ApplicationContext context: Context, sessionManager: MqttSessionManager): DriveSessionController =
        DriveSessionController(context, sessionManager.session)
}
