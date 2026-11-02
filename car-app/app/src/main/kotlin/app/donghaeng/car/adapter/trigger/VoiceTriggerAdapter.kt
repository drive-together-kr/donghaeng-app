package app.donghaeng.car.adapter.trigger

import android.view.KeyEvent
import app.donghaeng.car.core.common.DhLog
import app.donghaeng.car.domain.ptt.PttSettings
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 무전 버튼이 눌린 곳 */
enum class PttTriggerSource {
    /** ① 플로팅 패널 [무전] 탭 */
    PANEL,

    /** ② 정차 대시보드 [무전] 탭 */
    DASHBOARD,

    /** ③ 동행 화면이 앞일 때 `MainActivity.dispatchKeyEvent`(SEARCH·HEADSETHOOK·MEDIA_PLAY_PAUSE·MEDIA_PLAY) */
    ACTIVITY_KEY,

    /** ④ 그룹 주행 중 백그라운드 — MediaSession 미디어 버튼(HEADSETHOOK·MEDIA_PLAY_PAUSE·MEDIA_PLAY) */
    MEDIA_SESSION,

    /** 디버그 리시버 `ptt_press` */
    DEBUG,
}

data class PttPress(val source: PttTriggerSource, val key: String?, val atMs: Long)

/** 트리거 상태(진단 한 줄) */
data class VoiceTriggerStatus(
    val groupActive: Boolean = false,
    val handleButton: Boolean = true,
    /** 다른 앱이 미디어를 재생 중 — 세션을 내려 재생/정지 키를 음악 앱에 돌려준다 */
    val otherMediaActive: Boolean = false,
    val sessionActive: Boolean = false,
    /** 켜 둔 MediaSession 의 재생 상태 이름(`PAUSED` — [PttSessionPlayback]), 세션이 없으면 null */
    val sessionState: String? = null,
    val presses: Int = 0,
    val consumedKeys: Int = 0,
    val lastSource: PttTriggerSource? = null,
    val lastKey: String? = null,
    val detail: String? = null,
) {
    override fun toString(): String =
        "group=$groupActive handleButton=$handleButton otherMedia=$otherMediaActive session=$sessionActive sessionState=${sessionState ?: "-"} " +
            "presses=$presses consumed=$consumedKeys last=${lastSource ?: "-"}/${lastKey ?: "-"}${detail?.let { " detail=$it" } ?: ""}"
}

/**
 * 무전 트리거 어댑터 (F16, docs/05 § 무전 — 2026-10-28 Pleos AVD 실측 근거).
 *
 * - **MediaSession**(기기: [MediaSessionVoiceTrigger]): 활성 세션 + 짧은 무음 재생(USAGE_MEDIA)으로 우리 세션이 'Media button session' 이 되면
 *   순정 내비가 앞에 있어도 `KEYCODE_HEADSETHOOK`·`MEDIA_PLAY_PAUSE`·`MEDIA_PLAY` 가 온다. `KEYCODE_SEARCH`·`VOICE_ASSIST` 는 시스템 몫이라 안 온다 —
 *   게다가 내비가 앞일 때 SEARCH 를 보내면 Pleos 전역 검색이 열려 내비·패널을 덮는다(docs/10 R14). 그래서 SEARCH 는 동행 화면이 앞일 때
 *   ([onActivityKey])만 무전으로 쓴다.
 * - 세션은 **그룹이 있고** 설정 **'핸들 버튼으로 무전'**([PttSettings.handleButton], 기본 켬)이 켜져 있고 **마이크를 쓸 수 있을 때**([BaseVoiceTrigger.micAvailable]
 *   — release: RECORD_AUDIO 승인, B046)만 켠다. 마이크 권한이 없는데 세션을 켜 두면 재생/정지 키를 빼앗아 누를 때마다 "마이크 권한이 필요해요"만 읽는다.
 *   debug 빌드는 권한 없이 주입 녹음으로 키 시연(tools/demo/ptt.py)을 하므로 늘 켤 수 있다(DI). 화면 [무전] 탭은 지금처럼 권한 안내를 보인다.
 * - **음악 앱과 충돌 방지**: 다른 앱이 미디어(USAGE_MEDIA·GAME)를 재생 중이면 세션을 내려 재생/정지 키를 음악 앱에 돌려준다. 음악이 멈춘 뒤
 *   [TriggerPolicy.RECLAIM_DELAY_MS](15초) 동안은 그대로 둔다 — 핸들로 음악을 멈춘 직후 다시 누르면 음악이 이어져야 하므로.
 * - 키는 down 1회만 누름으로 센다(반복·up 은 소비만, [PttKeyFilter]). 300 ms 안의 두 번째 down 은 같은 누름(두 경로로 온 중복)으로 본다.
 * - 패널·대시보드 탭([press])은 설정과 무관하다.
 *
 * 구독자(PttRuntime)는 [presses] 를 받아 PttController 에 넘긴다. 테스트: [FakeVoiceTrigger].
 */
