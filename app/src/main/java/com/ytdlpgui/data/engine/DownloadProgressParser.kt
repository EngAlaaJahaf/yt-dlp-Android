package com.ytdlpgui.data.engine

import com.ytdlpgui.data.bus.DownloadProgressInfo
import java.util.Locale

object DownloadProgressParser {

    private val progressRegex = Regex("""\[download\]\s+(\d+(?:\.\d+)?)%\s+of\s+~?\s*([\d\.]+\s*[a-zA-Z]+)(?:\s+at\s+([\d\.]+\s*[a-zA-Z/]+))?(?:\s+ETA\s+([\d:]+))?""")
    private val percentFallback = Regex("""(\d+(?:\.\d+)?)%""")
    private val totalSizeFallback = Regex("""of\s+~?\s*([\d\.]+\s*[kMGTP]?i?B)""", RegexOption.IGNORE_CASE)
    private val speedFallback = Regex("""at\s+([\d\.]+\s*[kMGTP]?i?B/s)""", RegexOption.IGNORE_CASE)
    private val etaFallback = Regex("""ETA\s+([\d:]+)""", RegexOption.IGNORE_CASE)

    fun parse(rowId: Long, line: String, rawProgress: Int = 0, title: String? = null): DownloadProgressInfo {
        val match = progressRegex.find(line)
        val percent: Int
        val totalStr: String?
        val speedStr: String?
        val etaStr: String?

        if (match != null) {
            val pctFloat = match.groupValues[1].toFloatOrNull() ?: rawProgress.toFloat()
            percent = pctFloat.toInt().coerceIn(0, 100)
            totalStr = match.groupValues[2].trim().takeIf { it.isNotBlank() }
            speedStr = match.groupValues.getOrNull(3)?.trim()?.takeIf { it.isNotBlank() }
            etaStr = match.groupValues.getOrNull(4)?.trim()?.takeIf { it.isNotBlank() }
        } else {
            val pctStr = percentFallback.find(line)?.groupValues?.get(1)
            percent = (pctStr?.toFloatOrNull()?.toInt() ?: rawProgress).coerceIn(0, 100)
            totalStr = totalSizeFallback.find(line)?.groupValues?.get(1)?.trim()
            speedStr = speedFallback.find(line)?.groupValues?.get(1)?.trim()
            etaStr = etaFallback.find(line)?.groupValues?.get(1)?.trim()
        }

        val downloadedStr = calculateDownloaded(percent, totalStr)

        return DownloadProgressInfo(
            rowId = rowId,
            percent = if (percent > 0) percent else rawProgress,
            downloadedStr = downloadedStr,
            totalStr = totalStr,
            speedStr = speedStr,
            etaStr = etaStr,
            title = title
        )
    }

    private fun calculateDownloaded(percent: Int, totalStr: String?): String? {
        if (totalStr.isNullOrBlank()) return null
        val numMatch = Regex("""^([\d\.]+)""").find(totalStr.trim()) ?: return null
        val totalVal = numMatch.groupValues[1].toDoubleOrNull() ?: return null
        val unit = totalStr.replace(numMatch.groupValues[1], "").trim()
        val downloadedVal = (percent / 100.0) * totalVal
        return String.format(Locale.US, "%.1f %s", downloadedVal, unit)
    }
}
