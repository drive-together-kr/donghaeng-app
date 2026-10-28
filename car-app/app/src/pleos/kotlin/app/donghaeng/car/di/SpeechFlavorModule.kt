package app.donghaeng.car.di

import android.content.Context
import app.donghaeng.car.adapter.speech.PlatformRecognizers
import app.donghaeng.car.adapter.speech.PleosSttYesNoRecognizer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** F16 무전 '네/아니' 체인 맨 앞 — pleos flavor: Pleos SpeechToText SDK(선택적, 쓸 수 없으면 체인이 건너뛴다 — CLAUDE.md 규칙 5) */
@Module
@InstallIn(SingletonComponent::class)
object SpeechFlavorModule {

    @Provides
    @Singleton
    fun providePlatformRecognizers(@ApplicationContext context: Context): PlatformRecognizers =
        PlatformRecognizers { listOf(PleosSttYesNoRecognizer(context)) }
}
