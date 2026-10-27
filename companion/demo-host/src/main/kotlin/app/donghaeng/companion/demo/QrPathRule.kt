package app.donghaeng.companion.demo

import app.donghaeng.companion.sdk.DongHaengException
import java.net.URI

/** 호스트 앱이 QR 을 어느 동행 API 로 보낼지 고르기 위한 종류 */
enum class DemoQrKind { JOIN, PAIR, UNKNOWN }

/**
 * 호스트 앱 쪽의 두 경로 규칙(docs/07 §4). 동행 모듈은 `joinGroup`/`pairVehicle` 를 호스트가 골라 부르게 하므로
 * 스캐너를 가진 공식 앱이 경로만 보고 어느 쪽을 부를지 정한다. 호스트 검증·토큰 해석은 모듈이 다시 엄격하게 한다.
 */
object QrPathRule {

    fun classify(payload: String): DemoQrKind {
        val uri = runCatching { URI(payload.trim()) }.getOrNull() ?: return DemoQrKind.UNKNOWN
        val path = uri.path.orEmpty().trimEnd('/')
        return when {
            path.endsWith("/pair") -> DemoQrKind.PAIR
            path.contains("/j/") -> DemoQrKind.JOIN
            else -> DemoQrKind.UNKNOWN
        }
    }
}

/** 동행 예외를 사용자 문구로 — 공식 앱은 자기 디자인 가이드에 맞춰 이 매핑을 직접 한다 */
fun DongHaengException.toUserMessage(): String = when (this) {
    is DongHaengException.NotInitialized -> "먼저 로그인해 주세요"
    is DongHaengException.Unauthorized -> "계정 인증이 만료됐어요. 다시 로그인해 주세요"
    is DongHaengException.InvalidQr -> "동행 QR이 아니에요 ($reason)"
    is DongHaengException.OtherServer -> "다른 서버의 QR이에요 ($host). 앱의 서버 설정을 확인해 주세요"
    is DongHaengException.Api -> "${message ?: "요청에 실패했어요"} [$code]"
}
