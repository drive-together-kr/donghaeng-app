package app.donghaeng.protocol.rest

import app.donghaeng.protocol.DhJson
import app.donghaeng.protocol.GroupTopics
import app.donghaeng.protocol.Place
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** REST DTO 왕복 + docs/03 §1~§2 예시 JSON 디코드. */
class RestDtosTest {

    private val json = DhJson.json

    private inline fun <reified T> roundTrip(value: T): T {
        val s: KSerializer<T> = serializer()
        val text = json.encodeToString(s, value)
        val back = json.decodeFromString(s, text)
        assertEquals(value, back, text)
        return back
    }

    @Test
    fun `모든 DTO 가 JSON 왕복에서 동일하다`() {
        roundTrip(LoginRequest(email = "user@example.com", displayName = "홍길동"))
        roundTrip(ExchangeRequest("bluelink", "host-token"))
        roundTrip(RefreshRequest("opaque"))
        roundTrip(TokenResponse("jwt", "opaque", UserDto("usr_01H", "홍길동")))
        roundTrip(DeviceCodeRequest("CARINFO-ID", "GRANDEUR", "HYUNDAI", "0.1.0"))
        roundTrip(DeviceCodeResponse("dc_8f3", "KQ74MZ", "http://10.0.2.2:8080/pair?c=KQ74MZ", 600, 3))
        roundTrip(DeviceTokenRequest("dc_8f3"))
        roundTrip(
            DeviceTokenResponse(
                "jwt", "opaque",
                VehicleSummary("veh_01H", "홍길동 차", "usr_01H"),
                MqttInfo("tcp://10.0.2.2:1883", "veh_01H", "veh_01H"),
            ),
        )
        roundTrip(DeviceApproveRequest("KQ74MZ", "홍길동 차"))
        roundTrip(MyVehicleDto("veh_01H", "내 차", "KIA", "EV9", true))
        roundTrip(VehiclePatchRequest(nickname = "새 별명"))
        roundTrip(GroupCreateRequest("추석 귀성길", Place(35.1595454, 126.8526012, "광주 할머니댁")))
        roundTrip(GroupCreateResponse("grp_01H", "veh_01H", InviteDto("inv_x", "http://10.0.2.2:8080/j/inv_x", 1793000000000), GroupTopics("dh/v1/group/grp_01H/state", "dh/v1/group/grp_01H/event")))
        roundTrip(GroupJoinRequest("inv_x"))
        roundTrip(GroupJoinResponse("grp_01H", "추석 귀성길", listOf(MemberDto("veh_01H", "아빠 차", "#2E7DFF", "LEADER", 1793000000000, "KIA", "EV9"))))
        roundTrip(GroupDetailResponse("grp_01H", "추석 귀성길", "veh_01H", "ACTIVE"))
        roundTrip(MyGroupDto("grp_01H", "추석 귀성길", "veh_01H", "ACTIVE", Place(35.1, 126.8, "광주"), 2, listOf("veh_02H"), 1793000000000))
        roundTrip(MyGroupDto("grp_01H", "추석 귀성길", "veh_01H", "ENDED", null, 1, emptyList(), 1793000000000, 1793003600000))
        roundTrip(GroupLeaveRequest())
        roundTrip(GroupLeaveRequest("veh_02H"))
        roundTrip(PassengerMessageRequest("앞에 휴게소 들르자", "veh_01H"))
        roundTrip(LeaderRequest("veh_02H"))
        roundTrip(ErrorEnvelope(ErrorBody("INVITE_EXPIRED", "초대 코드가 만료됐어요.", "abc")))
    }

    @Test
    fun `docs-03 예시 JSON 이 디코드된다`() {
        val login = json.decodeFromString<TokenResponse>(
            """{ "accessToken": "jwt", "refreshToken": "opaque", "user": { "userId": "usr_01H...", "displayName": "홍길동" } }""",
        )
        assertEquals("usr_01H...", login.user.userId)

        val code = json.decodeFromString<DeviceCodeResponse>(
            """{ "deviceCode": "dc_8f3...", "userCode": "KQ7-4MZ", "verifyUri": "http://10.0.2.2:8080/pair?c=KQ74MZ", "expiresIn": 600, "interval": 3 }""",
        )
        assertEquals(3, code.interval)

        val token = json.decodeFromString<DeviceTokenResponse>(
            """{
              "vehicleAccessToken": "jwt(sub=veh_..., ownerId=usr_...)",
              "refreshToken": "opaque",
              "vehicle": { "vehicleId": "veh_01H...", "nickname": "홍길동 차", "ownerId": "usr_01H..." },
              "mqtt": { "uri": "tcp://10.0.2.2:1883", "clientId": "veh_01H...", "username": "veh_01H..." }
            }""",
        )
        assertEquals("veh_01H...", token.mqtt.username)

        val created = json.decodeFromString<GroupCreateResponse>(
            """{
              "groupId": "grp_01H...",
              "leaderVehicleId": "veh_01H...",
              "invite": { "token": "inv_...", "url": "http://10.0.2.2:8080/j/inv_...", "expiresAt": 1793000000000 },
              "mqttTopics": { "state": "dh/v1/group/grp_01H/state", "event": "dh/v1/group/grp_01H/event" }
            }""",
        )
        assertEquals(1793000000000L, created.invite.expiresAt)

        val err = json.decodeFromString<ErrorEnvelope>(
            """{ "error": { "code": "INVITE_EXPIRED", "message": "초대 코드가 만료됐어요.", "traceId": "..." } }""",
        )
        assertEquals(ErrorCodes.INVITE_EXPIRED, err.error.code)
    }

    @Test
    fun `생략 가능한 필드는 빠져도 디코드되고 null 은 인코딩에서 생략된다`() {
        val req = json.decodeFromString<DeviceCodeRequest>("""{"vehicleId":"X"}""")
        assertNull(req.model)
        val text = json.encodeToString(DeviceCodeRequest.serializer(), req)
        assertTrue("model" !in text, text)
        val join = json.decodeFromString<GroupJoinRequest>("""{"inviteToken":"inv_x","extra":1}""")
        assertNull(join.vehicleId)
        // 나가기 본문은 비어 있어도 된다 (`{}`), 그룹 요약의 endedAt 은 ACTIVE 면 생략된다
        assertNull(json.decodeFromString<GroupLeaveRequest>("{}").vehicleId)
        val summary = json.encodeToString(MyGroupDto.serializer(), MyGroupDto("grp_1", "n", "veh_1", "ACTIVE", null, 1, listOf("veh_1"), 1L))
        assertTrue("endedAt" !in summary && "destination" !in summary, summary)
    }

    @Test
    fun `경로 헬퍼는 docs-03 경로와 QR 규칙을 만든다`() {
        assertEquals("/v1/auth/login", ApiPaths.AUTH_LOGIN)
        assertEquals("/v1/device/token", ApiPaths.DEVICE_TOKEN)
        assertEquals("/v1/me/vehicles/veh_1", ApiPaths.meVehicle("veh_1"))
        assertEquals("/v1/groups/grp_1/messages", ApiPaths.groupMessages("grp_1"))
        assertEquals("http://10.0.2.2:8080/pair?c=KQ74MZ", ApiPaths.pairUrl("http://10.0.2.2:8080/", "KQ74MZ"))
        assertEquals("https://1-2-3-4.sslip.io/j/inv_abc", ApiPaths.joinUrl("https://1-2-3-4.sslip.io", "inv_abc"))
    }
}
