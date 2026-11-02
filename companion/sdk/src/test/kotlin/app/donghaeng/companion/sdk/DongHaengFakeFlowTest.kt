package app.donghaeng.companion.sdk

import app.donghaeng.companion.sdk.internal.FakeDongHaengApi
import app.donghaeng.protocol.GroupEventType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 메모리 Fake(`FakeDongHaengApi`)를 internal 훅 [DongHaeng.initWithApi] 로 끼워 공개 API 흐름 전체를 돈다.
 * 공개 [DongHaeng.init] 은 DEV 도 HTTP 전송을 쓰므로(T13) 여기서는 Fake 를 직접 끼운다 — 서버 전송은 HttpDongHaengApiTest.
 */
class DongHaengFakeFlowTest {

    private val config = DongHaengConfig(
        publicBaseUrl = "http://10.0.2.2:8080/", // 끝 슬래시 허용
        provider = AccountProvider.DEV,
        appVersion = "0.1.0-test",
    )
    private val devToken = TokenProvider { "dev:tester@dev.local" }
    private val joinQr = "http://10.0.2.2:8080/j/inv_test_0001"
    private val pairQr = "http://10.0.2.2:8080/pair?c=KQ74MZ"

    /** Fake 를 끼운 초기화 — 설정 검증은 공개 init 과 같다 */
    private fun initFake(cfg: DongHaengConfig = config, token: TokenProvider = devToken) =
        DongHaeng.initWithApi(cfg) { FakeDongHaengApi(token) }

    @Before
    fun setUp() = DongHaeng.reset()

    @After
    fun tearDown() = DongHaeng.reset()

    @Test
    fun `init 후 listMyVehicles 는 개발용 차 2대`() = runTest {
        initFake()
        val vehicles = DongHaeng.listMyVehicles()
        assertEquals(2, vehicles.size)
        assertEquals(1, vehicles.count { it.isPrimary })
    }

    @Test
    fun `joinGroup 은 멤버가 있는 그룹을 돌려준다`() = runTest {
        initFake()
        val result = DongHaeng.joinGroup(joinQr)
        assertTrue(result.groupId.isNotBlank())
        assertEquals(3, result.members.size)
        assertEquals(1, result.members.count { it.role == MemberRole.LEADER })
        // 대표 차량으로 합류
        val primary = DongHaeng.listMyVehicles().first { it.isPrimary }
        assertTrue(result.members.any { it.vehicleId == primary.vehicleId })
    }

    @Test
    fun `joinGroup 에 vehicleId 를 주면 그 차로 합류`() = runTest {
        initFake()
        val second = DongHaeng.listMyVehicles().first { !it.isPrimary }
        val result = DongHaeng.joinGroup("http://10.0.2.2:8080/j/inv_second", vehicleId = second.vehicleId)
        assertTrue(result.members.any { it.vehicleId == second.vehicleId })
    }

    @Test
    fun `observeGroup 첫 발행에 멤버 상태가 있다`() = runTest {
        initFake()
        val joined = DongHaeng.joinGroup(joinQr)
        val snapshot = DongHaeng.observeGroup(joined.groupId).first()
        assertEquals(joined.groupId, snapshot.groupId)
        assertEquals(3, snapshot.members.size)
        snapshot.members.forEach {
            assertNotNull(it.lat)
            assertNotNull(it.lon)
            assertNotNull(it.eta)
            assertNotNull(it.energy)
        }
        // 합류 이벤트가 이미 들어 있다
        assertTrue(snapshot.events.any { it.type == GroupEventType.MEMBER_JOINED.name })
    }

    @Test
    fun `observeGroup 은 틱마다 멤버가 움직인다`() = runTest {
        initFake()
        val joined = DongHaeng.joinGroup(joinQr)
        val snapshots = DongHaeng.observeGroup(joined.groupId).take(3).toList()
        assertEquals(3, snapshots.size)
        val leaderFirst = snapshots.first().members.first()
        val leaderLast = snapshots.last().members.first()
        assertNotEquals(leaderFirst.lat, leaderLast.lat)
    }

