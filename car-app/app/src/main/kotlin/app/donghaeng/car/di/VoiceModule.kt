package app.donghaeng.car.di

import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.domain.voice.VoiceCueTracker
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/** 음성 체인 주변 바인딩(R19 해결) — 체인 자체(VoiceAdapter)는 flavor 별 AdapterModule 이 후보를 넣어 만든다 */
@Module
@InstallIn(SingletonComponent::class)
object VoiceModule {

    /** 위험 이벤트 강조(docs/05 §5.2)가 보는 음성 경로·지연·eventId 별 시작 신호 */
    @Provides
    @Singleton
    fun provideVoiceCueTracker(voice: VoiceAdapter): VoiceCueTracker =
        VoiceCueTracker(voice, CoroutineScope(SupervisorJob() + Dispatchers.Default))
}
