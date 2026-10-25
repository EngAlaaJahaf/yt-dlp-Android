package com.ytdlpgui.data.engine

sealed class DownloadResult {
    object Completed : DownloadResult()
    object Cancelled : DownloadResult()
    data class Failed(val message: String) : DownloadResult()
}
