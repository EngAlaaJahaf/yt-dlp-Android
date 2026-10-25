package com.ytdlpgui.data.engine

import com.ytdlpgui.data.model.PlaylistEntry
import org.json.JSONObject

/**
 * محلل نقي لمخرجات --flat-playlist --dump-single-json — قابل للاختبار دون أندرويد.
 */
object PlaylistParser {

    fun parse(json: String): List<PlaylistEntry> {
        return try {
            val obj = JSONObject(json)
            val entries = obj.optJSONArray("entries") ?: return emptyList()
            (0 until entries.length()).mapNotNull { i ->
                val e = entries.optJSONObject(i) ?: return@mapNotNull null
                val rawUrl = e.optString("url").trim().ifBlank { e.optString("id").trim() }
                if (rawUrl.isBlank()) return@mapNotNull null
                val fullUrl = if (rawUrl.startsWith("http://", ignoreCase = true) || rawUrl.startsWith("https://", ignoreCase = true)) {
                    rawUrl
                } else if (rawUrl.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) {
                    "https://www.youtube.com/watch?v=$rawUrl"
                } else {
                    rawUrl
                }
                PlaylistEntry(
                    url = fullUrl,
                    title = e.optString("title").takeIf { it.isNotBlank() },
                    durationSeconds = if (e.has("duration") && !e.isNull("duration")) {
                        e.optDouble("duration").toInt()
                    } else null
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
