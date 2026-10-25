package com.ytdlpgui.data.engine

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object FormatSizeEstimator {

    fun estimateSizes(jsonObj: JSONObject): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val formats = jsonObj.optJSONArray("formats")
        val duration = if (jsonObj.has("duration")) jsonObj.optDouble("duration", 0.0) else 0.0

        if (formats == null || formats.length() == 0) {
            val topSize = if (jsonObj.has("filesize") && !jsonObj.isNull("filesize")) jsonObj.optLong("filesize", 0L)
            else if (jsonObj.has("filesize_approx") && !jsonObj.isNull("filesize_approx")) jsonObj.optLong("filesize_approx", 0L)
            else 0L
            if (topSize > 0) {
                val str = formatBytes(topSize)
                result["best"] = str
                result["720"] = str
            }
            return result
        }

        // الترويسات العامة للطلب
        val globalHeaders = mutableMapOf<String, String>()
        val gHeadersObj = jsonObj.optJSONObject("http_headers")
        if (gHeadersObj != null) {
            for (key in gHeadersObj.keys()) {
                globalHeaders[key] = gHeadersObj.optString(key)
            }
        }

        val allStreams = mutableListOf<StreamMeta>()
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val formatId = f.optString("format_id", "")
            val ext = f.optString("ext", "").lowercase(Locale.US)
            val vcodec = f.optString("vcodec", "none")
            val acodec = f.optString("acodec", "none")
            val height = f.optInt("height", 0)
            val filesize = if (f.has("filesize") && !f.isNull("filesize")) f.optLong("filesize", 0L) else 0L
            val filesizeApprox = if (f.has("filesize_approx") && !f.isNull("filesize_approx")) f.optLong("filesize_approx", 0L) else 0L
            val tbr = f.optDouble("tbr", 0.0)
            val vbr = f.optDouble("vbr", 0.0)
            val abr = f.optDouble("abr", 0.0)
            val url = f.optString("url").takeIf { it.isNotBlank() }

            val streamHeaders = mutableMapOf<String, String>()
            streamHeaders.putAll(globalHeaders)
            val sHeadersObj = f.optJSONObject("http_headers")
            if (sHeadersObj != null) {
                for (k in sHeadersObj.keys()) {
                    streamHeaders[k] = sHeadersObj.optString(k)
                }
            }

            allStreams.add(
                StreamMeta(
                    formatId = formatId,
                    ext = ext,
                    height = height,
                    filesize = filesize,
                    filesizeApprox = filesizeApprox,
                    vcodec = vcodec,
                    acodec = acodec,
                    tbr = tbr,
                    vbr = vbr,
                    abr = abr,
                    url = url,
                    httpHeaders = streamHeaders
                )
            )
        }

        val videoStreams = allStreams.filter { it.isVideo }
        val audioStreams = allStreams.filter { it.isAudio }
        val combinedStreams = allStreams.filter { it.isCombined }

        // استخراج أفضل مسارات الصوت الحقيقية
        val bestAudioM4a = audioStreams.filter { it.ext == "m4a" || it.acodec.contains("mp4a", ignoreCase = true) }
            .maxByOrNull { it.abr.takeIf { a -> a > 0 } ?: it.filesize.toDouble() }
        val bestAudioWebm = audioStreams.filter { it.ext == "webm" || it.acodec.contains("opus", ignoreCase = true) }
            .maxByOrNull { it.abr.takeIf { a -> a > 0 } ?: it.filesize.toDouble() }
        val bestAudioOverall = audioStreams.maxByOrNull { it.abr.takeIf { a -> a > 0 } ?: it.filesize.toDouble() }

        val audioM4aBytes = bestAudioM4a?.resolveRealBytes(duration) ?: (bestAudioOverall?.resolveRealBytes(duration) ?: 0L)
        val audioWebmBytes = bestAudioWebm?.resolveRealBytes(duration) ?: (bestAudioOverall?.resolveRealBytes(duration) ?: 0L)
        val audioOverallBytes = bestAudioOverall?.resolveRealBytes(duration) ?: 0L

        if (audioM4aBytes > 0) result["audio_m4a"] = formatBytes(audioM4aBytes)
        if (audioWebmBytes > 0) result["audio_webm"] = formatBytes(audioWebmBytes)
        if (audioOverallBytes > 0) {
            result["audio"] = formatBytes(audioOverallBytes)
            result["audio_mp3"] = formatBytes(audioOverallBytes)
            result["audio_opus"] = formatBytes(if (audioWebmBytes > 0) audioWebmBytes else audioOverallBytes)
        }

        val resolutions = listOf(2160, 1440, 1080, 720, 480, 360)
        val containers = listOf("mp4", "webm", "mkv")

        for (res in resolutions) {
            for (container in containers) {
                val candidateVideos = videoStreams.filter { v ->
                    v.height == res || (res == 2160 && v.height >= 2160)
                }.filter { v ->
                    if (container == "mp4") v.ext == "mp4" || v.vcodec.startsWith("avc") || v.vcodec.startsWith("av01")
                    else if (container == "webm") v.ext == "webm" || v.vcodec.startsWith("vp")
                    else true
                }

                val chosenVideo = candidateVideos.maxByOrNull { it.filesize.takeIf { s -> s > 0 } ?: (it.tbr.toLong()) }
                    ?: videoStreams.filter { it.height == res }.maxByOrNull { it.tbr }

                if (chosenVideo != null) {
                    val vBytes = chosenVideo.resolveRealBytes(duration)
                    if (vBytes > 0) {
                        val aBytes = if (chosenVideo.acodec == "none") {
                            if (container == "webm") audioWebmBytes else audioM4aBytes
                        } else 0L
                        val totalBytes = vBytes + aBytes
                        val formatted = formatBytes(totalBytes)
                        result["${res}_$container"] = formatted
                        if (!result.containsKey(res.toString())) {
                            result[res.toString()] = formatted
                        }
                    }
                }
            }

            // دعم المسارات المدمجة (Progressive Video + Audio في ملف واحد مثل صيغ 18 و 22)
            val combined = combinedStreams.firstOrNull { it.height == res }
            if (combined != null) {
                val cBytes = combined.resolveRealBytes(duration)
                if (cBytes > 0) {
                    val formatted = formatBytes(cBytes)
                    if (!result.containsKey("${res}_mp4")) result["${res}_mp4"] = formatted
                    if (!result.containsKey(res.toString())) result[res.toString()] = formatted
                }
            }
        }

        // حساب أفضل جودة (Best) لكل حاوية
        for (container in containers) {
            val candidateVideos = videoStreams.filter { v ->
                if (container == "mp4") v.ext == "mp4" || v.vcodec.startsWith("avc") || v.vcodec.startsWith("av01")
                else if (container == "webm") v.ext == "webm" || v.vcodec.startsWith("vp")
                else true
            }
            val chosen = candidateVideos.maxByOrNull { (it.height * 100000L) + (it.tbr.toLong()) }
                ?: videoStreams.maxByOrNull { (it.height * 100000L) + (it.tbr.toLong()) }
            if (chosen != null) {
                val vBytes = chosen.resolveRealBytes(duration)
                if (vBytes > 0) {
                    val aBytes = if (chosen.acodec == "none") {
                        if (container == "webm") audioWebmBytes else audioM4aBytes
                    } else 0L
                    val formatted = formatBytes(vBytes + aBytes)
                    result["best_$container"] = formatted
                    if (!result.containsKey("best")) {
                        result["best"] = formatted
                    }
                }
            }
        }

        // في حال كان الكائن الأصلي يحتوي فقط على حجم عام
        if (result.isEmpty()) {
            val topSize = if (jsonObj.has("filesize") && !jsonObj.isNull("filesize")) jsonObj.optLong("filesize", 0L)
            else if (jsonObj.has("filesize_approx") && !jsonObj.isNull("filesize_approx")) jsonObj.optLong("filesize_approx", 0L)
            else 0L
            if (topSize > 0) {
                val str = formatBytes(topSize)
                result["best"] = str
                result["720"] = str
            }
        }

        return result
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1000.0) {
            String.format(Locale.US, "%.1f GB", mb / 1024.0)
        } else if (mb >= 1.0) {
            String.format(Locale.US, "%.1f MB", mb)
        } else {
            String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        }
    }

    private data class StreamMeta(
        val formatId: String,
        val ext: String,
        val height: Int,
        val filesize: Long,
        val filesizeApprox: Long,
        val vcodec: String,
        val acodec: String,
        val tbr: Double,
        val vbr: Double,
        val abr: Double,
        val url: String?,
        val httpHeaders: Map<String, String> = emptyMap()
    ) {
        val isVideo: Boolean get() = vcodec != "none" && vcodec.isNotBlank()
        val isAudio: Boolean get() = acodec != "none" && acodec.isNotBlank() && (vcodec == "none" || vcodec.isBlank())
        val isCombined: Boolean get() = isVideo && acodec != "none" && acodec.isNotBlank()

        fun resolveRealBytes(duration: Double): Long {
            // 1. الحجم الحقيقي المعطى صراحة من المصدر
            if (filesize > 0) return filesize

            // 2. الحجم الفعلي المحسوب من ترويسة Content-Length للمصدر
            if (filesizeApprox > 0) return filesizeApprox

            // 3. الاستعلام المباشر عبر HTTP Range / Head لسيرفر المصدر إن وُجد الرابط
            if (!url.isNullOrBlank()) {
                val probed = probeSourceContentLength(url, httpHeaders)
                if (probed > 0) return probed
            }

            // 4. الحساب الدقيق عبر معدل البت الحقيقي × المدة الزمنية
            val bitrateKbps = when {
                vbr > 0 -> vbr
                abr > 0 -> abr
                tbr > 0 -> tbr
                else -> 0.0
            }
            return if (bitrateKbps > 0 && duration > 0) {
                ((bitrateKbps * 1000.0 / 8.0) * duration).toLong()
            } else 0L
        }

        private fun probeSourceContentLength(urlStr: String, headers: Map<String, String>): Long {
            if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) return 0L
            return try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.connectTimeout = 2500
                conn.readTimeout = 2500
                conn.instanceFollowRedirects = true
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.setRequestProperty("Range", "bytes=0-0")
                val code = conn.responseCode
                if (code == 206) {
                    val range = conn.getHeaderField("Content-Range")
                    val total = range?.substringAfterLast('/', "")?.toLongOrNull()
                    conn.disconnect()
                    if (total != null && total > 0) return total
                } else if (code == 200) {
                    val cl = conn.contentLengthLong
                    conn.disconnect()
                    if (cl > 0) return cl
                }
                conn.disconnect()
                0L
            } catch (_: Exception) {
                0L
            }
        }
    }
}
