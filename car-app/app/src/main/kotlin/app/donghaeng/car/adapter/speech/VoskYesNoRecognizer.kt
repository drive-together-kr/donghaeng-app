package app.donghaeng.car.adapter.speech

import app.donghaeng.car.adapter.audio.WavInfo
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.ptt.YesNo
import app.donghaeng.car.domain.ptt.YesNoParser
import app.donghaeng.protocol.DhJson
import app.donghaeng.car.domain.ptt.YesNoSegments
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.RandomAccessFile

/**
 * Vosk 오프라인 '네/아니' 단계 (F16 체인 3순위) — `com.alphacephei:vosk-android`(Apache 2.0) + 소형 한국어 모델 `vosk-model-small-ko-0.22`
 * (zip 82 MB, 풀면 253 MB — APK 에 넣지 않는다).
 *
 * - **모델이 [modelDir](앱 `files/vosk-ko-small/`, `am/`·`conf/` 가 있어야 함)에 있을 때만** 쓴다 — `tools/demo` 가 내려받아 넣는다. 없으면 `no-model` 로 건너뛴다.
 * - 단어 문법([YesNoParser.VOSK_GRAMMAR_WORDS] + `[unk]`)으로 어휘를 제한한다 — 소형 모델은 실행 중 문법을 받는다. 모델 어휘에 없는 낱말은 Vosk 가 경고만 남기고 뺀다.
 * - 모델 적재는 수 초 걸려 [prepare](녹음 중)에 한 번 하고 앱이 사는 동안 들고 있는다.
 * - 마이크: `SpeechService`(AudioRecord 16 kHz). 결과 JSON `{"text": "…", "result": [{"conf":…}]}` 의 text 를 [YesNoParser] 로 판정, 낱말 신뢰도 평균을 conf 로.
 * - **판정(B014)** = [YesNoSegments]: 지금까지의 구간 + 부분 결과를 이은 문장 전체로 본다. 아니는 부분 결과에서도 바로 끝, **네는 구간이 끝난 뒤
 *   [YesNoSegments.YES_CONFIRM_MS] 더 들어** 문장이 계속 네일 때만 확정("어… 아니"처럼 '어' 뒤에 잠깐 멈춰도 네로 보내지 않게).
 * - **모델 적재를 기다린 시간만큼 듣기를 줄인다(B064)** — 남은 시간이 [MIN_LISTEN_MS] 보다 짧으면 `model-loading`(이번만 실패)으로 다음 단계·카운트다운에 넘긴다
 *   (체인·컨트롤러의 확인 상한을 넘기지 않게). 로그 `ptt: vosk model-loading budget=…ms`.
 * - [listenWav]: 마이크 대신 WAV PCM 을 직접 `acceptWaveForm` 에 흘린다(디버그 주입 — 16 kHz·모노·16 bit 만).
 * - **취소돼도 마이크를 닫는다**: 확인 중 누름·주입 답(`RecognizerChain` 의 first())·컨트롤러 시간 초과·그룹 변경으로 듣기가 취소되는 일이 흔하다.
 *   정리(`stop`·`shutdown`·`close`)는 `NonCancellable` 로 돌린다 — 취소된 코루틴에서 그냥 `withContext` 를 부르면 블록을 돌기 전에 CancellationException 이 나
 *   AudioRecord·녹음 스레드가 프로세스가 끝날 때까지 남는다(다음 듣기 'mic-busy'·다음 무전 녹음과 마이크 다툼).
 * - JNI(JNA) 라이브러리라 JVM 단위 테스트에서는 이 클래스를 쓰지 않는다(체인은 [FakeYesNoRecognizer] 로 시험).
 */
