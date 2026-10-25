package com.ytdlpgui.data.model

import java.io.Serializable

sealed class OutputFormat(val label: String) : Serializable {
    data class Video(val container: VideoContainer) : OutputFormat(container.name)
    data class Audio(val container: AudioContainer) : OutputFormat(container.name)

    companion object {
        private const val serialVersionUID = 1L
        val formatsList = listOf(
            Video(VideoContainer.MP4),
            Video(VideoContainer.MKV),
            Video(VideoContainer.WEBM),
            Audio(AudioContainer.MP3),
            Audio(AudioContainer.M4A),
            Audio(AudioContainer.OPUS),
            Audio(AudioContainer.FLAC),
            Audio(AudioContainer.WAV)
        )
    }
}

enum class VideoContainer(val value: String) {
    MP4("mp4"),
    MKV("mkv"),
    WEBM("webm")
}

enum class AudioContainer(val value: String) {
    MP3("mp3"),
    M4A("m4a"),
    OPUS("opus"),
    FLAC("flac"),
    WAV("wav")
}

sealed class DownloadQuality(val label: String) : Serializable {
    object BEST : DownloadQuality("best")
    data class MaxHeight(val pixels: Int) : DownloadQuality("${pixels}p") {
        override fun equals(other: Any?): Boolean =
            other is MaxHeight && other.pixels == pixels
        override fun hashCode(): Int = pixels
    }

    companion object {
        val All = listOf(
            BEST,
            MaxHeight(1080),
            MaxHeight(720),
            MaxHeight(480),
            MaxHeight(360)
        )
    }
}

data class DownloadRequest(
    val url: String,
    val quality: DownloadQuality,
    val format: OutputFormat,
    val downloadDir: String,
    val outputTemplate: String = "%(title)s.%(ext)s",
    val cookiesPath: String? = null
) : Serializable {
    companion object {
        private const val serialVersionUID = 2L
    }
}
