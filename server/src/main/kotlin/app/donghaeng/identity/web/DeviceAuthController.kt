package app.donghaeng.identity.web

import app.donghaeng.identity.DeviceAuthService
import app.donghaeng.protocol.rest.ApiPaths
import app.donghaeng.protocol.rest.DeviceApproveRequest
import app.donghaeng.protocol.rest.DeviceCodeRequest
import app.donghaeng.protocol.rest.DeviceCodeResponse
import app.donghaeng.protocol.rest.DeviceTokenRequest
import app.donghaeng.protocol.rest.DeviceTokenResponse
import app.donghaeng.protocol.rest.VehicleSummary
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/** docs/03 §1.2 차량 페어링 (Device Authorization Grant, 대체 수단) */
@RestController
class DeviceAuthController(private val devices: DeviceAuthService) {

    /** 차량 앱, 인증 없음 → 200 { deviceCode, userCode, verifyUri, expiresIn 600, interval 3 } */
    @PostMapping(ApiPaths.DEVICE_CODE)
    fun code(@RequestBody req: DeviceCodeRequest): DeviceCodeResponse = devices.issueCode(req)

    /** 차량 앱 폴링, 인증 없음 → 200 / 428 AUTHORIZATION_PENDING / 429 SLOW_DOWN / 410 CODE_EXPIRED / 404 CODE_NOT_FOUND / 409 ALREADY_APPROVED */
    @PostMapping(ApiPaths.DEVICE_TOKEN)
    fun token(@RequestBody req: DeviceTokenRequest): DeviceTokenResponse = devices.poll(req)

    /** companion `pairVehicle`, 사용자 JWT → 200 VehicleSummary / 404 / 410 / 409 */
    @PostMapping(ApiPaths.DEVICE_APPROVE)
    fun approve(@AuthenticationPrincipal jwt: Jwt, @RequestBody req: DeviceApproveRequest): VehicleSummary =
        devices.approve(jwt.requireUserId(), req)
}
