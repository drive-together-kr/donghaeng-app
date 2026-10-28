package app.donghaeng.car.domain.ptt

import app.donghaeng.car.adapter.audio.AudioMime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * '보내지 않은 무전' 파일 저장소 (F16, 사용자 결정 2026-10-28 — 앱 재시작·프로세스 종료 뒤에도 남는다).
 * '재시작' = 같은 폴더로 **새 인스턴스**를 만들어 다시 읽는다(JVM 임시 폴더).
 */
class UnsentVoiceStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun clip(dir: File, name: String, bytes: Int = 120): File = File(dir, name).apply { parentFile.mkdirs(); writeBytes(ByteArray(bytes) { it.toByte() }) }

    private fun item(id: String, file: File, created: Long, group: String = "grp_1", reason: UnsentReason = UnsentReason.NO_ANSWER, failure: String? = null) =
        UnsentVoice(id, group, file, AudioMime.MP4, 3_000, created, reason, failure)

    @Test
    fun `재시작 증명 — 넣은 것을 새 인스턴스(같은 폴더)가 그대로 읽는다`() {
        val cache = tmp.newFolder("cache")
        val dir = File(tmp.root, "files/ptt_unsent")
        val a = FileUnsentVoiceStore(dir)
        val c1 = clip(cache, "ptt_1.m4a", 300)
        val s1 = a.add(item("u1000-1", c1, 1_000))
        val s2 = a.add(item("u2000-2", clip(cache, "ptt_2.m4a", 200), 2_000, reason = UnsentReason.SEND_FAILED, failure = "HTTP 429 VOICE_RATE_LIMITED"))
        assertNotNull(s1); assertNotNull(s2)
        assertFalse("캐시 파일은 저장소로 옮겼다", c1.exists())
        assertEquals(dir.canonicalPath, s1!!.file.parentFile!!.canonicalPath)
        assertEquals("u1000-1.m4a", s1.file.name)
        assertEquals(2, a.count())

        // 프로세스가 죽고 다시 떴다 — 메모리는 없고 같은 폴더만 있다
        val b = FileUnsentVoiceStore(dir)
        val loaded = b.load()
        assertEquals("최신 먼저", listOf("u2000-2", "u1000-1"), loaded.map { it.id })
        val l1 = loaded[1]
        assertEquals("grp_1", l1.groupId)
        assertEquals(3_000L, l1.durationMs)
        assertEquals(1_000L, l1.createdAtMs)
        assertEquals(AudioMime.MP4, l1.mime)
        assertEquals(UnsentReason.NO_ANSWER, l1.reason)
        assertEquals(300L, l1.file.length())
        assertEquals(UnsentReason.SEND_FAILED, loaded[0].reason)
        assertEquals("HTTP 429 VOICE_RATE_LIMITED", loaded[0].failure)
        assertEquals(2, b.count())
    }

    @Test
    fun `메타 갱신·하나 지우기·전부 지우기(그룹 종료) — 폴더가 빈다`() {
        val cache = tmp.newFolder("cache")
        val dir = File(tmp.root, "store")
        val s = FileUnsentVoiceStore(dir)
        val a = s.add(item("u1-1", clip(cache, "a.m4a"), 1))!!
        val b = s.add(item("u2-2", clip(cache, "b.m4a"), 2))!!
        assertTrue(s.update(a.copy(reason = UnsentReason.SEND_FAILED, failure = "network SocketTimeoutException")))
        assertEquals("network SocketTimeoutException", FileUnsentVoiceStore(dir).load().first { it.id == "u1-1" }.failure)

        assertTrue(s.remove("u2-2"))
        assertFalse(b.file.exists())
        assertFalse(File(dir, "u2-2.json").exists())
        assertFalse("없는 것", s.remove("u2-2"))
        assertEquals(listOf("u1-1"), FileUnsentVoiceStore(dir).load().map { it.id })

        s.add(item("u3-3", clip(cache, "c.m4a"), 3))
        assertEquals(2, s.clear())
        assertEquals(0, s.count())
        assertTrue("그룹 종료 → 폴더가 빈다", dir.listFiles().isNullOrEmpty())
        assertTrue(FileUnsentVoiceStore(dir).load().isEmpty())
    }

    @Test
    fun `손상 JSON·오디오 없는 메타·짝 없는 오디오·쓰다 만 임시 파일은 무시하고 정리한다`() {
        val cache = tmp.newFolder("cache")
        val dir = File(tmp.root, "store")
        val s = FileUnsentVoiceStore(dir)
        s.add(item("u5-1", clip(cache, "ok.m4a"), 5))
        File(dir, "u6-2.json").writeText("{ not json")                      // 해석 안 됨
        File(dir, "u6-2.m4a").writeBytes(ByteArray(10))                     // 그 오디오
        File(dir, "u7-3.json").writeText(                                   // 오디오가 없다
            """{"v":1,"id":"u7-3","groupId":"grp_1","audio":"u7-3.m4a","mime":"audio/mp4","durationMs":1000,"createdAtMs":7,"reason":"NO_ANSWER"}""",
        )
        File(dir, "u8-4.json").writeText(                                   // 파일 이름과 id 가 다르다
            """{"v":1,"id":"other","groupId":"grp_1","audio":"ok.m4a","mime":"audio/mp4","durationMs":1000,"createdAtMs":8,"reason":"NO_ANSWER"}""",
        )
        File(dir, "u9-5.json").writeText(                                   // 모르는 사유
            """{"v":1,"id":"u9-5","groupId":"grp_1","audio":"u5-1.m4a","mime":"audio/mp4","durationMs":1000,"createdAtMs":9,"reason":"WHAT"}""",
        )
        File(dir, "u10-6.json").writeText(                                  // 폴더 밖을 가리킨다
            """{"v":1,"id":"u10-6","groupId":"grp_1","audio":"../cache/ok.m4a","mime":"audio/mp4","durationMs":1000,"createdAtMs":10,"reason":"NO_ANSWER"}""",
        )
        File(dir, "orphan.m4a").writeBytes(ByteArray(5))                    // 메타 없는 오디오(쓰다 만 것)
        File(dir, "u11-7.json.tmp").writeText("{}")                         // 쓰다 만 메타

        val loaded = FileUnsentVoiceStore(dir).load()
        assertEquals(listOf("u5-1"), loaded.map { it.id })
        assertEquals(setOf("u5-1.json", "u5-1.m4a"), dir.listFiles()!!.map { it.name }.toSet())
        assertEquals(1, s.count())
    }

    @Test
    fun `상한 — 넘으면 오래된 것부터 지운다`() {
        val cache = tmp.newFolder("cache")
        val dir = File(tmp.root, "store")
        val s = FileUnsentVoiceStore(dir, maxItems = 3)
        val files = (1..5).map { i -> s.add(item("u$i-$i", clip(cache, "c$i.m4a"), i * 1_000L))!!.file }
        val loaded = FileUnsentVoiceStore(dir, maxItems = 3).load()
        assertEquals(listOf("u5-5", "u4-4", "u3-3"), loaded.map { it.id })
        assertFalse(files[0].exists())
        assertFalse(files[1].exists())
        assertTrue(files[4].exists())
        assertEquals(3, s.count())
    }

    @Test
    fun `저장 실패 — null 이고 원래 파일은 그대로(호출 쪽이 메모리로 들고 있는다)`() {
        val cache = tmp.newFolder("cache")
        // 폴더 자리에 파일이 있어 만들 수 없다
        val blocker = File(tmp.root, "blocked").apply { writeText("x") }
        val s = FileUnsentVoiceStore(blocker)
        val src = clip(cache, "keep.m4a")
        assertNull(s.add(item("u1-1", src, 1)))
        assertTrue(src.isFile)
        assertNull("id 형식이 아니면 거절", FileUnsentVoiceStore(File(tmp.root, "s2")).add(item("../x", src, 1)))
        assertTrue(src.isFile)
    }
}