interface VoiceTriggerAdapter {
    val presses: SharedFlow<PttPress>
    val status: StateFlow<VoiceTriggerStatus>

    /** 그룹 세션 유무 — PttRuntime 이 부른다 */
    fun setGroupActive(active: Boolean)

    /** 화면 탭·디버그 */
    fun press(source: PttTriggerSource)

    /**
     * ③ `MainActivity.dispatchKeyEvent` 에서 — 무전 키로 썼으면(누름이든 반복·up 소비든) true 를 돌려주고 액티비티는 이벤트를 소비한다.
     * 그룹이 없거나 '핸들 버튼으로 무전'이 꺼져 있으면 false(평소처럼 흘려보낸다). 다른 앱이 미디어를 재생 중(또는 멈춘 뒤 되찾기 대기)이면
     * 재생/정지 키(HEADSETHOOK·MEDIA_PLAY_PAUSE·MEDIA_PLAY)도 false — 음악 앱 몫([TriggerPolicy.interceptActivityKey]). SEARCH 는 미디어 키가 아니라 그대로 무전.
     */
    fun onActivityKey(keyCode: Int, action: Int, repeatCount: Int, eventTimeMs: Long): Boolean

    fun start()
    fun stop()
}

/** 무전 키 집합 */
object PttKeys {
    /** 동행 화면이 앞일 때 */
    val FOREGROUND: Set<Int> = setOf(KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY)

    /** MediaSession(백그라운드) — SEARCH 는 오지 않는다 */
    val BACKGROUND: Set<Int> = setOf(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY)

    /** 로그용 이름(KeyEvent.keyCodeToString 은 JVM 테스트에 없다) */
    fun name(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_SEARCH -> "SEARCH"
        KeyEvent.KEYCODE_HEADSETHOOK -> "HEADSETHOOK"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "MEDIA_PLAY_PAUSE"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "MEDIA_PLAY"
        else -> "KEY_$keyCode"
    }
}

/** 키 → 누름 판정(순수) — down(반복 0)만 누름, 반복·up 은 소비, [debounceMs] 안의 두 번째 down 도 소비 */
class PttKeyFilter(private val debounceMs: Long = DEBOUNCE_MS) {
    enum class Verdict { PRESS, CONSUME, PASS }

    private var lastPressAt: Long? = null

    @Synchronized
    fun onKey(keyCode: Int, action: Int, repeatCount: Int, eventTimeMs: Long, allowed: Set<Int>): Verdict {
        if (keyCode !in allowed) return Verdict.PASS
        if (action != KeyEvent.ACTION_DOWN || repeatCount > 0) return Verdict.CONSUME
        val last = lastPressAt
        if (last != null && eventTimeMs - last in 0 until debounceMs) return Verdict.CONSUME
        lastPressAt = eventTimeMs
        return Verdict.PRESS
    }

    companion object {
        const val DEBOUNCE_MS = 300L
    }
}

/** 트리거 켜기 판단(순수) */
object TriggerPolicy {
    /** 음악이 멈춘 뒤 세션을 다시 가져오기까지 */
    const val RECLAIM_DELAY_MS = 15_000L

    /** 핸들·미디어 키를 무전에 쓸까 — 그룹 있음 + 설정 켬 + 마이크를 쓸 수 있음(B046, 기본 true) */
    fun keysEnabled(groupActive: Boolean, handleButton: Boolean, micAvailable: Boolean = true): Boolean = groupActive && handleButton && micAvailable

    /**
     * 재생 구성 하나 → 다른 앱 미디어가 재생 중인가. 우리 무음(USAGE_MEDIA, 세션을 켤 때 400 ms)이 도는 창([ownSilenceUntil]) 안에서는
     * MEDIA 구성이 우리 것인지 남의 것인지 구별할 수 없다(구성의 앱·세션 정보는 공개 API 가 아니다) → null = '모름, 창이 끝나면 다시 읽어 판단'.
     * MEDIA 가 하나도 없으면 창 안이어도 확실히 false.
     */
    fun otherMediaFrom(mediaPlaying: Boolean, now: Long, ownSilenceUntil: Long): Boolean? = when {
        !mediaPlaying -> false
        now >= ownSilenceUntil -> true
        else -> null
    }

    /**
     * 동행 화면이 앞일 때 키를 가로챌까(사용자 지시 2026-10-28 — 그룹 주행 중 + 설정 켬일 때만, 음악과 충돌 금지):
     * 그룹·설정 중 하나라도 아니면 통과(super 로 — 원래 동작), SEARCH 는 가로챔, 재생/정지 키는 다른 앱 미디어에 양보 중이면 통과.
     * @param yieldingMedia 다른 앱이 미디어를 재생 중이거나 멈춘 지 [RECLAIM_DELAY_MS] 가 안 됐다(MediaSession 을 내려 둔 것과 같은 판단)
     */
    fun interceptActivityKey(keyCode: Int, groupActive: Boolean, handleButton: Boolean, yieldingMedia: Boolean, micAvailable: Boolean = true): Boolean = when {
        !keysEnabled(groupActive, handleButton, micAvailable) -> false
        keyCode == KeyEvent.KEYCODE_SEARCH -> true
        keyCode in PttKeys.BACKGROUND -> !yieldingMedia
        else -> false
    }

