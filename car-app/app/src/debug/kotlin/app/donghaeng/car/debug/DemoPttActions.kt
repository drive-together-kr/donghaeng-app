package app.donghaeng.car.debug

import android.content.Context
import android.content.Intent
import app.donghaeng.car.adapter.audio.VoiceRecorder
import app.donghaeng.car.adapter.speech.RecognizerChain
import app.donghaeng.car.adapter.speech.VoskYesNoRecognizer
import app.donghaeng.car.adapter.trigger.PttTriggerSource
import app.donghaeng.car.adapter.trigger.VoiceTriggerAdapter
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.core.common.MicPermission
import app.donghaeng.car.domain.ptt.PttController
import app.donghaeng.car.domain.ptt.PttSettings
import app.donghaeng.car.domain.ptt.VoicePlayback
import app.donghaeng.car.domain.ptt.VoicePlaybackState
import app.donghaeng.car.domain.ptt.YesNo
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** F16 무전 디버그 액션 전용 의존성 — [DemoControlReceiver.Deps] 와 따로 둔다(다른 브랜치의 Deps 변경과 충돌하지 않게) */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DemoPttDeps {
    fun voiceTrigger(): VoiceTriggerAdapter
    fun pttController(): PttController
    fun voicePlayback(): VoicePlayback
    fun recognizerChain(): RecognizerChain
    fun voiceRecorder(): VoiceRecorder
    fun pttSettings(): PttSettings
    fun micPermission(): MicPermission
}

/**
 * F16 무전 디버그 액션 (debug 빌드 전용 — `DemoControlReceiver` 가 넘겨준다, 표는 그 파일 KDoc).
 * `tools/demo/ptt.py` 가 쓴다. 로그는 `demo: ptt_… ` 한 줄 — 녹음 내용·인식 원문·닉네임·좌표는 남기지 않는다(규칙 8).
 *
 * 파일 경로(`--es file`·`--es dir`): `/` 로 시작하면 그대로, 아니면 **앱 files 디렉터리 기준**(`ptt_test/yes_ne.wav` → `files/ptt_test/yes_ne.wav`).
 * Pleos AVD 는 앱이 사용자 10 에서 돈다 — `ptt.py` 는 `run-as --user 10` 으로 files 아래에 넣고 상대 경로를 준다.
 */
object DemoPttActions {
    val ACTIONS = setOf("ptt_press", "ptt_inject_record", "ptt_answer", "ptt_status", "ptt_unsent", "ptt_handle", "ptt_countdown", "vosk_install")

