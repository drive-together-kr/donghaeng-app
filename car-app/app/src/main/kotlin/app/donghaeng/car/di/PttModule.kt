package app.donghaeng.car.di

import android.content.Context
import android.os.SystemClock
import app.donghaeng.car.adapter.audio.AndroidVoiceClipPlayer
import app.donghaeng.car.adapter.audio.AndroidVoiceRecorder
import app.donghaeng.car.adapter.audio.AudioMime
import app.donghaeng.car.adapter.audio.VoiceClipPlayer
import app.donghaeng.car.adapter.audio.VoiceRecorder
import app.donghaeng.car.adapter.speech.AndroidSpeechYesNoRecognizer
import app.donghaeng.car.adapter.speech.PlatformRecognizers
import app.donghaeng.car.adapter.speech.RecognizerChain
import app.donghaeng.car.adapter.speech.VoskYesNoRecognizer
import app.donghaeng.car.adapter.trigger.MediaSessionVoiceTrigger
import app.donghaeng.car.adapter.trigger.VoiceTriggerAdapter
import app.donghaeng.car.adapter.voice.VoiceAdapter
import app.donghaeng.car.core.common.MicPermission
import app.donghaeng.car.core.network.ApiResult
import app.donghaeng.car.core.network.VoiceApi
import app.donghaeng.car.core.network.VoiceMessageClient
import app.donghaeng.car.domain.ptt.FileUnsentVoiceStore
import app.donghaeng.car.domain.ptt.PttController
import app.donghaeng.car.domain.ptt.PttRuntime
import app.donghaeng.car.domain.ptt.UnsentVoiceStore
import app.donghaeng.car.domain.ptt.PttSettings
import app.donghaeng.car.domain.ptt.SendOutcome
import app.donghaeng.car.domain.ptt.SharedPrefsPttSettingsStore
import app.donghaeng.car.domain.ptt.VoicePlayback
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.car.domain.time.ClockSync
import app.donghaeng.car.domain.voice.VoiceAnnouncer
import app.donghaeng.car.surface.PttUiMapper
import app.donghaeng.car.surface.SurfaceState
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import retrofit2.create
import java.io.File
import javax.inject.Singleton

/**
 * F16 무전(녹음형 PTT) 바인딩 — docs/05 § 무전.
 *
 * 트리거([MediaSessionVoiceTrigger]) → [PttController](녹음 [AndroidVoiceRecorder] → '네/아니' [RecognizerChain] → 업로드 [VoiceMessageClient]) /
 * 수신 [VoicePlayback](내려받기 → [AndroidVoiceClipPlayer]) → [PttRuntime] 이 묶어 `SurfaceState.ptt` 로 내보낸다. DongHaengApp 이 [PttRuntime.start].
 * 인식 체인 순서: flavor 플랫폼 단계([PlatformRecognizers] — pleos: Pleos STT 선택적) → Android SpeechRecognizer → Vosk(모델이 `files/vosk-ko-small` 에 있을 때만).
 */
@Module
@InstallIn(SingletonComponent::class)
object PttModule {

    @Provides
    @Singleton
    fun providePttSettings(@ApplicationContext context: Context): PttSettings = PttSettings(SharedPrefsPttSettingsStore(context))

    @Provides
    @Singleton
    fun provideMicPermission(@ApplicationContext context: Context): MicPermission = MicPermission.android(context)

    /** 기본(인증) Retrofit — 차량 JWT·401 갱신(T33)이 따라온다 */
    @Provides
    @Singleton
    fun provideVoiceApi(retrofit: Retrofit): VoiceApi = retrofit.create()

    @Provides
    @Singleton
    fun provideVoiceMessageClient(api: VoiceApi): VoiceMessageClient = VoiceMessageClient(api)

    @Provides
    @Singleton
    fun provideVoiceRecorder(@ApplicationContext context: Context): VoiceRecorder = AndroidVoiceRecorder(context)

    @Provides
    @Singleton
    fun provideVoiceClipPlayer(@ApplicationContext context: Context): VoiceClipPlayer = AndroidVoiceClipPlayer(context)

