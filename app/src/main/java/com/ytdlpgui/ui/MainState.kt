package com.ytdlpgui.ui

import com.ytdlpgui.data.model.PlaylistEntry
import com.ytdlpgui.data.model.VideoInfo

sealed class MainUiState {
    object Idle : MainUiState()
    object Initializing : MainUiState()
    object Ready : MainUiState()
    object Downloading : MainUiState()
    object Paused : MainUiState()
    object UpdatingEngine : MainUiState()
    data class InitFailed(val message: String) : MainUiState()
}

sealed class InfoUi {
    object Loading : InfoUi()
    data class Loaded(val info: VideoInfo) : InfoUi()
    data class Playlist(val entries: List<PlaylistEntry>) : InfoUi()
    data class Failed(val message: String) : InfoUi()
}