    @OptIn(ExperimentalCoroutinesApi::class) // currentTime
    @Test
    fun `LOW_ENERGY 틱(10틱)을 지나도 틱당 한 번만 발행되고 시간이 흐른다 - 무한 루프 회귀`() = runTest {
        initFake()
        val joined = DongHaeng.joinGroup(joinQr)
        // 틱 0..13 → 14번 발행. 버그가 있으면 10틱에서 같은 틱이 즉시 반복 발행되어 가상 시간이 멈춘 채 끝난다
        val snapshots = DongHaeng.observeGroup(joined.groupId).take(14).toList()
        assertEquals(14, snapshots.size)
        assertTrue("가상 시간이 13틱 이상 흘러야 함: $currentTime", currentTime >= 13 * 2_000L)
        val lowEnergyCount = snapshots.last().events.count { it.type == GroupEventType.LOW_ENERGY.name }
        assertEquals(1, lowEnergyCount)
        // 10틱 이후 발행마다 선두차 위치가 계속 바뀐다(같은 틱 반복 아님)
        val latsAfterTen = snapshots.drop(10).map { it.members.first().lat }
        assertEquals(latsAfterTen.size, latsAfterTen.distinct().size)
    }

    @Test
    fun `sendPassengerMessage 는 PASSENGER_MESSAGE 이벤트로 나타난다`() = runTest {
        initFake()
        val joined = DongHaeng.joinGroup(joinQr)
        DongHaeng.sendPassengerMessage(joined.groupId, "앞에 휴게소 들르자")
        val snapshot = DongHaeng.observeGroup(joined.groupId).first()
        val message = snapshot.events.lastOrNull { it.type == GroupEventType.PASSENGER_MESSAGE.name }
        assertNotNull(message)
        assertEquals("앞에 휴게소 들르자", message!!.payload["text"]?.jsonPrimitive?.content)
        assertEquals(GroupEventType.PASSENGER_MESSAGE, message.eventType)
    }

    @Test
    fun `같은 idempotencyKey 로 두 번 보내면 메시지는 하나, fromVehicleId 가 보낸 차가 된다`() = runTest {
        initFake()
        val second = DongHaeng.listMyVehicles().first { !it.isPrimary }
        val joined = DongHaeng.joinGroup("http://10.0.2.2:8080/j/inv_msg", vehicleId = second.vehicleId)
        repeat(2) { DongHaeng.sendPassengerMessage(joined.groupId, "배고파요", fromVehicleId = second.vehicleId, idempotencyKey = "same_key_0001") }
        val messages = DongHaeng.observeGroup(joined.groupId).first().events.filter { it.type == GroupEventType.PASSENGER_MESSAGE.name }
        assertEquals(1, messages.size)
        assertEquals(second.vehicleId, messages.single().sourceVehicleId)
    }

    @Test
    fun `RD-5 - 실패한 요청의 idempotencyKey 로 다시 보내면 처리된다(서버는 성공 응답만 기억한다)`() = runTest {
        initFake()
        val second = DongHaeng.listMyVehicles().first { !it.isPrimary }
        val joined = DongHaeng.joinGroup("http://10.0.2.2:8080/j/inv_retry", vehicleId = second.vehicleId)
        val failed = runCatching {
            DongHaeng.sendPassengerMessage(joined.groupId, "배고파요", fromVehicleId = "veh_wrong", idempotencyKey = "retry_key_0001")
        }.exceptionOrNull()
        assertEquals("NO_PAIRED_VEHICLE", (failed as DongHaengException.Api).code)

        DongHaeng.sendPassengerMessage(joined.groupId, "배고파요", fromVehicleId = second.vehicleId, idempotencyKey = "retry_key_0001")

        val messages = DongHaeng.observeGroup(joined.groupId).first().events.filter { it.type == GroupEventType.PASSENGER_MESSAGE.name }
        assertEquals("고친 재시도는 조용히 무시되지 않고 메시지가 생긴다", 1, messages.size)
        assertEquals(second.vehicleId, messages.single().sourceVehicleId)
    }

