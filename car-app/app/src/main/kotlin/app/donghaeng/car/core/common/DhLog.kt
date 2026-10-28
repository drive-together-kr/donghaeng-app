package app.donghaeng.car.core.common

import android.util.Log
import app.donghaeng.car.BuildConfig
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 앱 공용 로그 래퍼. 태그는 "DongHaeng" (logcat 필터: `adb logcat -s DongHaeng`),
 * capability 점검 결과만 별도 태그 "DH_CAP" (`adb logcat -s DH_CAP`, docs/09 §3 체크리스트 기록용).
 *
 * 좌표·닉네임·토큰 원문은 로그에 남기지 않는다(CLAUDE.md 규칙 8) — 호출 측에서
 * [CoordinateMasking] / [TextMasking] 을 거친 문자열만 넘길 것.
 * `android.util.Log` 는 이 객체에서만 직접 호출한다(앱 전체의 단일 창구).
 *
 * **로그 보존 (T33, docs/10 R13):** logcat main 버퍼는 위치·차량 신호 로그 때문에 7분 만에 회전해 사후 원인 추적이 불가능했다.
 * I/W/E 줄은 (1) 메모리 링 버퍼 [RING_CAPACITY] 줄(진단 화면 '최근 로그' 카드)과 (2) [attach] 이후 `files/dh_log.txt`
 * (256 KB 넘으면 `dh_log.1.txt` 로 밀어내는 2개 순환 = 최대 512 KB)에 **이미 마스킹된 문자열 그대로** 남는다.
 * 그룹 주행 중 보존 줄은 분당 약 20줄 × 130 B ≈ 2.6 KB 라 파일 두 개로 약 3시간을 덮는다 — R13 처럼 40분 뒤 발견해도
 * `token store: cleared …` 같은 핵심 줄이 남는다(64 KB 일 때는 25~30분이라 밀려날 수 있었다, T33 리뷰).
 * 예외는 클래스 이름만 남긴다(메시지에 URL·좌표가 섞일 수 있다). D 레벨과 `keep = false` 로 부른 고빈도 줄은 보존하지 않는다.
 * 꺼내기(debug 빌드): `adb shell run-as app.donghaeng.car cat files/dh_log.txt` (Pleos AVD 는 `run-as --user 10 app.donghaeng.car …`).
 */
object DhLog {
    const val TAG = "DongHaeng"

    /** capability 점검 전용 태그 — 값은 SDK 지원 여부뿐이라 개인정보가 없다 */
    const val CAP_TAG = "DH_CAP"

    /** 메모리 링 버퍼 줄 수 */
    const val RING_CAPACITY = 300

    /** 파일 하나의 최대 크기 — 넘으면 `.1` 로 밀어내고 새로 쓴다(두 파일 합 최대 512 KB ≈ 그룹 주행 3시간) */
    const val FILE_ROTATE_BYTES = 256 * 1024L

    const val FILE_NAME = "dh_log.txt"
    const val FILE_NAME_PREVIOUS = "dh_log.1.txt"

    private val ring = ArrayDeque<String>(RING_CAPACITY)
    private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS", Locale.ROOT).withZone(ZoneId.systemDefault())

    @Volatile
    private var sink: FileSink? = null

    /** Application.onCreate 에서 한 번 — 이후 I/W/E 줄을 파일에도 남긴다 */
    fun attach(filesDir: File) {
        if (sink != null) return
        sink = FileSink(File(filesDir, FILE_NAME), File(filesDir, FILE_NAME_PREVIOUS), FILE_ROTATE_BYTES)
    }

    /** @param keep false 면 logcat 에만 남기고 링 버퍼·파일에는 넣지 않는다(초당 여러 번 바뀌는 값의 변화 로그 등) */
    fun i(message: String, keep: Boolean = true) {
        Log.i(TAG, message)
        if (keep) keep('I', message, null)
    }

    /** 디버그 빌드에서만 출력한다. 보존하지 않는다. */
    fun d(message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message)
    }

    fun w(message: String, t: Throwable? = null) {
        if (t == null) Log.w(TAG, message) else Log.w(TAG, message, t)
        keep('W', message, t)
    }

    fun e(message: String, t: Throwable? = null) {
        if (t == null) Log.e(TAG, message) else Log.e(TAG, message, t)
        keep('E', message, t)
    }

    /** capability 한 항목 — `CapabilityEntry.format()` 결과를 그대로 넘긴다(예: `Fuel supported=true detail=accessType=READ source=PLEOS_SDK`). */
    fun cap(line: String) {
        Log.i(CAP_TAG, line)
    }

    /** 최근 보존 줄(오래된 것 → 최신 순) 최대 [n] 개 */
    fun recent(n: Int = 30): List<String> = synchronized(ring) { ring.toList().takeLast(n.coerceAtLeast(0)) }

    private fun keep(level: Char, message: String, t: Throwable?) {
        val line = buildString {
            append(timeFormat.format(Instant.now())).append(' ').append(level).append(' ').append(message.replace('\n', ' '))
            if (t != null) append(" (").append(t.javaClass.simpleName).append(')')
        }
        synchronized(ring) {
            if (ring.size >= RING_CAPACITY) ring.removeFirst()
            ring.addLast(line)
        }
        sink?.append(line)
    }

    /** 파일 기록은 단일 백그라운드 스레드에서 — 호출 스레드(메인 포함)는 디스크를 기다리지 않는다 */
    private class FileSink(private val file: File, private val previous: File, private val rotateBytes: Long) {
        private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "dh-log-file").apply { isDaemon = true } }

        fun append(line: String) {
            runCatching {
                executor.execute {
                    try {
                        if (file.length() >= rotateBytes) {
                            previous.delete()
                            file.renameTo(previous)
                        }
                        file.appendText(line + "\n", Charsets.UTF_8)
                    } catch (e: Exception) {
                        // 저장 공간 부족 등 — 로그 때문에 앱이 죽지 않게 삼킨다(logcat 에는 이미 남았다)
                    }
                }
            }
        }
    }
}
