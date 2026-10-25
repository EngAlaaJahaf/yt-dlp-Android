package com.ytdlpgui

import com.ytdlpgui.data.model.AudioContainer
import com.ytdlpgui.data.model.DownloadQuality
import com.ytdlpgui.data.model.DownloadRequest
import com.ytdlpgui.data.model.OutputFormat
import com.ytdlpgui.data.model.RequestCodec
import com.ytdlpgui.data.model.VideoContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestCodecTest {

    private fun requestVideoBest(dir: String = "/sdcard/Download") = DownloadRequest(
        url = "https://example.com/v/1",
        quality = DownloadQuality.BEST,
        format = OutputFormat.Video(VideoContainer.MP4),
        downloadDir = dir
    )

    private fun requestAudio720(dir: String = "/sdcard/Music") = DownloadRequest(
        url = "https://example.com/a/2",
        quality = DownloadQuality.MaxHeight(720),
        format = OutputFormat.Audio(AudioContainer.MP3),
        downloadDir = dir
    )

    @Test
    fun encodeDecode_roundtrip_videoBest() {
        val decoded = RequestCodec.decode(RequestCodec.encode(requestVideoBest()))
        assertEquals(requestVideoBest(), decoded)
    }

    @Test
    fun encodeDecode_roundtrip_audio720() {
        val decoded = RequestCodec.decode(RequestCodec.encode(requestAudio720()))
        assertEquals(requestAudio720(), decoded)
    }

    @Test
    fun decode_garbage_returnsNull() {
        assertNull(RequestCodec.decode("not a json {{{"))
        assertNull(RequestCodec.decode(""))
        assertNull(RequestCodec.decode("{\"url\":123}")) // حقول ناقصة

    }

    @Test
    fun encode_unknownFormat_isRejectedOrHandled() {
        // قائمة الحاويات معروفة؛ decode لعنصر حاوية غير معروف يجب أن يفشل بأمان
        assertNull(RequestCodec.decode("{\"q\":\"best\",\"fmt\":\"video\",\"cont\":\"UNKNOWN\",\"url\":\"u\",\"dir\":\"d\"}"))
    }

    @Test
    fun cookies_roundtrip() {
        val withCookies = requestVideoBest().copy(cookiesPath = "/data/cookies.txt")
        assertEquals(withCookies, RequestCodec.decode(RequestCodec.encode(withCookies)))
    }

    @Test
    fun cookies_absent_legacyJson_decodesToNull() {
        // صفوف قديمة في قاعدة البيانات بلا حقل cookies — يجب أن تُقرأ بأمان
        val legacy = "{\"q\":\"best\",\"fmt\":\"video\",\"cont\":\"MP4\",\"url\":\"u\",\"dir\":\"d\"}"
        val decoded = RequestCodec.decode(legacy)
        assertEquals(null, decoded?.cookiesPath)
    }

    @Test
    fun cookies_emptyValue_decodesToNull() {
        val empty = "{\"q\":\"best\",\"fmt\":\"video\",\"cont\":\"MP4\",\"url\":\"u\",\"dir\":\"d\",\"cookies\":\"\"}"
        assertEquals(null, RequestCodec.decode(empty)?.cookiesPath)
    }
}
