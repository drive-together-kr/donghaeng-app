package app.donghaeng.car.di

import android.content.Context
import app.donghaeng.car.core.common.EncryptedTokenStore
import app.donghaeng.car.core.common.InstallationVehicleIdFallback
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.VehicleIdFallback
import app.donghaeng.car.core.network.ServerConfig
import app.donghaeng.car.core.network.SharedPrefsServerOverrideStorage
import app.donghaeng.car.core.network.mqtt.MqttTransportMemory
import app.donghaeng.car.core.network.mqtt.SharedPrefsMqttTransportMemory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** flavor 와 무관한 공용 바인딩 (main 소스셋). 어댑터는 flavor 별 `AdapterModule`, 네트워크는 `NetworkModule`. */
@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    fun provideTokenStore(@ApplicationContext context: Context): TokenStore = EncryptedTokenStore(context)

    @Provides
    @Singleton
    fun provideVehicleIdFallback(@ApplicationContext context: Context): VehicleIdFallback = InstallationVehicleIdFallback(context)

    /** T32 서버 주소: 숨은 메뉴 사용자 지정(SharedPreferences `server_config`) ?: 빌드 기본값 */
    @Provides
    @Singleton
    fun provideServerConfig(@ApplicationContext context: Context): ServerConfig =
        ServerConfig(ServerConfig.BUILD_DEFAULT_BASE_URL, ServerConfig.BUILD_DEFAULT_MQTT_URI, SharedPrefsServerOverrideStorage(context))

    /** T32 마지막으로 붙은 MQTT 전송(tls/wss) 기억 — 다음 시작에 먼저 시도 */
    @Provides
    @Singleton
    fun provideMqttTransportMemory(@ApplicationContext context: Context): MqttTransportMemory = SharedPrefsMqttTransportMemory(context)
}
