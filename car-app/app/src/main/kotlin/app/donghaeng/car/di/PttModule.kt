package app.donghaeng.car.di

import android.content.Context
import android.os.Build
import android.os.SystemClock
import app.donghaeng.car.adapter.audio.AndroidVoiceClipPlayer
import app.donghaeng.car.adapter.audio.AndroidVoiceRecorder
import app.donghaeng.car.adapter.audio.AudioMime
import app.donghaeng.car.adapter.audio.RecordedClip
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
import app.donghaeng.car.domain.ptt.PttMicGate
import app.donghaeng.car.domain.ptt.PttRuntime
import app.donghaeng.car.domain.ptt.UnsentVoiceStore
import app.donghaeng.car.domain.ptt.PttSettings
import app.donghaeng.car.domain.ptt.SendOutcome
import app.donghaeng.car.domain.ptt.SharedPrefsPttSettingsStore
import app.donghaeng.car.domain.ptt.VoicePlayback
import app.donghaeng.car.domain.ptt.VoiceSender
import app.donghaeng.car.domain.session.MqttSessionManager
import app.donghaeng.car.domain.time.ClockSync
import app.donghaeng.car.domain.voice.VoiceAnnouncer
import app.donghaeng.car.service.DriveSessionService
import app.donghaeng.car.surface.AppForegroundTracker
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
 * 마이크를 실제로 쓸 수 있는가([micUsable] — B015)는 동행 화면이 보이는가 + 그룹 서비스에 붙은 포그라운드 타입([PttMicGate]).
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

    /** 지금 녹음하면 소리가 담기는가(B015) — API 30+ 에서 백그라운드면 그룹 서비스에 microphone 타입이 있어야 한다 */
    private fun micUsable(tracker: AppForegroundTracker): Boolean =
        PttMicGate.usable(Build.VERSION.SDK_INT, tracker.appVisible.value, DriveSessionService.attachedType.value)

    @Provides
    @Singleton
    fun provideRecognizerChain(
        @ApplicationContext context: Context,
        platform: PlatformRecognizers,
        mic: MicPermission,
        tracker: AppForegroundTracker,
    ): RecognizerChain =
        RecognizerChain(
            stages = platform.create() +
                // error 9 는 마이크가 실제로 열리는 상태에서 났을 때만 영구 실패(B045)
                AndroidSpeechYesNoRecognizer(context, micGranted = { mic.granted.value }, micReady = { micUsable(tracker) }) +
                VoskYesNoRecognizer(File(context.filesDir, VoskYesNoRecognizer.MODEL_DIR_NAME)) { mic.granted.value },
            clock = SystemClock::elapsedRealtime,
        )

    @Provides
    @Singleton
    fun provideVoiceTrigger(@ApplicationContext context: Context, settings: PttSettings, mic: MicPermission): VoiceTriggerAdapter =
        // B046: 마이크 권한이 없으면 핸들 재생/정지 키를 빼앗지 않는다(음악 앱 몫). debug 는 권한 없이 주입 녹음 + 키로 시연(tools/demo/ptt.py)하므로 늘 켤 수 있게
        MediaSessionVoiceTrigger(
            context, settings,
            micAvailable = if (app.donghaeng.car.BuildConfig.DEBUG) kotlinx.coroutines.flow.MutableStateFlow(true) else mic.granted,
        )

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
        announcer: VoiceAnnouncer,
        tracker: AppForegroundTracker,
    ): PttController = PttController(
        recorder = recorder,
        listener = chain,
        voice = voice,
        // 같은 녹음이면 첫 전송·다시 보내기가 같은 Idempotency-Key(B073, docs/03 계약 3)
        sender = object : VoiceSender {
            override suspend fun send(groupId: String, clip: RecordedClip): SendOutcome = send(groupId, clip, null)
            override suspend fun send(groupId: String, clip: RecordedClip, idempotencyKey: String?): SendOutcome =
                when (val r = client.upload(groupId, clip.file, clip.mime, clip.durationMs, idempotencyKey)) {
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
        // 백그라운드 + 포그라운드 서비스에 microphone 없음이면 무음 대신 안내(B015)
        micUsable = { micUsable(tracker) },
        // 녹음을 열기 직전 — 이미 넘긴 NORMAL·LOW 안내를 멈추고 무전 뒤 다시(B013)
        micOpening = { announcer.yieldToMic() },
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
