package app.donghaeng.car.di

import android.content.Context
import app.donghaeng.car.core.network.mqtt.HiveMqttGateway
import app.donghaeng.car.core.network.mqtt.MqttGateway
import app.donghaeng.car.domain.session.DataStoreGroupSessionStore
import app.donghaeng.car.domain.session.GroupSessionStore
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
    fun provideMqttGateway(impl: HiveMqttGateway): MqttGateway = impl

    @Provides
    @Singleton
    fun provideGroupSessionStore(@ApplicationContext context: Context): GroupSessionStore = DataStoreGroupSessionStore(context)
}
