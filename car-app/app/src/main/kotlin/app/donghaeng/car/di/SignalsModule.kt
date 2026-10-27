package app.donghaeng.car.di

import app.donghaeng.car.adapter.vehicle.VehicleAdapter
import app.donghaeng.car.domain.vehicle.VehicleSignals
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
}
