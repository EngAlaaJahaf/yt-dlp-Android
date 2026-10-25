package com.ytdlpgui

import com.ytdlpgui.data.engine.FormatSizeEstimator
import com.ytdlpgui.data.model.AudioContainer
import com.ytdlpgui.data.model.DownloadQuality
import com.ytdlpgui.data.model.OutputFormat
import com.ytdlpgui.data.model.VideoContainer
import com.ytdlpgui.data.model.VideoInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class FormatSizeEstimatorTest {

    @Test
    fun formatsWithSize_calculatesRealSizeSummingVideoAndAudio() {
        val root = JSONObject()
        root.put("duration", 100.0)

        val formats = JSONArray()

        // Audio stream (M4A) ~ 10 MB
        val audioM4a = JSONObject().apply {
            put("format_id", "140")
            put("ext", "m4a")
            put("vcodec", "none")
            put("acodec", "mp4a.40.2")
            put("abr", 128.0)
            put("filesize", 10_000_000L)
        }
        formats.put(audioM4a)

        // 720p Video stream (MP4) ~ 50 MB
        val video720Mp4 = JSONObject().apply {
            put("format_id", "136")
            put("ext", "mp4")
            put("height", 720)
            put("vcodec", "avc1.4d401f")
            put("acodec", "none")
            put("filesize", 50_000_000L)
        }
        formats.put(video720Mp4)

        // 1080p Video stream (MP4) ~ 100 MB
        val video1080Mp4 = JSONObject().apply {
            put("format_id", "137")
            put("ext", "mp4")
            put("height", 1080)
            put("vcodec", "avc1.640028")
            put("acodec", "none")
            put("filesize", 100_000_000L)
        }
        formats.put(video1080Mp4)

        root.put("formats", formats)

        val sizes = FormatSizeEstimator.estimateSizes(root)

        // Audio should be ~10 MB (9.5 MB formatted)
        assertNotNull(sizes["audio"])
        assertNotNull(sizes["audio_m4a"])

        // 720p total = 50 MB video + 10 MB audio = 60 MB
        assertNotNull(sizes["720_mp4"])
        val mb720 = FormatSizeEstimator.formatBytes(60_000_000L)
        assertEquals(mb720, sizes["720_mp4"])

        // 1080p total = 100 MB video + 10 MB audio = 110 MB
        assertNotNull(sizes["1080_mp4"])
        val mb1080 = FormatSizeEstimator.formatBytes(110_000_000L)
        assertEquals(mb1080, sizes["1080_mp4"])

        // Test VideoInfo.getEstimatedSize
        val info = VideoInfo(
            title = "Test",
            uploader = "User",
            durationSeconds = 100,
            extractor = "youtube",
            formatLabel = "mp4",
            estimatedSizes = sizes
        )

        assertEquals(mb1080, info.getEstimatedSize(DownloadQuality.MaxHeight(1080), OutputFormat.Video(VideoContainer.MP4)))
        assertEquals(mb720, info.getEstimatedSize(DownloadQuality.MaxHeight(720), OutputFormat.Video(VideoContainer.MP4)))
        assertEquals(sizes["audio_m4a"], info.getEstimatedSize(DownloadQuality.BEST, OutputFormat.Audio(AudioContainer.M4A)))
    }

    @Test
    fun formatBytes_formatsCorrectly() {
        assertEquals("500 KB", FormatSizeEstimator.formatBytes(512_000L))
        assertEquals("25.0 MB", FormatSizeEstimator.formatBytes(26_214_400L))
        assertEquals("1.5 GB", FormatSizeEstimator.formatBytes(1_610_612_736L))
    }
}
