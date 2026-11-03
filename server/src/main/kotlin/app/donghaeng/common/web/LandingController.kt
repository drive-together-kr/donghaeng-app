package app.donghaeng.common.web

import app.donghaeng.common.config.DhProperties
import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.util.HtmlUtils

/**
 * QR 웹 폴백 (docs/07 §5). 앱이 없는 폰 기본 카메라로 QR 을 찍었을 때 "공식 앱의 동행 메뉴" 안내 페이지를 돌려준다.
 * companion 모듈을 내장한 공식 앱(또는 demo-host)의 스캐너가 같은 URL 을 읽으면 경로(`/pair`, `/j/`)로 종류를 판별한다(docs/07 §5).
 * 도메인 비의존 — 서버 내장 정적 HTML, 개인정보 없음. 사용자 입력(코드·토큰)은 모두 HTML 이스케이프한다.
 */
@Controller
class LandingController(private val props: DhProperties) {

    @GetMapping("/pair", produces = [TEXT_HTML_UTF8])
    @ResponseBody
    fun pair(@RequestParam(name = "c", required = false) userCode: String?): String =
        page(
            title = "차량 연결",
            detail = userCode?.takeIf { it.isNotBlank() }?.let { "연결 코드 <code>${HtmlUtils.htmlEscape(it)}</code>" },
            appPath = userCode?.takeIf { it.isNotBlank() }?.let { "/pair?c=" + java.net.URLEncoder.encode(it, Charsets.UTF_8) },
        )

    @GetMapping("/j/{token}", produces = [TEXT_HTML_UTF8])
    @ResponseBody
    fun join(@PathVariable token: String): String =
        page(
            title = "그룹 합류",
            detail = "초대 코드 <code>${HtmlUtils.htmlEscape(token)}</code>",
            appPath = "/j/" + java.net.URLEncoder.encode(token, Charsets.UTF_8),
        )

    /**
     * Android App Links 확인 파일 — 폰 앱(동행 메뉴를 넣은 공식 앱, 시연은 demo-host)이 이 서버의 `/j/`·`/pair` 주소를 직접 연다(카메라 없이 메신저 링크로 합류·등록).
     * 앱 패키지·인증서 지문은 공개 값이다(dh.app-links).
     */
    @GetMapping("/.well-known/assetlinks.json", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun assetLinks(): String {
        val pkg = props.appLinks.packageName ?: return "[]"
        val fps = props.appLinks.sha256.joinToString(",") { "\"$it\"" }
        return """[{"relation":["delegate_permission/common.handle_all_urls"],"target":{"namespace":"android_app","package_name":"$pkg","sha256_cert_fingerprints":[$fps]}}]"""
    }

    /** 브라우저에서 앱 열기 — Chrome 의 intent: 링크(앱이 없으면 아무 일도 없고 안내 문구가 남는다) */
    private fun openInAppHref(appPath: String): String? {
        val pkg = props.appLinks.packageName ?: return null
        val base = java.net.URI(props.publicBaseUrl)
        val hostPort = base.host + (if (base.port > 0) ":" + base.port else "")
        return "intent://$hostPort$appPath#Intent;scheme=${base.scheme};package=$pkg;end"
    }

    private fun page(title: String, detail: String?, appPath: String? = null): String {
        val open = appPath?.let(::openInAppHref)?.let { "<p><a class=\"btn\" href=\"${HtmlUtils.htmlEscape(it)}\">앱에서 열기</a></p>" } ?: ""
        return """
        |<!DOCTYPE html>
        |<html lang="ko">
        |<head>
        |<meta charset="utf-8">
        |<meta name="viewport" content="width=device-width, initial-scale=1">
        |<title>동행 — $title</title>
        |<style>
        |body{font-family:sans-serif;margin:0;padding:32px 20px;color:#1b1b1f;background:#f6f7fb}
        |main{max-width:480px;margin:0 auto;text-align:center}
        |h1{font-size:1.5rem;margin:0 0 12px}
        |p{line-height:1.6}
        |code{background:#e8ebf3;padding:2px 6px;border-radius:4px}
        |.hint{color:#5a5f6e;font-size:.9rem}
        |.btn{display:inline-block;background:#05141f;color:#fff;padding:14px 28px;border-radius:12px;text-decoration:none;font-weight:600}
        |</style>
        |</head>
        |<body>
        |<main>
        |<h1>동행 — $title</h1>
        |<p>블루링크·마이현대·기아 커넥트·제네시스 앱의 '동행' 메뉴에서 이 QR을 스캔해 주세요.</p>
        |${detail?.let { "<p>$it</p>" } ?: ""}
        |$open
        |<p class="hint">공식 앱을 연 뒤 '동행' 메뉴의 QR 스캔으로 같은 QR을 다시 찍어 주세요. (데모: 동행 데모 호스트 앱)</p>
        |</main>
        |</body>
        |</html>
        |""".trimMargin()
    }

    companion object {
        private const val TEXT_HTML_UTF8 = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8"
    }
}
