package app.donghaeng.car.domain.routesync

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

    private suspend fun ask(enabled: Boolean = true, candidate: Boolean = true, listen: suspend () -> YesNo?) = VoiceRouteAsk.ask(
        question = "따라갈까요? 네 또는 아니요로 답해 주세요.",
        voiceEnabled = { enabled },
        pttBusy = pttBusy,
        pttBusyNow = { pttBusy.value },
        hasCandidate = { candidate },
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
