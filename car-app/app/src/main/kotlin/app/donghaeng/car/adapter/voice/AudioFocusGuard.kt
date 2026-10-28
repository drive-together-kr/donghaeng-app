package app.donghaeng.car.adapter.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import app.donghaeng.car.core.common.DhLog

/** 오디오 포커스 창구 — 단위 테스트에서 가짜로 바꾼다 */
interface AudioFocusPort {
    /** 말하기 전에. 허용되면 true */
    fun request(): Boolean

    /** 마지막 문장이 끝난 뒤에 */
    fun abandon()
}

/** 포커스를 쓰지 않는 대역(테스트·포커스 없는 환경) */
object NoAudioFocus : AudioFocusPort {
    override fun request(): Boolean = true
    override fun abandon() = Unit
}

/**
 * 음성 안내 공통 오디오 정책(docs/05 §4): 용도 `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` · 말하기 전 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`
 * (음악은 줄이고 멈추지 않음), 마지막 문장이 끝나면 반납. Android TTS·Pleos TTS 후보가 같은 정책을 쓴다.
 */
class AudioFocusGuard(context: Context, private val attributes: AudioAttributes = NAV_GUIDANCE) : AudioFocusPort {

    private val audioManager: AudioManager? = context.applicationContext.getSystemService(AudioManager::class.java)
    private var focusRequest: AudioFocusRequest? = null

    override fun request(): Boolean {
        val am = audioManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes)
                    .setWillPauseWhenDucked(false)
                    .build()
                    .also { focusRequest = it }
                am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (e: Exception) {
            DhLog.w("오디오 포커스 요청 실패 ${e.javaClass.simpleName}")
            false
        }
    }

    override fun abandon() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let { am.abandonAudioFocusRequest(it) }
            else @Suppress("DEPRECATION") am.abandonAudioFocus(null)
        } catch (e: Exception) {
            DhLog.w("오디오 포커스 반납 실패 ${e.javaClass.simpleName}")
        }
    }

    companion object {
        /** 내비 안내 용도 — 순정 내비 안내와 같은 버스(AAOS)로 나간다 */
        val NAV_GUIDANCE: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
