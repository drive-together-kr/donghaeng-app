package app.donghaeng.mqtt.web

import app.donghaeng.common.config.DhProperties
import app.donghaeng.mqtt.MqttAclService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * EMQX 5 HTTP 인가 소스 (docs/06 §5 "그룹 토픽은 서버가 HTTP ACL 훅으로 판정").
 *
 * 요청: `POST /internal/mqtt/acl` JSON `{ username, clientid, topic, action: "publish"|"subscribe" }` (compose 의 body 템플릿).
 * 응답: 200 `{ "result": "allow" | "deny" }`. 오류나 잘못된 요청은 `deny` 로 답한다(브로커의 `no_match = deny` 와 함께 기본 거부).
 * `dh.internal-token` 이 설정돼 있으면 `X-Internal-Token` 이 같아야 한다(아니면 403 — EMQX 는 이를 ignore 로 보고 no_match 규칙으로 거부).
 */
@RestController
class MqttAclController(
    private val acl: MqttAclService,
    private val props: DhProperties,
) {
    data class AclRequest(
        val username: String? = null,
        val clientid: String? = null,
        val topic: String? = null,
        val action: String? = null,
    )

    data class AclResponse(val result: String)

    @PostMapping("/internal/mqtt/acl")
    fun decide(
        @RequestHeader(name = "X-Internal-Token", required = false) token: String?,
        @RequestBody req: AclRequest,
    ): ResponseEntity<AclResponse> {
        val expected = props.internalToken
        if (expected != null && token != expected) {
            log.warn("acl hook rejected: bad internal token username={}", req.username)
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(AclResponse("deny"))
        }
        val action = when (req.action?.lowercase()) {
            "publish" -> MqttAclService.Action.PUBLISH
            "subscribe" -> MqttAclService.Action.SUBSCRIBE
            else -> return ResponseEntity.ok(AclResponse("deny"))
        }
        val decision = acl.decide(req.username, action, req.topic)
        if (!decision.allow) log.info("acl deny username={} action={} topic={} reason={}", req.username, req.action, req.topic, decision.reason)
        else if (log.isDebugEnabled) log.debug("acl allow username={} action={} topic={}", req.username, req.action, req.topic)
        return ResponseEntity.ok(AclResponse(if (decision.allow) "allow" else "deny"))
    }

    companion object {
        private val log = LoggerFactory.getLogger(MqttAclController::class.java)
    }
}
