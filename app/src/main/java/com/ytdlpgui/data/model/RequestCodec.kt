package com.ytdlpgui.data.model

import org.json.JSONObject

object RequestCodec {

    fun encode(request: DownloadRequest): String {
        val obj = JSONObject()
        obj.put("url", request.url)
        obj.put("dir", request.downloadDir)
        when (request.quality) {
            is DownloadQuality.BEST -> obj.put("q", "best")
            is DownloadQuality.MaxHeight -> {
                obj.put("q", "max")
                obj.put("pixels", request.quality.pixels)
            }
        }
        when (val fmt = request.format) {
            is OutputFormat.Video -> {
                obj.put("fmt", "video")
                obj.put("cont", fmt.container.name)
            }
            is OutputFormat.Audio -> {
                obj.put("fmt", "audio")
                obj.put("cont", fmt.container.name)
            }
        }
        request.cookiesPath?.let { obj.put("cookies", it) }
        return obj.toString()
    }

    fun decode(json: String): DownloadRequest? {
        return try {
            val obj = JSONObject(json)
            val quality = when (obj.optString("q")) {
                "max" -> DownloadQuality.MaxHeight(obj.getInt("pixels"))
                else -> DownloadQuality.BEST
            }
            val format = when (obj.optString("fmt")) {
                "video" -> OutputFormat.Video(VideoContainer.valueOf(obj.getString("cont")))
                else -> OutputFormat.Audio(AudioContainer.valueOf(obj.getString("cont")))
            }
            val cookies = if (obj.has("cookies") && !obj.isNull("cookies"))
                obj.getString("cookies").takeIf { it.isNotBlank() } else null
            DownloadRequest(
                url = obj.getString("url"),
                quality = quality,
                format = format,
                downloadDir = obj.getString("dir"),
                cookiesPath = cookies
            )
        } catch (_: Exception) {
            null
        }
    }
}
