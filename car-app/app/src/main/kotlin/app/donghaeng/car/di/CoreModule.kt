package app.donghaeng.car.di

import android.content.Context
import app.donghaeng.car.core.common.EncryptedTokenStore
import app.donghaeng.car.core.common.InstallationVehicleIdFallback
import app.donghaeng.car.core.common.TokenStore
import app.donghaeng.car.core.common.VehicleIdFallback
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
}
