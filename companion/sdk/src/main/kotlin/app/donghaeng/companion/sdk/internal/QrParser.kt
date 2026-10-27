package app.donghaeng.companion.sdk.internal

import java.net.URI
import java.net.URLDecoder

/**
 * 동행 QR 문자열 해석 (docs/07 §5 판별 규칙).
 *
 * 서버가 `{DH_PUBLIC_BASE_URL}` + 경로로 QR 을 만들므로 **호스트가 아니라 경로**로 종류를 가른다.
 * ```
 * {base}/pair?c=CODE   → Pair(CODE)      차량 등록(Device Authorization Grant 승인)
 * {base}/j/{token}     → Join(token)     그룹 합류
 * 경로는 맞는데 호스트가 base 와 다름 → OtherServer(host)
 * 그 밖의 모든 것          → Unknown
 * ```
 * 호스트 비교는 대소문자를 무시하고 포트(생략 시 스킴 기본값)까지 본다 — 포트가 다르면 다른 서버다.
 * base 끝의 `/` 와 base 에 붙은 경로 접두사(예: `https://x.sslip.io/dh`)는 허용한다.
 */
internal object QrParser {

    internal sealed interface Result {
        /** 차량 등록 QR. [code] 는 차 화면에 표시된 userCode(하이픈 없는 형태) */
        data class Pair(val code: String) : Result

        /** 그룹 합류 QR. [token] 은 초대 토큰(`inv_...`) */
        data class Join(val token: String) : Result

        /** 동행 QR 이지만 다른 서버의 것. [host] 는 QR 의 호스트(포트 포함) */
        data class OtherServer(val host: String) : Result

        /** 동행 QR 이 아님 */
        data object Unknown : Result
    }

    private val joinPath = Regex("^/j/([A-Za-z0-9_.~-]+)/?$")

    /**
     * @param payload 스캐너가 읽은 문자열
     * @param publicBaseUrl 설정된 서버 공개 주소(끝 `/` 있어도 됨)
     */
    internal fun parse(payload: String, publicBaseUrl: String): Result {
        val uri = payload.trim().toHttpUriOrNull() ?: return Result.Unknown
        val base = publicBaseUrl.trim().trimEnd('/').toHttpUriOrNull()

        // 1) 경로로 종류 판별 (base 에 경로 접두사가 있으면 떼고 본다)
        val basePath = base?.path?.trimEnd('/').orEmpty()
        val rawPath = uri.path.orEmpty()
        val path = if (basePath.isNotEmpty() && rawPath.startsWith(basePath)) rawPath.removePrefix(basePath) else rawPath

        val kind: Result = when {
            path == "/pair" || path == "/pair/" -> {
                val code = queryParam(uri.rawQuery, "c")?.trim().orEmpty()
                if (code.isEmpty()) return Result.Unknown
                Result.Pair(code)
            }
            else -> {
                val m = joinPath.find(path) ?: return Result.Unknown
                Result.Join(m.groupValues[1])
            }
        }

        // 2) 경로는 동행 QR — 호스트가 설정된 서버와 같은지 확인
        if (base == null || !sameServer(uri, base)) {
            return Result.OtherServer(hostWithPort(uri))
        }
        return kind
    }

    private fun String.toHttpUriOrNull(): URI? {
        val uri = runCatching { URI(this) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return uri
    }

    private fun sameServer(a: URI, b: URI): Boolean =
        a.host.equals(b.host, ignoreCase = true) && effectivePort(a) == effectivePort(b)

    private fun effectivePort(uri: URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }

    private fun hostWithPort(uri: URI): String =
        if (uri.port != -1) "${uri.host}:${uri.port}" else uri.host

    private fun queryParam(rawQuery: String?, name: String): String? {
        if (rawQuery.isNullOrEmpty()) return null
        for (pair in rawQuery.split('&')) {
            val idx = pair.indexOf('=')
            val key = if (idx >= 0) pair.substring(0, idx) else pair
            if (key != name) continue
            val value = if (idx >= 0) pair.substring(idx + 1) else ""
            return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
        }
        return null
    }
}