    /**
     * MediaSession 을 켜 둘까 — 키가 켜져 있고, 다른 앱 미디어가 재생 중이 아니고, 멈춘 지 [reclaimDelayMs] 가 지났을 때.
     * @param otherMediaStoppedAgoMs 다른 앱 미디어가 멈춘 지 얼마나 됐나(멈춘 적이 없으면 null)
     */
    fun sessionActive(
        groupActive: Boolean,
        handleButton: Boolean,
        otherMediaActive: Boolean,
        otherMediaStoppedAgoMs: Long?,
        reclaimDelayMs: Long = RECLAIM_DELAY_MS,
        micAvailable: Boolean = true,
    ): Boolean = keysEnabled(groupActive, handleButton, micAvailable) && !otherMediaActive && (otherMediaStoppedAgoMs == null || otherMediaStoppedAgoMs >= reclaimDelayMs)
}

/**
 * 공통 — 누름 흐름·상태·키 판정. 구현은 [onInputsChanged] 에서 세션을 켜고 끈다.
 * [micAvailable] = 핸들·미디어 키를 무전에 써도 되는가(B046 — release 는 마이크 권한, debug 는 늘 true). 바뀌면 구현이 [onInputsChanged] 를 부른다.
 */
abstract class BaseVoiceTrigger(
    protected val settings: PttSettings,
    protected val clock: () -> Long,
    protected val micAvailable: StateFlow<Boolean> = MutableStateFlow(true),
) : VoiceTriggerAdapter {

    private val _presses = MutableSharedFlow<PttPress>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val presses: SharedFlow<PttPress> = _presses.asSharedFlow()

    protected val _status = MutableStateFlow(VoiceTriggerStatus(handleButton = settings.handleButton.value))
    override val status: StateFlow<VoiceTriggerStatus> = _status.asStateFlow()

    protected val groupActive = MutableStateFlow(false)
    private val filter = PttKeyFilter()

    override fun setGroupActive(active: Boolean) {
        if (groupActive.value == active) return
        groupActive.value = active
        _status.update { it.copy(groupActive = active) }
        DhLog.i("ptt: trigger group=$active")
        onInputsChanged()
    }

    override fun press(source: PttTriggerSource) = emit(source, null)

    override fun onActivityKey(keyCode: Int, action: Int, repeatCount: Int, eventTimeMs: Long): Boolean {
        if (!TriggerPolicy.interceptActivityKey(keyCode, groupActive.value, settings.handleButton.value, yieldingMediaKeys(), micAvailable.value)) return false
        return onKey(PttTriggerSource.ACTIVITY_KEY, keyCode, action, repeatCount, eventTimeMs, PttKeys.FOREGROUND)
    }

    /** 다른 앱 미디어에 재생/정지 키를 양보 중인가(재생 중 또는 멈춘 뒤 되찾기 대기) — 기본은 재생 중 여부만 */
    protected open fun yieldingMediaKeys(): Boolean = _status.value.otherMediaActive

    /** MediaSession 미디어 버튼 — 무전 키로 썼으면 true */
    protected fun onSessionKey(keyCode: Int, action: Int, repeatCount: Int, eventTimeMs: Long): Boolean =
        onKey(PttTriggerSource.MEDIA_SESSION, keyCode, action, repeatCount, eventTimeMs, PttKeys.BACKGROUND)

    private fun onKey(source: PttTriggerSource, keyCode: Int, action: Int, repeatCount: Int, eventTimeMs: Long, allowed: Set<Int>): Boolean {
        if (!TriggerPolicy.keysEnabled(groupActive.value, settings.handleButton.value, micAvailable.value)) return false
        return when (filter.onKey(keyCode, action, repeatCount, eventTimeMs, allowed)) {
            PttKeyFilter.Verdict.PRESS -> {
                emit(source, PttKeys.name(keyCode))
                true
            }
            PttKeyFilter.Verdict.CONSUME -> {
                _status.update { it.copy(consumedKeys = it.consumedKeys + 1) }
                true
            }
            PttKeyFilter.Verdict.PASS -> false
        }
    }

    private fun emit(source: PttTriggerSource, key: String?) {
        _status.update { it.copy(presses = it.presses + 1, lastSource = source, lastKey = key) }
        DhLog.i("ptt: trigger source=$source${key?.let { " key=$it" } ?: ""}")
        _presses.tryEmit(PttPress(source, key, clock()))
    }

    /** 그룹·설정·다른 앱 미디어 상태가 바뀌었다 — 세션을 켜고 끈다 */
    protected abstract fun onInputsChanged()
}
