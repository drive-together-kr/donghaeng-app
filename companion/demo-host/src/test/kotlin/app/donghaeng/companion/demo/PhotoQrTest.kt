package app.donghaeng.companion.demo

import app.donghaeng.companion.demo.donghaeng.IncomingLink
import app.donghaeng.companion.demo.donghaeng.PhotoQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 카메라 없이 QR 읽기(사진)·링크로 열기 — 순수 부분 */
class PhotoQrTest {
    @Test
    fun `first non-blank QR value is used, blank or none gives null`() {
        assertEquals("https://h/j/inv_1", PhotoQr.firstValue(listOf(null, " ", " https://h/j/inv_1 ", "https://h/j/inv_2")))
        assertNull(PhotoQr.firstValue(listOf(null, "")))
        assertNull(PhotoQr.firstValue(emptyList()))
    }

    @Test
    fun `incoming link is taken once`() {
        IncomingLink.offer("https://h/pair?c=KQ74MZ")
        assertEquals("https://h/pair?c=KQ74MZ", IncomingLink.take())
        assertNull(IncomingLink.take())
        IncomingLink.offer(" ")
        assertNull(IncomingLink.take())
    }
}
