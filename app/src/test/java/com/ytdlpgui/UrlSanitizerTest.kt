package com.ytdlpgui

import com.ytdlpgui.data.model.UrlSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlSanitizerTest {

    @Test
    fun plainUrl_passesThrough() {
        assertEquals("https://youtu.be/abc", UrlSanitizer.sanitize("https://youtu.be/abc"))
    }

    @Test
    fun duplicatedProtocol_isFixedToLastUrl() {
        assertEquals(
            "https://www.youtube.com/watch?v=x",
            UrlSanitizer.sanitize("https://youtubehttps://www.youtube.com/watch?v=x")
        )
    }

    @Test
    fun extractsUrlFromJunkText() {
        assertEquals(
            "https://youtu.be/abc",
            UrlSanitizer.sanitize("شاهد هذا الفيديو https://youtu.be/abc الآن")
        )
    }

    @Test
    fun stripsTrailingPunctuation() {
        assertEquals("https://x.com/a", UrlSanitizer.sanitize("رابط: https://x.com/a."))
        assertEquals("https://x.com/a", UrlSanitizer.sanitize("https://x.com/a)"))
    }

    @Test
    fun invalidInput_returnsNull() {
        assertNull(UrlSanitizer.sanitize("hello world"))
        assertNull(UrlSanitizer.sanitize(""))
        assertNull(UrlSanitizer.sanitize(null))
        assertNull(UrlSanitizer.sanitize("youtube.com/no-protocol"))
    }

    @Test
    fun keepsQueryParams() {
        assertEquals(
            "https://www.youtube.com/shorts/73_Ib2CoL8?si=rCVOsIvETjRV-xQy",
            UrlSanitizer.sanitize("https://www.youtube.com/shorts/73_Ib2CoL8?si=rCVOsIvETjRV-xQy")
        )
    }

    @Test
    fun normalizeKey_unifiesVideoIdAcrossUrlFormats() {
        assertEquals("yt:abc", UrlSanitizer.normalizeKey("https://youtu.be/abc"))
        assertEquals("yt:abc", UrlSanitizer.normalizeKey("https://www.youtube.com/watch?v=abc&feature=share"))
        assertEquals("yt:abc", UrlSanitizer.normalizeKey("https://www.youtube.com/shorts/abc?si=x"))
        assertEquals("yt:abc", UrlSanitizer.normalizeKey("https://www.youtube.com/live/abc?feature=share"))
        assertEquals("yt:abc", UrlSanitizer.normalizeKey("https://www.youtube.com/embed/abc"))
    }

    @Test
    fun normalizeKey_nonYoutube_usesLowercaseUrl() {
        assertEquals(
            "https://example.com/video",
            UrlSanitizer.normalizeKey("https://Example.com/video/")
        )
    }

    @Test
    fun normalizeKey_invalid_returnsNull() {
        assertNull(UrlSanitizer.normalizeKey("hello"))
        assertNull(UrlSanitizer.normalizeKey(null))
    }

    @Test
    fun normalizeKey_socialPlatforms_extractsDistinctId() {
        assertEquals(
            "tiktok:7123456789012345678",
            UrlSanitizer.normalizeKey("https://www.tiktok.com/@user/video/7123456789012345678?is_from_webapp=1")
        )
        assertEquals(
            "ig:C_xyz123",
            UrlSanitizer.normalizeKey("https://www.instagram.com/reel/C_xyz123/?igsh=abc")
        )
        assertEquals(
            "x:1234567890123456789",
            UrlSanitizer.normalizeKey("https://x.com/user/status/1234567890123456789")
        )
        assertEquals(
            "fb:123456789",
            UrlSanitizer.normalizeKey("https://www.facebook.com/watch/?v=123456789")
        )
    }

    @Test
    fun cleanSingleVideoUrl_stripsPlaylistParameters() {
        assertEquals(
            "https://www.youtube.com/watch?v=abc",
            UrlSanitizer.cleanSingleVideoUrl("https://www.youtube.com/watch?v=abc&list=PL12345&index=2")
        )
        assertEquals(
            "https://youtu.be/abc",
            UrlSanitizer.cleanSingleVideoUrl("https://youtu.be/abc?list=PL12345")
        )
        assertEquals(
            "https://www.youtube.com/shorts/abc",
            UrlSanitizer.cleanSingleVideoUrl("https://www.youtube.com/shorts/abc?si=tracker")
        )
    }

    @Test
    fun isPlaylistOrChannel_detectsPlaylistsAndChannels() {
        org.junit.Assert.assertTrue(UrlSanitizer.isPlaylistOrChannel("https://www.youtube.com/playlist?list=PL12345"))
        org.junit.Assert.assertTrue(UrlSanitizer.isPlaylistOrChannel("https://www.youtube.com/@channelName/videos"))
        org.junit.Assert.assertTrue(UrlSanitizer.isPlaylistOrChannel("https://www.youtube.com/channel/UC12345"))
        org.junit.Assert.assertTrue(UrlSanitizer.isPlaylistOrChannel("https://www.tiktok.com/@creator"))

        org.junit.Assert.assertFalse(UrlSanitizer.isPlaylistOrChannel("https://www.youtube.com/watch?v=abc"))
        org.junit.Assert.assertFalse(UrlSanitizer.isPlaylistOrChannel("https://youtu.be/abc"))
        org.junit.Assert.assertFalse(UrlSanitizer.isPlaylistOrChannel("https://www.youtube.com/shorts/abc"))
        org.junit.Assert.assertFalse(UrlSanitizer.isPlaylistOrChannel("https://www.tiktok.com/@creator/video/12345"))
        org.junit.Assert.assertFalse(UrlSanitizer.isPlaylistOrChannel("https://www.instagram.com/reel/abc/"))
    }
}
