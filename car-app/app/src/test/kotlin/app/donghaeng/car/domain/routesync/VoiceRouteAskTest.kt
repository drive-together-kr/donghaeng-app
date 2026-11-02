package app.donghaeng.car.domain.routesync

import app.donghaeng.car.domain.ptt.PttMicGate
import app.donghaeng.car.domain.ptt.YesNo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A034: 주행 중 경로 제안 음성 질문 — 읽은 뒤에만 듣고, 물을 수 없으면 null, 무전이 시작되면 듣기를 멈춘다 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRouteAskTest {

    private val events = mutableListOf<String>()
    private val pttBusy = MutableStateFlow(false)

    private suspend fun ask(enabled: Boolean = true, candidate: Boolean = true, mic: Boolean = true, listen: suspend () -> YesNo?) = VoiceRouteAsk.ask(
        question = "따라갈까요? 네 또는 아니요로 답해 주세요.",
        voiceEnabled = { enabled },
        pttBusy = pttBusy,
        pttBusyNow = { pttBusy.value },
        hasCandidate = { candidate },
        micUsable = { mic },
        speakAndWait = { events += "speak" },
        listen = { events += "listen"; listen() },
    )

    @Test
    fun `speaks first then listens, yes and no map to answers`() = runTest {
        assertEquals(VoiceAnswer.YES, ask { YesNo.YES })
        assertEquals(listOf("speak", "listen"), events)
        assertEquals(VoiceAnswer.NO, ask { YesNo.NO })
        assertEquals(VoiceAnswer.NO_ANSWER, ask { YesNo.NONE })
    }

    @Test
    fun `does not ask when voice is off, ptt is busy or no recognizer can listen`() = runTest {
        assertNull(ask(enabled = false) { YesNo.YES })
        assertNull(ask(candidate = false) { YesNo.YES })
        pttBusy.value = true
        assertNull(ask { YesNo.YES })
        assertTrue("묻지 않으면 읽지도 않는다", events.isEmpty())
    }

    /** RA-4: 백그라운드(순정 내비가 앞)에 microphone 타입 없이 시작된 그룹 서비스 — 마이크가 무음이라 '네'라고 해도 못 듣는다 → 묻지 않는다 */
    @Test
    fun `does not ask when the microphone would record silence (background without a microphone service)`() = runTest {
        assertNull(ask(mic = false) { YesNo.YES })
        assertTrue("묻지 않으면 읽지도 듣지도 않는다", events.isEmpty())
    }

    /**
     * RA-4 배선(di/SignalsModule): 경로 질문은 무전과 같은 [PttMicGate.usable] 로 마이크를 판정한다(배치 A 의 임시 판정 `routeAskMicUsable` 대체).
     * 판정이 예전과 같은지 고정 — API 30 미만·동행 화면이 보임·서비스에 microphone 타입 → 쓸 수 있음, 그 밖(서비스 없음·타입 모름 포함) → 못 씀.
     * SignalsModule 을 거치지 않고 그 모듈이 부르는 형태(`startedWhileVisible` 미전달) 그대로 [PttMicGate.usable] 를 본다 — 배선 자체는 SignalsModule 의
     * 한 줄이다. RB-6(API 30~33 백그라운드 시작) 분기는 그룹 서비스가 `startedWhileVisible` 을 내보낼 때까지 무전·경로 질문 모두 쓰이지 않는다(docs/05 B015).
     */
    @Test
    fun `RA-4 - PttMicGate called as SignalsModule does (no startedWhileVisible) matches the batch A rule`() {
        val mic = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val location = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val dataSync = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        assertTrue(PttMicGate.usable(29, appVisible = false, attachedFgsType = null))
        assertTrue(PttMicGate.usable(33, appVisible = true, attachedFgsType = null))
        assertTrue(PttMicGate.usable(33, appVisible = false, attachedFgsType = location or mic))
        assertTrue("서비스 없음", !PttMicGate.usable(33, appVisible = false, attachedFgsType = null))
        assertTrue("백그라운드 시작 — microphone 빠짐", !PttMicGate.usable(33, appVisible = false, attachedFgsType = location or dataSync))
        // 배치 A 의 임시 판정(옮기기 전 SignalsModule.routeAskMicUsable)과 모든 조합에서 같다 — SignalsModule 은 startedWhileVisible 를 넘기지 않는다
        fun batchA(sdk: Int, visible: Boolean, type: Int?): Boolean = when {
            sdk < 30 -> true
            visible -> true
            type == null -> false
            else -> type and mic != 0
        }
        for (sdk in listOf(28, 29, 30, 31, 33, 34, 35)) for (visible in listOf(true, false)) {
            for (type in listOf(null, 0, location, dataSync, mic, location or mic, location or dataSync, dataSync or mic)) {
                assertEquals("sdk=$sdk visible=$visible type=$type", batchA(sdk, visible, type), PttMicGate.usable(sdk, appVisible = visible, attachedFgsType = type))
            }
        }
    }

    @Test
    fun `a ptt press while listening stops listening (no answer)`() = runTest {
        val result = async { ask { awaitCancellation() } }
        runCurrent()
        assertEquals(listOf("speak", "listen"), events)
        pttBusy.value = true
        runCurrent()
        assertEquals(VoiceAnswer.NO_ANSWER, result.await())
    }
}