class VoskYesNoRecognizer(
    private val modelDir: File,
    private val micGranted: () -> Boolean,
) : YesNoRecognizer {

    override val kind: RecognizerKind = RecognizerKind.VOSK

    private val loadLock = Mutex()
    @Volatile private var model: Model? = null
    @Volatile private var loadError: String? = null

    private fun modelPresent(): Boolean = File(modelDir, "am").isDirectory && File(modelDir, "conf").isDirectory

    private fun modelReason(): String? = when {
        !modelPresent() -> "no-model"
        loadError != null -> "model-load ${loadError}"
        else -> null
    }

    override fun unavailableReason(): String? = modelReason() ?: if (!micGranted()) "no-mic-permission" else null

    override fun wavUnavailableReason(): String? = modelReason()

    override suspend fun prepare() {
        loadModel()
    }

    private suspend fun loadModel(): Model? = loadLock.withLock {
        model?.let { return@withLock it }
        if (!modelPresent()) return@withLock null
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            try {
                Model(modelDir.absolutePath).also {
                    model = it
                    DhLog.i("ptt: vosk model loaded ${System.currentTimeMillis() - started}ms")
                }
            } catch (e: Throwable) {
                // IOException(모델 깨짐) 또는 UnsatisfiedLinkError(ABI) — 앱이 사는 동안 다시 시도하지 않는다
                loadError = e.javaClass.simpleName
                DhLog.w("ptt: vosk model load 실패 ${e.javaClass.simpleName}")
                null
            }
        }
    }

    private fun newRecognizer(m: Model): Recognizer =
        Recognizer(m, SAMPLE_RATE, VoskResult.GRAMMAR_JSON).apply { setWords(true) }

    override suspend fun listen(timeoutMs: Long): ListenResult {
        val t0 = System.currentTimeMillis()
        val m = loadModel() ?: return ListenResult.Failed(modelReason() ?: "no-model", permanent = loadError != null)
        // 적재를 여기서 기다렸으면 그만큼 듣기를 줄인다(B064) — 체인의 남은 시간(확인 5초)을 넘지 않게
        val budget = timeoutMs - (System.currentTimeMillis() - t0)
        if (budget < MIN_LISTEN_MS) {
            DhLog.i("ptt: vosk model-loading budget=${budget}ms → 다음 단계")
            return ListenResult.Failed("model-loading", permanent = false)
        }
        val recognizer = try {
            newRecognizer(m)
        } catch (e: Exception) {
            return ListenResult.Failed("recognizer-${e.javaClass.simpleName}", permanent = false)
        }
        val done = CompletableDeferred<ListenResult>()
        // SpeechService 생성자가 AudioRecord 를 연다 — 여기서부터 끝(취소 포함)까지 finally 가 닫는다
        val service = try {
            SpeechService(recognizer, SAMPLE_RATE)
        } catch (e: Exception) {
            runCatching { recognizer.close() }
            return ListenResult.Failed("mic-${e.javaClass.simpleName}", permanent = false)
        }
        // 구간·부분 결과를 이어 문장 전체로 판정(B014) — 콜백(메인 스레드)과 확정 타이머가 같이 쓰므로 잠근다
        val segs = YesNoSegments()
        var lastConf: Float? = null
        fun heard(a: YesNo) = ListenResult.Heard(a, lastConf)
        return try {
            val listener = object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val text = VoskResult.partialText(hypothesis)
                    synchronized(segs) { segs.onPartial(text) }?.let { done.complete(heard(it)) }
                }
                override fun onResult(hypothesis: String?) {
                    // 한 마디 끝 — 아니면 바로, 네면 조금 더 들어 확정(아래 타이머)
                    val (text, conf) = VoskResult.parseText(hypothesis)
                    if (conf != null) lastConf = conf
                    synchronized(segs) { segs.onSegment(text, System.currentTimeMillis()) }?.let { done.complete(heard(it)) }
                }
                override fun onFinalResult(hypothesis: String?) {
                    val (text, conf) = VoskResult.parseText(hypothesis)
                    if (conf != null) lastConf = conf
                    done.complete(heard(synchronized(segs) { segs.onFinal(text) }))
                }
                override fun onError(exception: Exception?) {
                    done.complete(ListenResult.Failed("vosk-${exception?.javaClass?.simpleName ?: "error"}", permanent = false))
                }
                override fun onTimeout() {
                    done.complete(heard(synchronized(segs) { segs.current() }))
                }
            }
            val started = withContext(Dispatchers.Main) { service.startListening(listener) }
            if (!started) return ListenResult.Failed("mic-busy", permanent = false)
            coroutineScope {
                // 네 확정 타이머 — 구간이 끝난 뒤 조금 더 들었는데도 문장이 네면 끝낸다
                val ticker = launch {
                    while (!done.isCompleted) {
                        delay(CONFIRM_TICK_MS)
                        synchronized(segs) { segs.onTick(System.currentTimeMillis()) }?.let { done.complete(heard(it)) }
                    }
                }
                try {
                    withTimeoutOrNull(budget) { done.await() } ?: run {
                        // 시간이 다 됐다 — stop() 은 마지막 결과(onFinalResult)를 낸다
                        withContext(Dispatchers.Main) { runCatching { service.stop() } }
                        withTimeoutOrNull(STOP_GRACE_MS) { done.await() } ?: heard(synchronized(segs) { segs.current() })
                    }
                } finally {
                    ticker.cancel()
                }
            }
        } finally {
            // 취소된 뒤에도 반드시 돈다(NonCancellable) — stop() 은 녹음 스레드를 멈추고 join, shutdown() 은 AudioRecord 해제
            withContext(NonCancellable + Dispatchers.Main) {
                runCatching { service.stop() }
                runCatching { service.shutdown() }
            }
            runCatching { recognizer.close() }
        }
    }

    override suspend fun listenWav(file: File, timeoutMs: Long): ListenResult {
        val m = loadModel() ?: return ListenResult.Failed(modelReason() ?: "no-model", permanent = loadError != null)
        return withContext(Dispatchers.IO) {
            val info = runCatching {
                file.inputStream().use { input -> ByteArray(4096).let { buf -> buf.copyOf(input.read(buf).coerceAtLeast(0)) } }
            }.getOrNull()?.let { WavInfo.parse(it, file.length()) }
                ?: return@withContext ListenResult.Failed("wav-header", permanent = false)
            if (!info.isPcm16Mono16k) return@withContext ListenResult.Failed("wav-format ${info.sampleRate}Hz/${info.channels}ch/${info.bitsPerSample}bit", permanent = false)
            val recognizer = try {
                newRecognizer(m)
            } catch (e: Exception) {
                return@withContext ListenResult.Failed("recognizer-${e.javaClass.simpleName}", permanent = false)
            }
            // 마이크 듣기와 같은 판정(B014) — 아니는 구간에서 바로, 네는 파일을 끝까지 듣고 문장 전체로(파일은 실시간보다 빨리 흐른다)
            val segs = YesNoSegments()
            var lastConf: Float? = null
            try {
                withTimeoutOrNull(timeoutMs) {
                    RandomAccessFile(file, "r").use { raf ->
                        raf.seek(info.dataOffset.toLong())
                        val buf = ByteArray(CHUNK_BYTES)
                        var left = info.dataBytes
                        while (left > 0) {
                            ensureActive()
                            val n = raf.read(buf, 0, minOf(buf.size, left))
                            if (n <= 0) break
                            left -= n
                            if (recognizer.acceptWaveForm(buf, n)) {
                                val (text, conf) = VoskResult.parseText(recognizer.result)
                                if (conf != null) lastConf = conf
                                if (segs.onSegment(text, 0L) == YesNo.NO) return@withTimeoutOrNull ListenResult.Heard(YesNo.NO, lastConf)
                            }
                        }
                    }
                    val (text, conf) = VoskResult.parseText(recognizer.finalResult)
                    if (conf != null) lastConf = conf
                    ListenResult.Heard(segs.onFinal(text), lastConf)
                } ?: ListenResult.Heard(segs.current(), lastConf)
            } finally {
                runCatching { recognizer.close() }
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000f
        const val CHUNK_BYTES = 8_000   // 0.25 s
        const val STOP_GRACE_MS = 1_000L
        /** 적재를 기다리고 남은 듣기 시간이 이보다 짧으면 이 단계를 건너뛴다(다음 단계에 시간을 넘긴다) — Pleos STT 와 같은 값 */
        const val MIN_LISTEN_MS = 1_500L
        /** 네 확정 타이머 간격 */
        const val CONFIRM_TICK_MS = 100L
        /** 앱 `files/` 아래 모델 폴더 이름(tools/demo 가 여기에 푼다) */
        const val MODEL_DIR_NAME = "vosk-ko-small"
    }
}

/** Vosk 문법·결과 JSON 처리(순수 — JNI 를 건드리지 않아 JVM 단위 테스트에서 쓴다) */
object VoskResult {
    /** `["네","예",…,"[unk]"]` */
    val GRAMMAR_JSON: String = YesNoParser.VOSK_GRAMMAR_WORDS.joinToString(",", "[", "]") { "\"$it\"" }

    /** 결과 JSON `{"text": "…", "result": [{"conf": …, "word": …}]}` → 판정 + 낱말 신뢰도 평균. 깨진 JSON 은 NONE */
    fun parse(json: String?): ListenResult.Heard {
        val (text, conf) = parseText(json)
        return ListenResult.Heard(YesNoParser.parse(text), conf)
    }

    /** 결과 JSON → (문장, 낱말 신뢰도 평균). 깨진 JSON·빈 값은 (null, null) — 문장은 판정에만 쓰고 로그에 남기지 않는다 */
    fun parseText(json: String?): Pair<String?, Float?> {
        if (json.isNullOrBlank()) return null to null
        val obj = runCatching { DhJson.json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null to null
        val text = (obj["text"] as? JsonPrimitive)?.content
        val confs = (obj["result"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.get("conf")?.jsonPrimitive?.floatOrNull }.orEmpty()
        return text to confs.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    }

    /** 부분 결과 JSON `{"partial": "…"}` → 문장(없으면 null) */
    fun partialText(json: String?): String? {
        if (json.isNullOrBlank()) return null
        val obj = runCatching { DhJson.json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        return (obj["partial"] as? JsonPrimitive)?.content
    }
}
