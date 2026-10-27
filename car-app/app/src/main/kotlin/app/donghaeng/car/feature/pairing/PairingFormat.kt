package app.donghaeng.car.feature.pairing

import java.util.Locale

/** 페어링 화면 표시용 순수 함수 — 단위 테스트 대상. */
object PairingFormat {

    /**
     * 서버 userCode(하이픈 없는 6자리, 예 `KQ74MZ`)를 읽기 쉽게 `KQ7-4MZ` 로 나눈다(docs/03 §1.2).
     * 이미 하이픈이 있거나 공백이 섞여 와도 영숫자만 남겨 다시 나눈다. 6자리가 아니면 영숫자만 대문자로 돌려준다.
     */
    fun formatUserCode(userCode: String): String {
        val clean = userCode.filter { it.isLetterOrDigit() }.uppercase(Locale.ROOT)
        return if (clean.length == 6) clean.substring(0, 3) + "-" + clean.substring(3) else clean
    }

    /** 남은 시간 `m:ss`. 음수는 0:00. */
    fun formatRemaining(remainingMillis: Long): String {
        val totalSec = (remainingMillis.coerceAtLeast(0L) + 999L) / 1000L
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.ROOT, "%d:%02d", min, sec)
    }
}
