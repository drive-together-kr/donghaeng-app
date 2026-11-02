package app.donghaeng.car.di

import android.content.Context
import app.donghaeng.car.core.network.GroupApi
import app.donghaeng.car.core.network.VehicleSelfApi
import app.donghaeng.car.core.network.mqtt.HiveMqttGateway
import app.donghaeng.car.core.network.mqtt.MqttGateway
import app.donghaeng.car.domain.group.RestGroupMembershipSource
import app.donghaeng.car.domain.session.DataStoreGroupSessionStore
import app.donghaeng.car.domain.session.GroupMembershipSource
import app.donghaeng.car.domain.session.GroupSessionStore
import app.donghaeng.car.domain.time.ClockSync
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** T16: MQTT 게이트웨이(HiveMQ)와 활성 그룹 저장소(DataStore). 테스트는 InMemory 구현을 직접 만든다 */
@Module
@InstallIn(SingletonComponent::class)
object MqttModule {

    @Provides
    @Singleton
    fun provideMqttGateway(impl: HiveMqttGateway, clockSync: ClockSync): MqttGateway = impl.also {
        // A068·계약 4: 발행 봉투 ts = 동기화돼 있으면 서버 시계, 아니면 기기 시계(ClockSync.serverNow 가 오프셋 없으면 기기 시각)
        it.envelopeClock = clockSync::serverNow
    }

    @Provides
    @Singleton
    fun provideGroupSessionStore(@ApplicationContext context: Context): GroupSessionStore = DataStoreGroupSessionStore(context)

    /** A000/A001: 재접속·앱 시작 때 서버와 그룹 소속을 맞추는 조회(차량 JWT REST, docs/03 계약 1) */
    @Provides
    @Singleton
    fun provideGroupMembershipSource(selfApi: VehicleSelfApi, groupApi: GroupApi): GroupMembershipSource =
        RestGroupMembershipSource(selfApi, groupApi)
}
