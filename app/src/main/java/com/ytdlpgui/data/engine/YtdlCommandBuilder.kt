package com.ytdlpgui.data.engine

import com.ytdlpgui.data.model.DownloadQuality
import com.ytdlpgui.data.model.DownloadRequest
import com.ytdlpgui.data.model.OutputFormat

/**
 * بنّاء أوامر yt-dlp النقي — بدون أي حساسية للبيئة على أندرويد، ويُختبر وحدةً مباشرة.
 */
object YtdlCommandBuilder {

    const val OUTPUT_TEMPLATE = "%(title).150s [%(id)s].%(ext)s"

    /** وسيط واحد: إما علم بلا قيمة أو خيار بقيمة */
    sealed class Arg {
        data class Flag(val name: String) : Arg()
        data class Option(val name: String, val value: String) : Arg()
    }

    fun formatSelector(quality: DownloadQuality): String {
        return when (quality) {
            DownloadQuality.BEST -> "bestvideo+bestaudio/best"
            is DownloadQuality.MaxHeight ->
                "bestvideo[height<=${quality.pixels}]+bestaudio/best[height<=${quality.pixels}]/best"
        }
    }

    /**
     * وسائط yt-dlp لطلب التنزيل (مهيكلة: أعلام vs خيارات بقيمة).
     */
    fun buildArgs(request: DownloadRequest): List<Arg> {
        val args = mutableListOf<Arg>()
        args += Arg.Option("-o", "${request.downloadDir.trimEnd('/')}/$OUTPUT_TEMPLATE")
        args += Arg.Flag("--newline")
        args += Arg.Flag("--force-ipv4")
        args += Arg.Flag("--no-mtime")
        args += Arg.Flag("--no-playlist")
        args += Arg.Flag("--windows-filenames")
        request.cookiesPath?.let { args += Arg.Option("--cookies", it) }

        when (val format = request.format) {
            is OutputFormat.Audio -> {
                args += Arg.Option("-f", "ba/b")
                args += Arg.Flag("-x")
                args += Arg.Option("--audio-format", format.container.value)
            }
            is OutputFormat.Video -> {
                args += Arg.Option("-f", formatSelector(request.quality))
                args += Arg.Option("--merge-output-format", format.container.value)
            }
        }
        return args
    }
}