    /** 모델 복사처럼 오래 걸리는 일 — 브로드캐스트(goAsync 10초 한도)와 떼어 앱 프로세스에서 끝까지 돈다 */
    private val longScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun handle(context: Context, action: String, intent: Intent) {
        val d = EntryPointAccessors.fromApplication(context.applicationContext, DemoPttDeps::class.java)
        when (action) {
            "ptt_press" -> {
                // 핸들 키·패널 탭과 같은 경로(PttRuntime → 재생 중이면 끊고 → PttController.press)
                val before = d.pttController().state.value.stage
                d.voiceTrigger().press(PttTriggerSource.DEBUG)
                DhLog.i("demo: ptt_press stage(before)=$before")
            }
            "ptt_inject_record" -> {
                val f = resolve(context, intent.getStringExtra("file"))
                if (f == null || !f.isFile || !f.canRead()) {
                    DhLog.w("demo: ptt_inject_record 파일 없음·읽기 불가(--es file <files 기준 상대 경로 또는 절대 경로>) exists=${f?.exists()}")
                    return
                }
                d.voiceRecorder().injectNext(f)
                DhLog.i("demo: ptt_inject_record armed name=${f.name} bytes=${f.length()} — 다음 녹음 한 번을 이 파일로 대신")
            }
            "ptt_answer" -> {
                val chain = d.recognizerChain()
                val file = intent.getStringExtra("file")
                if (file != null) {
                    val f = resolve(context, file)
                    if (f == null || !f.isFile || !f.canRead()) {
                        DhLog.w("demo: ptt_answer wav 없음·읽기 불가 exists=${f?.exists()}")
                        return
                    }
                    // 다음(또는 지금) 듣기 한 번을 마이크 대신 이 WAV 로 — 받을 인식기(Vosk 모델)가 없으면 체인이 경고하고 마이크로 듣는다
                    chain.injectWav(f)
                    DhLog.i("demo: ptt_answer wav=${f.name} bytes=${f.length()} vosk=${voskState(context)}")
                    return
                }
                val answer = when (intent.getStringExtra("answer")?.trim()?.lowercase(Locale.ROOT)) {
                    "yes", "y", "네" -> YesNo.YES
                    "no", "n", "아니" -> YesNo.NO
                    "none", "silence", "-" -> YesNo.NONE
                    else -> null
                }
                if (answer == null) {
                    DhLog.w("demo: ptt_answer --es answer yes|no|none 또는 --es file <wav> 필요")
                    return
                }
                chain.injectAnswer(answer)
                DhLog.i("demo: ptt_answer answer=$answer stage=${d.pttController().state.value.stage}")
            }
            "ptt_status" -> {
                val c = d.pttController()
                d.micPermission().refresh()
                DhLog.i(
                    "demo: ptt_status ${c.describe()} | trigger ${d.voiceTrigger().status.value} | playback ${playbackLine(d.voicePlayback().state.value)} | " +
                        "handleButton=${d.pttSettings().handleButton.value} mic=${d.micPermission().granted.value} vosk=${voskState(context)}",
                )
            }
            "ptt_unsent" -> unsent(d, intent)
            "ptt_handle" -> {
                val on = intent.getBooleanExtra("on", true)
                d.pttSettings().setHandleButton(on)
                DhLog.i("demo: ptt_handle on=$on → trigger ${d.voiceTrigger().status.value}")
            }
            "ptt_countdown" -> {
                // 인식기를 모두 건너뛰어 카운트다운 모드("5초 뒤 보낼게요 — 취소하려면 무전 버튼")를 보인다 — 주입 답은 그대로 받는다
                val on = intent.getBooleanExtra("on", true)
                d.recognizerChain().forceCountdown = on
                DhLog.i("demo: ptt_countdown on=$on recognizers=[${d.recognizerChain().describe()}]")
            }
            "vosk_install" -> voskInstall(context, intent)
        }
    }

    /** '보내지 않은 무전' 목록 한 줄 + 선택 동작(`--es op resend|delete --es id <u1|first>`) — 정차 중에만(PttController 가 판단) */
    private suspend fun unsent(d: DemoPttDeps, intent: Intent) {
        val c = d.pttController()
        val list = c.unsent.value
        val now = System.currentTimeMillis()
        val rows = list.joinToString(", ") { "${it.id} dur=${it.durationMs}ms reason=${it.reason}${it.failure?.let { f -> " failure=$f" } ?: ""} age=${(now - it.createdAtMs) / 1000}s" }
        DhLog.i("demo: ptt_unsent n=${list.size} [$rows]")
        val op = intent.getStringExtra("op") ?: return
        val rawId = intent.getStringExtra("id") ?: "first"
        val id = if (rawId == "first") list.firstOrNull()?.id else rawId
        if (id == null) {
            DhLog.w("demo: ptt_unsent op=$op — 목록이 비었다")
            return
        }
        when (op) {
            "resend" -> DhLog.i("demo: ptt_unsent resend id=$id started=${c.resend(id)} (false = 주행 중·다른 무전 중·그룹 바뀜)")
            "delete" -> DhLog.i("demo: ptt_unsent delete id=$id deleted=${c.deleteUnsent(id)} (false = 주행 중·없음)")
            else -> DhLog.w("demo: ptt_unsent op=$op 모름(resend|delete)")
        }
    }

