package app.donghaeng.car.adapter.trigger

import android.media.session.PlaybackState
import app.donghaeng.car.domain.ptt.InMemoryPttSettingsStore
import app.donghaeng.car.domain.ptt.PttSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 무전 MediaSession 재생 상태 고정 (F16, 2026-10-28 실측 — PLAYING 이면 Pleos 미디어 프레임워크가 동행을 '재생 중' 미디어로 잡는다).
 * 근거 표는 [PttSessionPlayback] KDoc. 이 테스트가 깨지면 표를 다시 재고 같이 고친다.
 */
class PttSessionPlaybackTest {

    @Test
    fun `세션 재생 상태는 PLAYING 이 아니라 PAUSED — 속도 0`() {
        assertNotEquals("PLAYING 이면 MediaDataProvider·GleoMediaSession 이 isPlaying=true 로 잡는다", PlaybackState.STATE_PLAYING, PttSessionPlayback.STATE)
        assertEquals(PlaybackState.STATE_PAUSED, PttSessionPlayback.STATE)
        assertEquals(0f, PttSessionPlayback.SPEED, 0f)
    }

    /**
     * 메타데이터(제목·앨범 아트)를 세션에 넣으면 Pleos 미디어 위젯에 항목이 생긴다. 단위 테스트에는 실제 MediaSession 이 없으므로
     * 출시 소스(main·pleos·fake — debug 프로브는 제외)에 메타데이터를 넣는 호출이 없는지 소스를 직접 본다.
     */
    @Test
    fun `출시 소스 어디에도 미디어 세션 메타데이터를 넣는 코드가 없다`() {
        val roots = releaseSourceRoots()
        val banned = listOf("setMetadata(", "MediaMetadata.Builder", "MediaMetadataCompat.Builder")
        val hits = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.flatMap { f ->
                f.readLines().withIndex()
                    .filter { (_, line) -> !isCommentLine(line) && banned.any { line.contains(it) } }
                    .map { (i, _) -> "${f.relativeTo(root.parentFile.parentFile).path}:${i + 1}" }
            }
        }
        assertTrue("메타데이터를 넣으면 미디어 위젯에 동행이 보인다 — PttSessionPlayback KDoc·실측 표 확인: $hits", hits.isEmpty())
        assertTrue(
            "MediaSessionVoiceTrigger 를 못 찾음 — 소스 경로가 바뀌었으면 이 테스트도 고친다",
            roots.any { File(it, "app/donghaeng/car/adapter/trigger/MediaSessionVoiceTrigger.kt").isFile },
        )
    }

    /** KDoc·주석 줄은 설명일 뿐이라 건너뛴다 */
    private fun isCommentLine(line: String): Boolean = line.trimStart().let { it.startsWith("*") || it.startsWith("//") || it.startsWith("/*") }

    /** 테스트 작업 폴더(Gradle 은 car-app/app, IDE 설정에 따라 car-app) 기준 출시 소스 폴더 */
    private fun releaseSourceRoots(): List<File> {
        val base = listOf(File("src"), File("app/src")).firstOrNull { File(it, "main/kotlin").isDirectory }
            ?: throw AssertionError("src/main/kotlin 을 찾지 못함(작업 폴더 ${File("").absoluteFile.name})")
        return listOf("main", "pleos", "fake").map { File(base, "$it/kotlin") }.filter { it.isDirectory }
    }

    @Test
    fun `세션에서 다시 읽은 값으로 로그를 만든다 — PAUSED·0·메타데이터 없음이면 노출 아님`() {
        val ok = PttSessionPlayback.Applied(PlaybackState.STATE_PAUSED, 0f, false)
        assertEquals("state=PAUSED speed=0.0 metadata=none", ok.describe())
        assertEquals("PAUSED", ok.stateLabel())
        assertFalse(ok.exposedAsMedia())
    }

    @Test
    fun `PLAYING 이거나 메타데이터가 있으면 노출로 보고 로그도 그대로 적는다`() {
        val playing = PttSessionPlayback.Applied(PlaybackState.STATE_PLAYING, 1f, false)
        assertEquals("state=PLAYING speed=1.0 metadata=none", playing.describe())
        assertTrue(playing.exposedAsMedia())
        val withMeta = PttSessionPlayback.Applied(PlaybackState.STATE_PAUSED, 0f, true)
        assertEquals("state=PAUSED speed=0.0 metadata=set", withMeta.describe())
        assertTrue(withMeta.exposedAsMedia())
    }

    @Test
    fun `못 읽은 항목은 물음표 — 상수로 채우지 않는다`() {
        val unknown = PttSessionPlayback.Applied(null, null, null)
        assertEquals("state=? speed=? metadata=?", unknown.describe())
        assertEquals("?", unknown.stateLabel())
        assertFalse(unknown.exposedAsMedia())
    }

    @Test
    fun `미디어 버튼을 받는 동작은 그대로 — PLAY_PAUSE·PLAY·PAUSE`() {
        val a = PttSessionPlayback.ACTIONS
        assertTrue(a and PlaybackState.ACTION_PLAY_PAUSE != 0L)
        assertTrue(a and PlaybackState.ACTION_PLAY != 0L)
        assertTrue(a and PlaybackState.ACTION_PAUSE != 0L)
        assertEquals("다음 곡·이전 곡은 받지 않는다", 0L, a and (PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS))
    }

    @Test
    fun `상태 이름`() {
        assertEquals("PAUSED", PttSessionPlayback.stateName(PlaybackState.STATE_PAUSED))
        assertEquals("PLAYING", PttSessionPlayback.stateName(PlaybackState.STATE_PLAYING))
        assertEquals("STOPPED", PttSessionPlayback.stateName(PlaybackState.STATE_STOPPED))
        assertEquals("NONE", PttSessionPlayback.stateName(PlaybackState.STATE_NONE))
        assertEquals("STATE_6", PttSessionPlayback.stateName(6))
    }

    @Test
    fun `트리거 상태 줄 — 세션이 켜지면 sessionState=PAUSED, 꺼지면 -`() {
        val t = FakeVoiceTrigger(PttSettings(InMemoryPttSettingsStore(true)), clock = { 0L })
        t.start()
        assertNull(t.status.value.sessionState)
        t.setGroupActive(true)
        assertEquals("PAUSED", t.status.value.sessionState)
        assertTrue(t.status.value.toString().contains("session=true sessionState=PAUSED"))
        t.setGroupActive(false)
        assertTrue(t.status.value.toString().contains("session=false sessionState=-"))
    }
}
