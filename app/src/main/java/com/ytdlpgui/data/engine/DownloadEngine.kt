package com.ytdlpgui.data.engine

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.ytdlpgui.data.model.DownloadQuality
import com.ytdlpgui.data.model.DownloadRequest
import com.ytdlpgui.data.model.OutputFormat
import com.ytdlpgui.data.model.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DownloadEngine(private val context: Context) : EngineApi {

    companion object {
        const val PROCESS_ID = "yt_dlp_download_task"
        const val INFO_PROCESS_ID = "yt_dlp_info_query"

        @Volatile
        private var initialized = false
        const val INIT_LOCK = "engine_init_lock"

        suspend fun ensureInitialized(context: Context) {
            if (initialized) return
            synchronized(INIT_LOCK) {
                if (initialized) return
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
                initialized = true
            }
        }

        suspend fun getInitializedVersion(context: Context): String = withContext(Dispatchers.IO) {
            try {
                ensureInitialized(context)
                YoutubeDL.getInstance().versionName(context.applicationContext) ?: "ready"
            } catch (_: Exception) {
                "ready"
            }
        }
    }

    suspend override fun initialize(): Result<String> = withContext(Dispatchers.IO) {
        try {
            synchronized(INIT_LOCK) {
                YoutubeDL.getInstance().init(context.applicationContext)
                FFmpeg.getInstance().init(context.applicationContext)
                initialized = true
            }
            val version = YoutubeDL.getInstance().versionName(context.applicationContext)
            Result.success(version ?: "ready")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend override fun updateEngine(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val status = YoutubeDL.getInstance()
                .updateYoutubeDL(context.applicationContext, YoutubeDL.UpdateChannel.STABLE)
            val version = YoutubeDL.getInstance().versionName(context.applicationContext)
            Result.success("${status?.name ?: "unknown"} | ${version ?: ""}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend override fun fetchVideoInfo(url: String): Result<VideoInfo?> = withContext(Dispatchers.IO) {
        try {
            ensureInitialized(context)
            val req = YoutubeDLRequest(url)
            req.addOption("--dump-single-json")
            req.addOption("--no-playlist")
            req.addOption("--no-warnings")
            val response = YoutubeDL.getInstance().execute(req, INFO_PROCESS_ID)
            val json = response.out.trim()
            val obj = JSONObject(json)
            val type = obj.optString("_type")
            if (type.equals("playlist", ignoreCase = true) || obj.has("entries")) {
                return@withContext Result.success(null)
            }
            val estimatedSizes = FormatSizeEstimator.estimateSizes(obj)
            val thumb = obj.optString("thumbnail").takeIf { it.isNotBlank() }
                ?: if (obj.has("thumbnails")) {
                    val arr = obj.optJSONArray("thumbnails")
                    if (arr != null && arr.length() > 0) {
                        arr.optJSONObject(arr.length() - 1)?.optString("url")
                    } else null
                } else null
            val finalThumb = thumb ?: com.ytdlpgui.data.model.UrlSanitizer.extractYoutubeId(url)?.let { id: String ->
                "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            }
            val webpageUrl = obj.optString("webpage_url").takeIf { it.isNotBlank() } ?: url

            Result.success(
                VideoInfo(
                    title = obj.optString("title").takeIf { it.isNotBlank() },
                    uploader = obj.optString("uploader").takeIf { it.isNotBlank() },
                    durationSeconds = if (obj.has("duration")) {
                        (obj.getDouble("duration")).toInt() } else null,
                    extractor = obj.optString("extractor_key").takeIf { it.isNotBlank() },
                    formatLabel = obj.optString("format").takeIf { it.isNotBlank() },
                    estimatedSizes = estimatedSizes,
                    thumbnailUrl = finalThumb,
                    webpageUrl = webpageUrl
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend override fun fetchPlaylist(url: String): Result<List<com.ytdlpgui.data.model.PlaylistEntry>> =
        withContext(Dispatchers.IO) {
            try {
                ensureInitialized(context)
                val req = YoutubeDLRequest(url)
                req.addOption("--flat-playlist")
                req.addOption("--dump-single-json")
                req.addOption("--no-warnings")
                val response = YoutubeDL.getInstance().execute(req, INFO_PROCESS_ID)
                Result.success(PlaylistParser.parse(response.out))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend override fun execute(
        request: DownloadRequest,
        processId: String,
        onProgress: (progress: Int, downloadLine: String?) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            ensureInitialized(context)
            val ytdlRequest = buildRequest(request)
            YoutubeDL.getInstance().execute(ytdlRequest, processId) { progress, _, text ->
                onProgress(progress.toInt().coerceIn(0, 100), text.takeIf { it.isNotBlank() })
            }
            DownloadResult.Completed
        } catch (e: Exception) {
            if (e.message?.contains("destroy", ignoreCase = true) == true ||
                e.message?.contains("killed", ignoreCase = true) == true
            ) {
                DownloadResult.Cancelled
            } else {
                DownloadResult.Failed(e.message ?: "unknown error")
            }
        }
    }

    suspend override fun destroyProcess(processId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            YoutubeDL.getInstance().destroyProcessById(processId)
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getEngineVersion(): String = withContext(Dispatchers.IO) {
        try {
            YoutubeDL.getInstance().versionName(context.applicationContext) ?: "ready"
        } catch (_: Exception) {
            "ready"
        }
    }

    private fun buildRequest(request: DownloadRequest): YoutubeDLRequest {
        val ytdlRequest = YoutubeDLRequest(request.url)
        YtdlCommandBuilder.buildArgs(request).forEach { arg ->
            when (arg) {
                is YtdlCommandBuilder.Arg.Flag -> ytdlRequest.addOption(arg.name)
                is YtdlCommandBuilder.Arg.Option -> ytdlRequest.addOption(arg.name, arg.value)
            }
        }
        return ytdlRequest
    }
}
