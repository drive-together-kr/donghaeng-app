package app.donghaeng.companion.demo

import app.donghaeng.companion.demo.donghaeng.EventTimes
import app.donghaeng.companion.demo.donghaeng.MessageKeys
import app.donghaeng.companion.demo.donghaeng.NotConfiguredGateway
import app.donghaeng.companion.demo.donghaeng.toUserText
import app.donghaeng.companion.demo.ui.Routes
import app.donghaeng.companion.sdk.DongHaengException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** 작은 규칙들 — D026(설치별 이메일)·D029(복원 가드·미설정 게이트웨이)·D083(이벤트 시각)·D085(메시지 키)·D077(다른 서버 문구) */
class DemoHostMiscTest {

    @Test
    fun `D026 - 설치마다 judge-8hex@dev_local, 기본 공유 계정(rider01)이 아니다`() {
        val a = DemoIdentity.newJudgeEmail(Random(1))
        val b = DemoIdentity.newJudgeEmail(Random(2))
        assertTrue(a, DemoIdentity.isGeneratedEmail(a))
        assertNotEquals(a, b)
        assertFalse(DemoIdentity.isGeneratedEmail("rider01@dev.local"))
    }

    @Test
    fun `D026 - 초기화 전에도 같은 프로세스 안에서는 같은 기본 이메일, 로그인한 이메일을 기억한다`() {
        val first = DemoPrefs.loginEmail()
        assertEquals(first, DemoPrefs.loginEmail())
        DemoPrefs.rememberLoginEmail("me@dev.local")
        assertEquals("me@dev.local", DemoPrefs.loginEmail())
    }

    @Test
    fun `D029 - 로그인 없이 복원된 화면은 로그인으로 되돌린다`() {
        assertTrue(Routes.needsLoginRedirect(loggedIn = false, route = Routes.MAIN))
        assertTrue(Routes.needsLoginRedirect(loggedIn = false, route = Routes.SCAN))
        assertFalse(Routes.needsLoginRedirect(loggedIn = false, route = Routes.LOGIN))
        assertFalse(Routes.needsLoginRedirect(loggedIn = true, route = Routes.MAIN))
        assertFalse("아직 그래프 없음", Routes.needsLoginRedirect(loggedIn = false, route = null))
    }

    @Test
    fun `D029 - 설정 전 게이트웨이는 가짜 데이터가 아니라 NotInitialized`() = runTest {
        val gw = NotConfiguredGateway()
        assertTrue(runCatching { gw.joinGroup("http://10.0.2.2:8080/j/inv_x", null) }.exceptionOrNull() is DongHaengException.NotInitialized)
        assertTrue(runCatching { gw.pairVehicle("http://10.0.2.2:8080/pair?c=KQ74MZ", "차") }.exceptionOrNull() is DongHaengException.NotInitialized)
        assertTrue(runCatching { gw.listMyVehicles() }.exceptionOrNull() is DongHaengException.NotInitialized)
        assertTrue(runCatching { gw.observeGroup("grp_1").first() }.exceptionOrNull() is DongHaengException.NotInitialized)
        assertEquals(null, gw.previewInvite("http://10.0.2.2:8080/j/inv_x"))
    }

    /** 서버 UlidGenerator 와 같은 방식(Crockford Base32, 앞 10자 = ms)으로 독립 인코딩 */
    private fun ulidAt(ms: Long): String {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val out = CharArray(10)
        var t = ms
        for (i in 9 downTo 0) {
            out[i] = alphabet[(t % 32).toInt()]
            t /= 32
        }
        return String(out) + "7ZZZZZZZZZZZZZZZ"
    }

    @Test
    fun `D083 - 알림 시각은 서버 이벤트 ID(ULID)의 생성 시각, 모르면 받은 시각`() {
        val created = 1_793_000_000_000L
        val received = created + 5 * 60 * 1000L
        assertEquals(created, EventTimes.atMs("evt_" + ulidAt(created), received))
        assertEquals("시연 ID", received, EventTimes.atMs("evt_demo_0001_3", received))
        assertEquals("폰 시계가 늦어 미래면 받은 시각", received, EventTimes.atMs("evt_" + ulidAt(received + 60_000), received))
        assertEquals("ULID 문자가 아님", received, EventTimes.atMs("evt_" + ulidAt(created).replace('0', 'U'), received))
    }

    @Test
    fun `D085 - 같은 문장의 재시도는 같은 키, 문장을 바꾸거나 보낸 뒤 다시 보내면 새 키`() {
        var seq = 0
        val keys = MessageKeys(newKey = { "key_${++seq}_padding" })
        val k1 = keys.keyFor("배고파요")
        assertEquals("타임아웃 뒤 다시 누름", k1, keys.keyFor("배고파요"))
        val k2 = keys.keyFor("천천히 와")
        assertNotEquals(k1, k2)
        keys.sent("천천히 와")
        assertNotEquals("전달된 뒤 같은 문장을 또 보내면 새 메시지", k2, keys.keyFor("천천히 와"))
    }

    @Test
    fun `D077 - 다른 서버 QR 문구에 이 앱의 서버 주소를 함께 보인다`() {
        val text = DongHaengException.OtherServer("10.0.2.2:8080").toUserText(publicBaseUrl = "https://1-2-3-4.sslip.io/")
        assertTrue(text, text.contains("10.0.2.2:8080") && text.contains("https://1-2-3-4.sslip.io"))
    }
}
