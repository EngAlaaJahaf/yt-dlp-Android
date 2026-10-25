package com.ytdlpgui.data.engine

import com.ytdlpgui.data.model.DownloadRequest
import com.ytdlpgui.data.model.PlaylistEntry
import com.ytdlpgui.data.model.VideoInfo

/**
 * عقد المحرك — قابل للاستبدال بـFake في اختبارات الوحدة.
 */
interface EngineApi {
    suspend fun initialize(): Result<String>
    suspend fun updateEngine(): Result<String>
    suspend fun execute(
        request: DownloadRequest,
        processId: String,
        onProgress: (progress: Int, downloadLine: String?) -> Unit
    ): DownloadResult

    suspend fun destroyProcess(processId: String): Boolean
    suspend fun fetchVideoInfo(url: String): Result<VideoInfo?>
    suspend fun fetchPlaylist(url: String): Result<List<PlaylistEntry>>
}