    @Provides
    @Singleton
    fun provideRecognizerChain(@ApplicationContext context: Context, platform: PlatformRecognizers, mic: MicPermission): RecognizerChain =
        RecognizerChain(
            stages = platform.create() +
                AndroidSpeechYesNoRecognizer(context) { mic.granted.value } +
                VoskYesNoRecognizer(File(context.filesDir, VoskYesNoRecognizer.MODEL_DIR_NAME)) { mic.granted.value },
            clock = SystemClock::elapsedRealtime,
        )

    @Provides
    @Singleton
    fun provideVoiceTrigger(@ApplicationContext context: Context, settings: PttSettings): VoiceTriggerAdapter = MediaSessionVoiceTrigger(context, settings)

    /** '보내지 않은 무전' 저장소 — 앱 내부 저장소 `files/ptt_unsent/`(재시작·프로세스 종료 뒤에도 남는다, 그룹이 끝나면 지운다) */
    @Provides
    @Singleton
    fun provideUnsentVoiceStore(@ApplicationContext context: Context): UnsentVoiceStore =
        FileUnsentVoiceStore(File(context.filesDir, FileUnsentVoiceStore.DIR_NAME))

    @Provides
    @Singleton
    fun providePttController(
        store: UnsentVoiceStore,
        recorder: VoiceRecorder,
        chain: RecognizerChain,
        voice: VoiceAdapter,
        client: VoiceMessageClient,
        sessionManager: MqttSessionManager,
        mic: MicPermission,
        surfaceState: SurfaceState,
    ): PttController = PttController(
        recorder = recorder,
        listener = chain,
        voice = voice,
        sender = { groupId, clip ->
            when (val r = client.upload(groupId, clip.file, clip.mime, clip.durationMs)) {
                is ApiResult.Success -> SendOutcome.Sent(r.value.voiceId)
                is ApiResult.Failure -> SendOutcome.Failed(r.summary)
            }
        },
        groupId = { sessionManager.session.value?.groupId },
        recipients = {
            val me = sessionManager.myVehicleId
            sessionManager.groupState.value?.members?.count { it.vehicleId != me }
        },
        micGranted = { mic.refresh() },
        // '보내지 않은 무전' 다시 보내기·지우기는 정차 중에만
        isDriving = { surfaceState.isDriving.value },
        clock = SystemClock::elapsedRealtime,
        store = store,
    )

    @Provides
    @Singleton
    fun provideVoicePlayback(
        @ApplicationContext context: Context,
        sessionManager: MqttSessionManager,
        clockSync: ClockSync,
        client: VoiceMessageClient,
        player: VoiceClipPlayer,
        voice: VoiceAdapter,
        announcer: VoiceAnnouncer,
    ): VoicePlayback {
        val inDir = File(context.cacheDir, "ptt/in")
        return VoicePlayback(
            events = sessionManager.groupEventsReceived,
            myVehicleId = { sessionManager.myVehicleId },
            groupState = { sessionManager.groupState.value },
            session = sessionManager.session,
            // 60초 신선도는 서버 시계 기준(T31) — 재연결 뒤 밀려 온 옛 무전을 자동 재생하지 않게
            serverNow = { clockSync.serverNow() },
            fetcher = { groupId, voiceId, mime ->
                val dest = File(inDir, "${safeName(voiceId)}.${AudioMime.extensionOf(mime)}")
                (client.download(groupId, voiceId, dest) as? ApiResult.Success)?.value
            },
            player = player,
            voice = voice,
            announceEnabled = { announcer.state.value.enabled },
            cacheDir = inDir,
        )
    }

    @Provides
    @Singleton
    fun providePttRuntime(
        trigger: VoiceTriggerAdapter,
        controller: PttController,
        playback: VoicePlayback,
        sessionManager: MqttSessionManager,
        announcer: VoiceAnnouncer,
        surfaceState: SurfaceState,
    ): PttRuntime = PttRuntime(
        trigger = trigger,
        controller = controller,
        playback = playback,
        session = sessionManager.session,
        holdAnnouncer = announcer::setHeld,
        publish = { c, p, unsent -> surfaceState.ptt.value = PttUiMapper.map(c, p, unsent.size) },
    )

    /** 서버 voiceId → 파일 이름(영숫자·`_`·`-` 만) */
    private fun safeName(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64).ifEmpty { "voice" }
}
