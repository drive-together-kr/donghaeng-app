package app.donghaeng.group.web

import app.donghaeng.group.GroupService
import app.donghaeng.protocol.rest.InvitePreviewResponse
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * 초대 미리보기 (docs/03 §2.2): 그룹 QR 의 URL `{base}/j/{token}` 자체를 `Accept: application/json` 으로 읽으면 그룹명·방장·목적지·인원을 돌려준다.
 * 같은 경로의 `text/html` 은 `common/web/LandingController`(앱 안내 페이지)가 맡는다 — 콘텐츠 협상으로 갈린다.
 * 인증 없음(SecurityConfig 공개 경로 `/j/` 아래): 토큰 자체가 비밀이고, QR 을 찍은 사람은 어차피 합류하면 같은 정보를 본다. 사용 횟수에 포함되지 않는다.
 */
@RestController
class InvitePreviewController(private val groups: GroupService) {

    @GetMapping("/j/{token}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun preview(@PathVariable token: String): InvitePreviewResponse = groups.previewInvite(token)
}
