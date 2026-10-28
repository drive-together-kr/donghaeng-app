package app.donghaeng.car.adapter.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import app.donghaeng.car.core.common.DhLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * `MediaRecorder` 무전 녹음 (F16) — AAC-LC MPEG-4(m4a) 16 kHz 모노 24 kbps(3초 ≈ 12 KB, 15초 ≈ 45 KB — 서버 상한 512 KB 의 10분의 1).
 * 2026-10-28 Pleos AVD 실측(F16Probes `rec`): 같은 설정으로 파일이 만들어지고, `hw.audioInput=no` 라 진폭은 0(무음).
 *
 * - 오디오 포커스: 녹음 동안 `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`(USAGE_ASSISTANT) — 순정 내비 안내 음성이 녹음에 섞이지 않게. stop/cancel 에서 반환.
 *   포커스를 못 받아도 녹음은 한다(로그만) — 무전을 못 보내는 것보다 안내 음성이 섞이는 편이 낫다.
 * - [currentLevel]: `maxAmplitude`(지난 호출 이후 최대, 0..32767) → 제곱근 곡선 0..1(작은 소리도 막대가 보이게).
 * - [injectNext]: 다음 녹음 한 번은 마이크를 열지 않고 그 파일을 결과로 쓴다(길이 = WAV 헤더 또는 MediaMetadataRetriever). 진폭은 흉내만 낸다.
 * - 결과 파일은 `cache/ptt/out/` — 보냈거나 버린 파일은 호출 쪽(PttController)이 지운다.
 */
class AndroidVoiceRecorder(
    context: Context,
    private val outDir: File = File(context.applicationContext.cacheDir, "ptt/out"),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : VoiceRecorder {

    private val appContext = context.applicationContext
    private val audioManager: AudioManager? = appContext.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null

    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    private var startedAt = 0L
    private var injecting: File? = null
    @Volatile private var pendingInjection: File? = null
    private var fakeLevelPhase = 0

    override val hasPendingInjection: Boolean get() = pendingInjection != null

    override fun injectNext(file: File) {
        pendingInjection = file
        DhLog.i("ptt: record injection armed bytes=${file.length()} mime=${AudioMime.ofFileName(file.name) ?: "?"}")
    }

    @Synchronized
    override fun start(): RecordStart {
        release(deleteFile = true)
        outDir.mkdirs()
        startedAt = clock()
        val inject = pendingInjection
        if (inject != null) {
            pendingInjection = null
            if (!inject.isFile || AudioMime.ofFileName(inject.name) == null) {
                DhLog.w("ptt: record injection 무시 — 파일 없음 또는 확장자 미지원(wav·m4a·aac)")
            } else {
                injecting = inject
                fakeLevelPhase = 0
                DhLog.i("ptt: recording start inject=true")
                return RecordStart.Started
            }
        }
        val file = File(outDir, "ptt_${System.currentTimeMillis()}.m4a")
        requestFocus()
        val r = try {
            newRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioChannels(1)
                setAudioEncodingBitRate(BIT_RATE)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            DhLog.w("ptt: recording start 실패 ${e.javaClass.simpleName}")
            file.delete()
            abandonFocus()
            return RecordStart.Failed("recorder-${e.javaClass.simpleName}")
        }
        recorder = r
        outFile = file
        DhLog.i("ptt: recording start inject=false rate=$SAMPLE_RATE bitrate=$BIT_RATE")
        return RecordStart.Started
    }

    @Synchronized
    override fun currentLevel(): Float {
        if (injecting != null) {
            // 주입 녹음: 화면 파형이 움직이게 흉내만(0.25..0.75)
            fakeLevelPhase = (fakeLevelPhase + 1) % 8
            return 0.25f + 0.5f * (if (fakeLevelPhase < 4) fakeLevelPhase else 8 - fakeLevelPhase) / 4f
        }
        val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        return sqrt((amp.coerceIn(0, 32_767) / 32_767f).toDouble()).toFloat()
    }

    override suspend fun stop(): RecordStop = withContext(Dispatchers.IO) { stopLocked() }

    @Synchronized
    private fun stopLocked(): RecordStop {
        val elapsed = clock() - startedAt
        val inject = injecting
        if (inject != null) {
            injecting = null
            return copyInjected(inject, elapsed)
        }
        val r = recorder ?: return RecordStop.Failed("not-recording", elapsed)
        val file = outFile
        recorder = null
        outFile = null
        val ok = try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            // 데이터가 거의 없을 때(아주 짧게 누름) MediaRecorder.stop 은 RuntimeException 을 던진다
            DhLog.w("ptt: recording stop 실패 ${e.javaClass.simpleName} elapsed=${elapsed}ms")
            false
        } finally {
            runCatching { r.release() }
            abandonFocus()
        }
        if (!ok || file == null || !file.isFile || file.length() == 0L) {
            file?.delete()
            return RecordStop.Failed("stop-failed", elapsed)
        }
        DhLog.i("ptt: recording end dur=${elapsed}ms bytes=${file.length()} inject=false")
        return RecordStop.Clip(RecordedClip(file, AudioMime.MP4, elapsed, file.length(), injected = false))
    }

    private fun copyInjected(source: File, elapsed: Long): RecordStop {
        val mime = AudioMime.ofFileName(source.name) ?: return RecordStop.Failed("inject-mime", elapsed)
        val target = File(outDir, "ptt_${System.currentTimeMillis()}.${AudioMime.extensionOf(mime)}")
        return try {
            source.copyTo(target, overwrite = true)
            val duration = durationOf(target, mime) ?: elapsed
            DhLog.i("ptt: recording end dur=${duration}ms bytes=${target.length()} inject=true pressed=${elapsed}ms")
            RecordStop.Clip(RecordedClip(target, mime, duration, target.length(), injected = true))
        } catch (e: Exception) {
            target.delete()
            DhLog.w("ptt: record injection 복사 실패 ${e.javaClass.simpleName}")
            RecordStop.Failed("inject-copy", elapsed)
        }
    }

    /** 주입 파일 길이 — WAV 는 헤더, 그 밖은 MediaMetadataRetriever. 모르면 null(누른 시간을 쓴다) */
    private fun durationOf(file: File, mime: String): Long? {
        if (mime == AudioMime.WAV) {
            val head = file.inputStream().use { input -> ByteArray(4096).let { buf -> buf.copyOf(input.read(buf).coerceAtLeast(0)) } }
            return WavInfo.parse(head, file.length())?.durationMs
        }
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    @Synchronized
    override fun cancel() {
        val had = recorder != null || injecting != null
        injecting = null
        release(deleteFile = true)
        if (had) DhLog.i("ptt: recording cancelled")
    }

    private fun release(deleteFile: Boolean) {
        recorder?.let { r ->
            runCatching { r.stop() }
            runCatching { r.release() }
            abandonFocus()
        }
        recorder = null
        if (deleteFile) outFile?.delete()
        outFile = null
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(appContext) else MediaRecorder()

    private fun requestFocus() {
        val am = audioManager ?: return
        try {
            val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .build()
                .also { focusRequest = it }
            val r = am.requestAudioFocus(req)
            if (r != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) DhLog.w("ptt: 녹음 오디오 포커스 거부 result=$r (녹음은 계속)")
        } catch (e: Exception) {
            DhLog.w("ptt: 녹음 오디오 포커스 요청 실패 ${e.javaClass.simpleName}")
        }
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        runCatching { focusRequest?.let { am.abandonAudioFocusRequest(it) } }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val BIT_RATE = 24_000
    }
}