    /**
     * Vosk 모델 설치 — `--es dir <모델 폴더>`(am/·conf/ 가 있는 폴더 또는 그 위 한 단계) 를 앱 `files/vosk-ko-small` 로 복사한다.
     * `--ez remove true` 면 지운다(카운트다운 모드 확인용). 기본 dir = 앱 외부 files `vosk-ko-small`(adb push 대상 — 사용자 10 이면 /storage/emulated/10/…).
     * 253 MB 복사는 브로드캐스트 한도(10초)를 넘으므로 따로 돈다 — 끝나면 `demo: vosk_install done …`.
     */
    private fun voskInstall(context: Context, intent: Intent) {
        val dest = File(context.filesDir, VoskYesNoRecognizer.MODEL_DIR_NAME)
        if (intent.getBooleanExtra("remove", false)) {
            val ok = dest.deleteRecursively()
            DhLog.i("demo: vosk_install removed=$ok → ${voskState(context)} (앱이 이미 모델을 읽었으면 다음 듣기부터 no-model 로 건너뛴다)")
            return
        }
        val src0 = intent.getStringExtra("dir")?.let { resolve(context, it) } ?: context.getExternalFilesDir(null)?.let { File(it, VoskYesNoRecognizer.MODEL_DIR_NAME) }
        val src = src0?.let { modelRoot(it) }
        if (src == null) {
            DhLog.w("demo: vosk_install 모델 폴더 없음(am/·conf/ 가 있는 폴더) dir=${src0?.absolutePath} exists=${src0?.exists()}")
            return
        }
        longScope.launch {
            val started = System.currentTimeMillis()
            val tmp = File(context.filesDir, VoskYesNoRecognizer.MODEL_DIR_NAME + ".tmp")
            try {
                tmp.deleteRecursively()
                if (!src.copyRecursively(tmp, overwrite = true)) error("copy")
                dest.deleteRecursively()
                if (!tmp.renameTo(dest)) error("rename")
                val (files, bytes) = sizeOf(dest)
                DhLog.i("demo: vosk_install done files=$files bytes=$bytes ms=${System.currentTimeMillis() - started} → ${voskState(context)}")
            } catch (e: Exception) {
                tmp.deleteRecursively()
                DhLog.w("demo: vosk_install 실패 ${e.javaClass.simpleName}: ${e.message?.take(60)}")
            }
        }
        DhLog.i("demo: vosk_install started src=${src.absolutePath}")
    }

    /** am/·conf/ 가 있는 폴더 — 주어진 폴더 또는 그 바로 아래 한 폴더(zip 을 풀면 `vosk-model-small-ko-0.22/` 가 한 겹 더 있다) */
    private fun modelRoot(dir: File): File? {
        fun ok(f: File) = File(f, "am").isDirectory && File(f, "conf").isDirectory
        if (!dir.isDirectory) return null
        if (ok(dir)) return dir
        return dir.listFiles()?.firstOrNull { it.isDirectory && ok(it) }
    }

    private fun sizeOf(dir: File): Pair<Int, Long> {
        var n = 0
        var b = 0L
        dir.walkTopDown().filter { it.isFile }.forEach { n++; b += it.length() }
        return n to b
    }

    /** `files/vosk-ko-small` 상태 — present(am,conf,…) / absent */
    fun voskState(context: Context): String {
        val dir = File(context.filesDir, VoskYesNoRecognizer.MODEL_DIR_NAME)
        val parts = dir.list()?.sorted().orEmpty()
        return if (File(dir, "am").isDirectory && File(dir, "conf").isDirectory) "present(${parts.joinToString(",")})" else "absent"
    }

    private fun playbackLine(p: VoicePlaybackState): String {
        val playing = p.playing?.let { "${it.voice.voiceId}${if (it.ended) "/ended" else ""} pos=${it.positionMs}ms" } ?: "-"
        val past = p.past.groupingBy { it.status }.eachCount().entries.joinToString(",") { "${it.key}=${it.value}" }
        return "playing=$playing queued=${p.queued} held=${p.held} past=${p.past.size}[$past] received=${p.received} played=${p.played} stale=${p.stale} " +
            "own=${p.skippedOwn} notForMe=${p.skippedNotAudience} dlFail=${p.downloadFailed} playFail=${p.playFailed}"
    }

    /** `/` 로 시작하면 절대 경로, 아니면 앱 files 기준. `..` 는 받지 않는다 */
    fun resolve(context: Context, raw: String?): File? {
        val p = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (p.split('/').any { it == ".." }) return null
        return if (p.startsWith("/")) File(p) else File(context.filesDir, p)
    }
}
