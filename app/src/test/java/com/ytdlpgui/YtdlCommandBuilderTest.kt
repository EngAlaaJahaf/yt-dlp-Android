package com.ytdlpgui

import com.ytdlpgui.data.engine.YtdlCommandBuilder
import com.ytdlpgui.data.engine.YtdlCommandBuilder.Arg
import com.ytdlpgui.data.model.AudioContainer
import com.ytdlpgui.data.model.DownloadQuality
import com.ytdlpgui.data.model.DownloadRequest
import com.ytdlpgui.data.model.OutputFormat
import com.ytdlpgui.data.model.VideoContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtdlCommandBuilderTest {

    private fun req(
        quality: DownloadQuality = DownloadQuality.BEST,
        format: OutputFormat = OutputFormat.Video(VideoContainer.MP4),
        dir: String = "/sdcard/Download/app"
    ) = DownloadRequest(url = "https://demo.example/watch/xyz", quality = quality, format = format, downloadDir = dir)

    private fun options(args: List<Arg>) = args.filterIsInstance<Arg.Option>()
    private fun flags(args: List<Arg>) = args.filterIsInstance<Arg.Flag>().map { it.name }
    private fun option(args: List<Arg>, name: String) = options(args).first { it.name == name }

    @Test
    fun video_mp4_best_usesMergedFormat() {
        val args = YtdlCommandBuilder.buildArgs(req())
        assertEquals("bestvideo+bestaudio/best", option(args, "-f").value)
        assertEquals("mp4", option(args, "--merge-output-format").value)
        assertFalse(flags(args).contains("-f"))
    }

    @Test
    fun quality_720_buildsHeightSelector() {
        val args = YtdlCommandBuilder.buildArgs(req(quality = DownloadQuality.MaxHeight(720)))
        assertEquals(
            "bestvideo[height<=720]+bestaudio/best[height<=720]/best",
            option(args, "-f").value
        )
    }

    @Test
    fun audio_mp3_asksExtractAndFormat() {
        val args = YtdlCommandBuilder.buildArgs(req(format = OutputFormat.Audio(AudioContainer.MP3)))
        assertTrue(flags(args).contains("-x"))
        assertEquals("ba/b", option(args, "-f").value)
        assertEquals("mp3", option(args, "--audio-format").value)
    }

    @Test
    fun outputTemplate_appendsToDir() {
        assertEquals("%(title).150s [%(id)s].%(ext)s", YtdlCommandBuilder.OUTPUT_TEMPLATE)
        val args = YtdlCommandBuilder.buildArgs(req())
        assertEquals(
            "/sdcard/Download/app/${YtdlCommandBuilder.OUTPUT_TEMPLATE}",
            option(args, "-o").value
        )
        assertTrue(flags(args).contains("--no-playlist"))
        assertTrue(flags(args).contains("--windows-filenames"))
        assertTrue(flags(args).contains("--force-ipv4"))
    }

    @Test
    fun noMtime_isFlag_notConsumedAsValue() {
        // هذا الاختبار يمنع انكلاس "Index 0 out of bounds": يجب أن يكون --no-mtime علماً بلا قيمة
        val args = YtdlCommandBuilder.buildArgs(
            req(quality = DownloadQuality.MaxHeight(1080), format = OutputFormat.Audio(AudioContainer.FLAC))
        )
        assertTrue(flags(args).contains("--no-mtime"))
        assertFalse(options(args).any { it.name == "--no-mtime" })
        assertEquals("flac", option(args, "--audio-format").value)
        assertTrue(flags(args).contains("-x"))
        assertEquals("ba/b", option(args, "-f").value)
    }

    @Test
    fun everyOptionHasValue_noOrphanFlags() {
        val args = YtdlCommandBuilder.buildArgs(
            req(quality = DownloadQuality.MaxHeight(480), format = OutputFormat.Video(VideoContainer.WEBM))
        )
        assertTrue(options(args).all { it.value.isNotBlank() })
        assertTrue(flags(args).all { it != "-f" && it != "--merge-output-format" })
    }

    @Test
    fun cookies_addedOnlyWhenPresent() {
        val withCookies = req().copy(cookiesPath = "/data/user/0/app/files/cookies.txt")
        val args = YtdlCommandBuilder.buildArgs(withCookies)
        assertEquals("/data/user/0/app/files/cookies.txt", option(args, "--cookies").value)

        val without = YtdlCommandBuilder.buildArgs(req())
        assertFalse(options(without).any { it.name == "--cookies" })
    }
}
