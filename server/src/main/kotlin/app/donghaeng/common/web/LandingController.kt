package app.donghaeng.common.web

import org.springframework.http.MediaType
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.util.HtmlUtils

/**
 * QR 웹 폴백 (docs/07 §4). 앱이 없는 폰 기본 카메라로 QR 을 찍었을 때 "앱 설치하기" 안내 페이지를 돌려준다.
 * 폰 앱이 설치돼 있으면 같은 URL 을 딥링크로 받아 경로(`/pair`, `/j/`)로 종류를 판별한다.
 * 도메인 비의존 — 서버 내장 정적 HTML, 개인정보 없음. 사용자 입력(코드·토큰)은 모두 HTML 이스케이프한다.
 */
@Controller
class LandingController {

    @GetMapping("/pair", produces = [TEXT_HTML_UTF8])
    @ResponseBody
    fun pair(@RequestParam(name = "c", required = false) userCode: String?): String =
        page(
            title = "차량 연결",
            detail = userCode?.takeIf { it.isNotBlank() }?.let { "연결 코드 <code>${HtmlUtils.htmlEscape(it)}</code>" },
        )

    @GetMapping("/j/{token}", produces = [TEXT_HTML_UTF8])
    @ResponseBody
    fun join(@PathVariable token: String): String =
        page(
            title = "그룹 합류",
            detail = "초대 코드 <code>${HtmlUtils.htmlEscape(token)}</code>",
        )

    private fun page(title: String, detail: String?): String = """
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
        |</style>
        |</head>
        |<body>
        |<main>
        |<h1>동행 — $title</h1>
        |<p>동행 앱을 설치하면 이 QR 로 바로 연결돼요</p>
        |${detail?.let { "<p>$it</p>" } ?: ""}
        |<p class="hint">앱을 설치한 뒤 같은 QR 을 다시 찍어 주세요.</p>
        |</main>
        |</body>
        |</html>
        |""".trimMargin()

    companion object {
        private const val TEXT_HTML_UTF8 = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8"
    }
}
