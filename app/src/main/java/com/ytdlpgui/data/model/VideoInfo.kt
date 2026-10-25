package com.ytdlpgui.data.model

data class VideoInfo(
    val title: String?,
    val uploader: String?,
    val durationSeconds: Int?,
    val extractor: String?,
    val formatLabel: String?,
    val estimatedSizes: Map<String, String> = emptyMap(),
    val thumbnailUrl: String? = null,
    val webpageUrl: String? = null
) {
    fun describeDuration(): String {
        val d = durationSeconds ?: return ""
        val h = d / 3600
        val m = (d % 3600) / 60
        val s = d % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }

    fun getEstimatedSize(quality: DownloadQuality, format: OutputFormat): String? {
        val container = when (format) {
            is OutputFormat.Video -> format.container.value.lowercase()
            is OutputFormat.Audio -> format.container.value.lowercase()
        }
        if (format is OutputFormat.Audio) {
            return estimatedSizes["audio_$container"]
                ?: estimatedSizes["audio"]
        }
        val key = when (quality) {
            DownloadQuality.BEST -> "best"
            is DownloadQuality.MaxHeight -> when (quality.pixels) {
                in 2000..4320 -> "2160"
                in 1400..1999 -> "1440"
                in 1000..1399 -> "1080"
                in 700..999 -> "720"
                in 450..699 -> "480"
                else -> "360"
            }
        }
        return estimatedSizes["${key}_$container"]
            ?: estimatedSizes[key]
            ?: estimatedSizes["best_$container"]
            ?: estimatedSizes["best"]
    }
}

data class PlaylistEntry(
    val url: String,
    val title: String?,
    val durationSeconds: Int?
)
