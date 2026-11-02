package app.donghaeng.companion.demo

import java.net.URI
import java.net.URLDecoder

/** 호스트 앱이 QR을 어느 동행 API 로 보낼지 고르기 위한 종류 */
enum class DemoQrKind { JOIN, PAIR, UNKNOWN }

/**
 * 호스트 앱 쪽의 두 경로 규칙(docs/07 §5). 동행 모듈은 `joinGroup`/`pairVehicle` 를 호스트가 골라 부르게 하므로
 * 스캐너를 가진 공식 앱이 경로만 보고 어느 쪽을 부를지 정한다. 호스트 검증·토큰 해석은 모듈이 다시 엄격하게 한다.
 *
 * 경로 규칙은 모듈의 `QrParser` 와 같다(경로 우선, 호스트는 보지 않음):
 * - 경로가 정확히 `/j/{token}` 한 세그먼트(끝 `/` 허용) → [DemoQrKind.JOIN]
 * - 경로가 정확히 `/pair` 이고 쿼리 `c=` 코드가 비어 있지 않음 → [DemoQrKind.PAIR]
 * - 그 외(`/pair` 에 코드가 없거나, `/j/` 가 중간에만 있거나, http(s) URL 이 아님) → [DemoQrKind.UNKNOWN]
 * 서버 주소에 경로 접두사(예: `https://x.sslip.io/dh`)가 있으면 모듈과 똑같이 먼저 뗀다.
 */
object QrPathRule {

    /** 모듈 QrParser 와 같은 토큰 문자 집합 */
    private val joinPath = Regex("^/j/[A-Za-z0-9_.~-]+/?$")

    fun classify(payload: String, publicBaseUrl: String = BuildConfig.DH_PUBLIC_BASE_URL): DemoQrKind {
        val uri = runCatching { URI(payload.trim()) }.getOrNull() ?: return DemoQrKind.UNKNOWN
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return DemoQrKind.UNKNOWN

        val basePath = runCatching { URI(publicBaseUrl.trim().trimEnd('/')).path }.getOrNull()?.trimEnd('/').orEmpty()
        val rawPath = uri.path.orEmpty()
        val path = if (basePath.isNotEmpty() && rawPath.startsWith(basePath)) rawPath.removePrefix(basePath) else rawPath

        return when {
            (path == "/pair" || path == "/pair/") && hasPairCode(uri.rawQuery) -> DemoQrKind.PAIR
            joinPath.matches(path) -> DemoQrKind.JOIN
            else -> DemoQrKind.UNKNOWN
        }
    }

    /** 쿼리에 비어 있지 않은 `c=` 값이 있는지 (모듈은 이 값을 userCode 로 쓴다) */
    private fun hasPairCode(rawQuery: String?): Boolean =
        rawQuery.orEmpty().split('&').any { pair ->
            pair.startsWith("c=") &&
                runCatching { URLDecoder.decode(pair.substring(2), "UTF-8") }.getOrDefault(pair.substring(2)).isNotBlank()
        }
}

// 동행 예외 → 사용자 문구는 donghaeng/DongHaengStore.kt 의 toUserText 하나로 합쳤다(D077 — 쓰이지 않던 toUserMessage 삭제)
