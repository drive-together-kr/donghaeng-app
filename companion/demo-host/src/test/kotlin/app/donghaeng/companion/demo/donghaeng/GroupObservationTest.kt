package app.donghaeng.companion.demo.donghaeng

import app.donghaeng.companion.sdk.DongHaengException
import app.donghaeng.companion.sdk.GroupSnapshot
import app.donghaeng.protocol.GroupEvent
import app.donghaeng.protocol.GroupEventType
import app.donghaeng.protocol.Severity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D021·D022·D028 — 일시 오류는 백오프로 다시 구독하고, 종료·나감·인증 만료는 한 번 알리고 끝낸다 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupObservationTest {

    private fun snap(vararg types: GroupEventType) = GroupSnapshot(
        groupId = "grp_1",
        members = emptyList(),
        events = types.mapIndexed { i, t -> GroupEvent("evt_$i", t.name, null, Severity.INFO, null, JsonObject(emptyMap())) },
    )

    /** 호출 순서대로 흐름을 돌려주는 가짜 모듈 */
    private class Script(vararg flows: () -> Flow<GroupSnapshot>) {
        private val queue = ArrayDeque(flows.toList())
        var calls = 0
        fun next(): Flow<GroupSnapshot> {
            calls++
            return queue.removeFirstOrNull()?.invoke() ?: flow { awaitCancellation() }
        }
    }

    private val network = DongHaengException.Api("NETWORK", "서버에 연결할 수 없어요")

    @Test
    fun `네트워크 오류 한 번이면 끝나지 않고 백오프 뒤 다시 구독해 스냅샷을 이어 받는다`() = runTest {
        val script = Script({ flow { throw network } }, { flow { emit(snap()); awaitCancellation() } })

        val start = currentTime
        val events = GroupObservation.observe(script::next).take(2).toList()

        assertTrue(events[0] is ObserveEvent.Retrying)
        assertEquals(1, (events[0] as ObserveEvent.Retrying).attempt)
        assertTrue(events[1] is ObserveEvent.Snapshot)
        assertEquals(2, script.calls)
        assertEquals("첫 재시도는 2 s 뒤", 2_000L, currentTime - start)
    }

    @Test
    fun `5xx·429·응답 해석 실패도 일시 오류, 연속 실패는 대기가 늘고 스냅샷을 받으면 처음부터`() = runTest {
        val script = Script(
            { flow { throw DongHaengException.Api("HTTP_502", "bad gateway") } },
            { flow { throw DongHaengException.Api("HTTP_429", "slow") } },
            { flow { emit(snap()); throw DongHaengException.Api("BAD_RESPONSE", "x") } },
            { flow { throw DongHaengException.Api("INTERNAL_ERROR", "x") } },
        )

        val events = GroupObservation.observe(script::next).take(5).toList()

        val attempts = events.map { (it as? ObserveEvent.Retrying)?.attempt }
        assertEquals(listOf(1, 2, null, 1, 2), attempts)
        assertEquals(listOf(2_000L, 4_000L, 2_000L, 4_000L), events.mapNotNull { (it as? ObserveEvent.Retrying)?.delayMs })
    }

    @Test
    fun `404 NOT_FOUND 는 다시 구독하지 않고 NOT_MEMBER 로 끝난다`() = runTest {
        val script = Script({ flow { throw DongHaengException.Api("NOT_FOUND", "그룹을 찾을 수 없어요.") } })
        val events = GroupObservation.observe(script::next).toList()
        assertEquals(1, events.size)
        assertEquals(StopReason.NOT_MEMBER, (events.single() as ObserveEvent.Stopped).reason)
        assertEquals(1, script.calls)
    }

    @Test
    fun `인증 만료는 UNAUTHORIZED, 403 같은 오류는 FAILED 로 끝난다`() = runTest {
        val unauthorized = GroupObservation.observe(Script({ flow { throw DongHaengException.Unauthorized() } })::next).toList()
        assertEquals(StopReason.UNAUTHORIZED, (unauthorized.single() as ObserveEvent.Stopped).reason)

        val forbidden = GroupObservation.observe(Script({ flow { throw DongHaengException.Api("FORBIDDEN", "x") } })::next).toList()
        assertEquals(StopReason.FAILED, (forbidden.single() as ObserveEvent.Stopped).reason)

        val notInit = GroupObservation.observe({ throw DongHaengException.NotInitialized() }).toList()
        assertEquals("흐름을 만들다 던져도 같은 분류", StopReason.FAILED, (notInit.single() as ObserveEvent.Stopped).reason)
    }

    @Test
    fun `GROUP_ENDED 가 보이면 그 스냅샷을 내보내고 ENDED 로 끝난다(모듈이 계속 폴링해도)`() = runTest {
        val script = Script({ flow { emit(snap(GroupEventType.MEMBER_JOINED)); emit(snap(GroupEventType.GROUP_ENDED)); emit(snap()); awaitCancellation() } })

        val events = GroupObservation.observe(script::next).toList()

        assertEquals(3, events.size)
        assertTrue(events[1] is ObserveEvent.Snapshot)
        assertEquals(StopReason.ENDED, (events[2] as ObserveEvent.Stopped).reason)
        assertNull((events[2] as ObserveEvent.Stopped).cause)
    }

    @Test
    fun `모듈 흐름이 정상 종료하면(서버 ENDED — 폴링 멈춤) ENDED`() = runTest {
        val events = GroupObservation.observe({ emptyFlow() }).toList()
        assertEquals(StopReason.ENDED, (events.single() as ObserveEvent.Stopped).reason)
    }

    @Test
    fun `백오프는 2 s 부터 두 배씩 30 s 까지`() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..6).map(GroupObservation::backoffMs))
        assertEquals(30_000L, GroupObservation.backoffMs(100))
    }
}