    @Test
    fun `init 에 스킴 없는 publicBaseUrl 을 주면 IllegalArgumentException`() = runTest {
        listOf("10.0.2.2:8080", "ftp://10.0.2.2:8080", "   ").forEach { bad ->
            val error = runCatching { initFake(config.copy(publicBaseUrl = bad)) }.exceptionOrNull()
            assertTrue("publicBaseUrl='$bad' → $error", error is IllegalArgumentException)
        }
        // 잘못된 init 은 세션을 만들지 않는다
        val afterError = runCatching { DongHaeng.listMyVehicles() }.exceptionOrNull()
        assertTrue(afterError is DongHaengException.NotInitialized)
        // 대소문자가 섞인 스킴과 끝 슬래시는 허용
        initFake(config.copy(publicBaseUrl = "HTTPS://1-2-3-4.sslip.io/"))
        assertEquals(2, DongHaeng.listMyVehicles().size)
    }

    @Test
    fun `공개 init 도 같은 설정 검증을 한다 - 네트워크 없이`() = runTest {
        listOf("10.0.2.2:8080", "ftp://10.0.2.2:8080", "   ").forEach { bad ->
            val error = runCatching { DongHaeng.init(config.copy(publicBaseUrl = bad), devToken) }.exceptionOrNull()
            assertTrue("publicBaseUrl='$bad' → $error", error is IllegalArgumentException)
        }
        // 올바른 주소면 세션이 만들어진다(교환은 첫 API 호출 때라 여기서는 네트워크를 쓰지 않는다)
        DongHaeng.init(config, devToken)
        val error = runCatching { DongHaeng.joinGroup(pairQr) }.exceptionOrNull()
        assertTrue(error is DongHaengException.InvalidQr) // QR 해석은 전송 전에 끝난다
    }

    @Test
    fun `init 전에 호출하면 NotInitialized`() = runTest {
        val listError = runCatching { DongHaeng.listMyVehicles() }.exceptionOrNull()
        assertTrue(listError is DongHaengException.NotInitialized)
        val joinError = runCatching { DongHaeng.joinGroup(joinQr) }.exceptionOrNull()
        assertTrue(joinError is DongHaengException.NotInitialized)
        val observeError = runCatching { DongHaeng.observeGroup("grp_x") }.exceptionOrNull()
        assertTrue(observeError is DongHaengException.NotInitialized)
    }

    @Test
    fun `joinGroup 에 차량 등록 QR 을 주면 InvalidQr`() = runTest {
        initFake()
        val error = runCatching { DongHaeng.joinGroup(pairQr) }.exceptionOrNull()
        assertTrue(error is DongHaengException.InvalidQr)
    }

    @Test
    fun `다른 서버 QR 은 OtherServer`() = runTest {
        initFake()
        val error = runCatching { DongHaeng.joinGroup("https://9-9-9-9.sslip.io/j/inv_1") }.exceptionOrNull()
        assertTrue(error is DongHaengException.OtherServer)
        assertEquals("9-9-9-9.sslip.io", (error as DongHaengException.OtherServer).host)
    }

    @Test
    fun `pairVehicle 은 내 차를 한 대 늘린다`() = runTest {
        initFake()
        val result = DongHaeng.pairVehicle(pairQr, "시승차")
        assertEquals("시승차", result.nickname)
        val vehicles = DongHaeng.listMyVehicles()
        assertEquals(3, vehicles.size)
        assertTrue(vehicles.any { it.vehicleId == result.vehicleId })
    }

    @Test
    fun `빈 토큰이면 Unauthorized`() = runTest {
        initFake(token = TokenProvider { "" })
        val error = runCatching { DongHaeng.listMyVehicles() }.exceptionOrNull()
        assertTrue(error is DongHaengException.Unauthorized)
    }

    @Test
    fun `모르는 그룹을 observe 하면 Api(GROUP_NOT_FOUND)`() = runTest {
        initFake()
        val error = runCatching { DongHaeng.observeGroup("grp_nope").first() }.exceptionOrNull()
        assertTrue(error is DongHaengException.Api)
        assertEquals("GROUP_NOT_FOUND", (error as DongHaengException.Api).code)
    }
}
