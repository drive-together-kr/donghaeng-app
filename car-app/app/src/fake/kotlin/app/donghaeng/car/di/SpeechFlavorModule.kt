package app.donghaeng.car.di

import app.donghaeng.car.adapter.speech.PlatformRecognizers
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** F16 무전 '네/아니' 체인 맨 앞 — fake flavor: Pleos SDK 없음(체인 = Android SpeechRecognizer → Vosk → 카운트다운) */
@Module
@InstallIn(SingletonComponent::class)
object SpeechFlavorModule {

    @Provides
    @Singleton
    fun providePlatformRecognizers(): PlatformRecognizers = PlatformRecognizers { emptyList() }
}
