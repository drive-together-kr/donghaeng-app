package app.donghaeng.car.domain.ptt

import android.content.pm.ServiceInfo

/**
 * 지금 녹음하면 소리가 담기는가(B015) — 마이크 권한(RECORD_AUDIO)과 별개인 **while-in-use 제한**(Android 11/API 30+) 판정, 순수 함수.
 *
 * API 30+ 에서 앱이 백그라운드(순정 내비가 앞)면 마이크는 **microphone 타입 포그라운드 서비스**가 있을 때만 열린다 — 없으면 `MediaRecorder` 가
 * 오류 없이 **무음**을 녹음한다. 그룹 서비스(`DriveSessionService`)가 백그라운드에서 시작·재시작(폰 합류·START_STICKY)되면 microphone 이 빠지고,
 * 동행 화면이 한 번 앞에 와야 붙는다(`DriveSessionController.ensureType`). 그 사이 핸들·패널로 무전을 누르면 무음이 보관·전송되므로 녹음하지 않고 안내한다.
 *
 * - API 30 미만 → 제한 없음(true)
 * - 동행 화면이 보임 → true
 * - 붙은 포그라운드 타입에 microphone 비트가 있음 → true
 * - 그 밖(서비스 없음·타입 모름 포함) → false
 */
object PttMicGate {
    /** `Build.VERSION_CODES.R` */
    const val SDK_WHILE_IN_USE = 30

    fun usable(sdk: Int, appVisible: Boolean, attachedFgsType: Int?): Boolean = when {
        sdk < SDK_WHILE_IN_USE -> true
        appVisible -> true
        attachedFgsType == null -> false
        else -> attachedFgsType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0
    }
}
