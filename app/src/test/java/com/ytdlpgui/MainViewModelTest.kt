package com.ytdlpgui

import com.ytdlpgui.data.bus.BusStatus
import com.ytdlpgui.data.bus.DownloadStateBus
import com.ytdlpgui.data.db.DownloadEntry
import com.ytdlpgui.data.engine.DownloadResult
import com.ytdlpgui.data.engine.EngineApi
import com.ytdlpgui.data.model.DownloadQuality
import com.ytdlpgui.data.model.DownloadRequest
import com.ytdlpgui.data.model.OutputFormat
import com.ytdlpgui.data.model.PlaylistEntry
import com.ytdlpgui.data.model.VideoContainer
import com.ytdlpgui.data.model.VideoInfo
import com.ytdlpgui.data.settings.SettingsStore
import com.ytdlpgui.ui.MainUiState
import com.ytdlpgui.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private class FakeEngine : EngineApi {
        var failInit = false
        val executed = mutableListOf<DownloadRequest>()
        var completion: DownloadResult = DownloadResult.Completed

        override suspend fun initialize(): Result<String> =
            if (failInit) Result.failure(RuntimeException("boom")) else Result.success("test.9")

        override suspend fun updateEngine(): Result<String> = Result.success("DONE | aloha.1")

        override suspend fun execute(
            request: DownloadRequest,
            processId: String,
            onProgress: (progress: Int, downloadLine: String?) -> Unit
        ): DownloadResult {
            executed.add(request)
            return completion
        }

        override suspend fun destroyProcess(processId: String): Boolean = true

        override suspend fun fetchVideoInfo(url: String): Result<VideoInfo?> =
            Result.success(VideoInfo("t", "ch", 10, null, null))

        override suspend fun fetchPlaylist(url: String): Result<List<com.ytdlpgui.data.model.PlaylistEntry>> =
            Result.success(emptyList())
    }

    private class FakeStore : com.ytdlpgui.data.db.DownloadStore {
        val inserted = mutableListOf<DownloadEntry>()
        var nextId = 1L
        var cancelCalled = 0
        var recentEntries: List<DownloadEntry> = emptyList()
        override suspend fun insert(entry: DownloadEntry): Long {
            val id = nextId++
            inserted.add(entry.copy(id = id))
            return id
        }

        override suspend fun cancelAllRunning() {
            cancelCalled++
        }

        override suspend fun recent(): List<DownloadEntry> = recentEntries
        override suspend fun allPaused(): List<DownloadEntry> = emptyList()
        override suspend fun resume(id: Long) {}
        override suspend fun updateStatus(id: Long, status: Int) {}
    }

    private class FakeSettings : SettingsStore {
        override val savedFolder: Flow<SettingsStore.FolderSelection> =
            MutableStateFlow(SettingsStore.FolderSelection("/sdcard/persist", null))
        override val savedIndices: Flow<Pair<Int, Int>> = MutableStateFlow(2 to 3)
        override val cookiesPath: Flow<String?> = MutableStateFlow(null)
        override val themeDark: Flow<Boolean?> = MutableStateFlow(null)
        override val wifiOnly: Flow<Boolean> = MutableStateFlow(false)
        override val maxConcurrent: Flow<Int> = MutableStateFlow(2)
        override val backgroundDownload: Flow<Boolean> = MutableStateFlow(true)
        override val saveHistory: Flow<Boolean> = MutableStateFlow(true)

        var savedFolderCalls = 0
        var savedIndicesCalls = 0
        var cookiesSaved: String? = null
        var themeDarkSaved: Boolean? = null
        var wifiOnlySaved: Boolean? = null
        var maxConcurrentSaved: Int? = null
        var backgroundDownloadSaved: Boolean? = null
        var saveHistorySaved: Boolean? = null

        override suspend fun saveFolder(path: String?, treeUri: String?) { savedFolderCalls++ }
        override suspend fun saveIndices(qualityIndex: Int, formatIndex: Int) { savedIndicesCalls++ }
        override suspend fun saveCookiesPath(path: String?) { cookiesSaved = path }
        override suspend fun saveThemeDark(dark: Boolean) { themeDarkSaved = dark }
        override suspend fun saveWifiOnly(enabled: Boolean) { wifiOnlySaved = enabled }
        override suspend fun saveMaxConcurrent(count: Int) { maxConcurrentSaved = count }
        override suspend fun saveBackgroundDownload(enabled: Boolean) { backgroundDownloadSaved = enabled }
        override suspend fun saveSaveHistory(enabled: Boolean) { saveHistorySaved = enabled }
    }

    private lateinit var engine: FakeEngine
    private lateinit var store: FakeStore
    private lateinit var settings: FakeSettings
    var serviceStarts = 0
    var serviceStops = 0
    private val bus = DownloadStateBus()

    private fun makeViewModel(): MainViewModel =
        MainViewModel(
            engine = engine,
            store = store,
            settings = settings,
            startService = { serviceStarts++ },
            stopService = { serviceStops++ },
            serviceBus = bus
        )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        engine = FakeEngine()
        store = FakeStore()
        settings = FakeSettings()
        serviceStarts = 0
        serviceStops = 0
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private val sampleRequest = DownloadRequest(
        url = "https://demo.example/watch/abc",
        quality = DownloadQuality.BEST,
        format = OutputFormat.Video(VideoContainer.MP4),
        downloadDir = "/sdcard/Download/app"
    )

    @Test
    fun init_setsReadyWithVersion_andCleansStaleRows() = runTest(dispatcher) {
        val vm = makeViewModel()
        advanceUntilIdle()
        assertEquals(MainUiState.Ready, vm.uiState.value)
        assertEquals("test.9", vm.engineVersion.value)
        assertEquals(1, store.cancelCalled)
    }

    @Test
    fun init_failure_setsInitFailed() = runTest(dispatcher) {
        engine.failInit = true
        val vm = makeViewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value is MainUiState.InitFailed)
        assertTrue(vm.log.value.any { it.contains("فشل تهيئة المحرك") })
    }

    @Test
    fun startDownload_insertsQueued_andStartsService() = runTest(dispatcher) {
        val vm = makeViewModel()
        advanceUntilIdle()

        vm.startDownload(sampleRequest)
        advanceUntilIdle()

        assertEquals(1, store.inserted.size)
        assertEquals(com.ytdlpgui.data.db.DownloadStatus.QUEUED, store.inserted[0].status)
        assertEquals(1, serviceStarts)
        assertEquals(MainUiState.Downloading, vm.uiState.value)
        assertEquals(0, engine.executed.size) // المحرك تُدار عبر الخدمة فقط
    }

    @Test
    fun startDownload_ignoredWhenNotReady() = runTest(dispatcher) {
        engine.failInit = true
        val vm = makeViewModel()
        advanceUntilIdle()

        vm.startDownload(sampleRequest)
        advanceUntilIdle()

        assertEquals(0, store.inserted.size)
        assertEquals(0, serviceStarts)
    }

    @Test
    fun startDownload_alreadyDoneBlocks_andShowsToast() = runTest(dispatcher) {
        store.recentEntries = listOf(
            DownloadEntry(
                id = 9, url = "https://www.youtube.com/watch?v=abc",
                dir = "/d", qualityLabel = "q", formatLabel = sampleRequest.format.label,
                requestJson = "{}", status = com.ytdlpgui.data.db.DownloadStatus.DONE
            )
        )
        val vm = makeViewModel()
        advanceUntilIdle()

        // صيغة مختلفة لنفس الفيديو — يجب أن يُكتشف
        vm.startDownload(sampleRequest.copy(url = "https://youtu.be/abc"))
        advanceUntilIdle()

        assertEquals(0, store.inserted.size)
        assertEquals(0, serviceStarts)
        assertEquals(MainUiState.Ready, vm.uiState.value)
        assertEquals(
            com.ytdlpgui.R.string.msg_already_downloaded,
            vm.toastMessage.value?.resId
        )
        vm.consumeToastMessage()
        assertEquals(null, vm.toastMessage.value)
    }

    @Test
    fun startDownload_alreadyQueuedBlocks_withQueueToast() = runTest(dispatcher) {
        store.recentEntries = listOf(
            DownloadEntry(
                id = 5, url = "https://youtu.be/abc",
                dir = "/d", qualityLabel = "q", formatLabel = sampleRequest.format.label,
                requestJson = "{}", status = com.ytdlpgui.data.db.DownloadStatus.RUNNING
            )
        )
        val vm = makeViewModel()
        advanceUntilIdle()

        vm.startDownload(sampleRequest.copy(url = "https://www.youtube.com/watch?v=abc"))
        advanceUntilIdle()

        assertEquals(0, store.inserted.size)
        assertEquals(0, serviceStarts)
        assertEquals(com.ytdlpgui.R.string.msg_already_in_queue, vm.toastMessage.value?.resId)
    }

    @Test
    fun startDownload_pastFailedRow_isNotDuplicate() = runTest(dispatcher) {
        store.recentEntries = listOf(
            DownloadEntry(
                id = 7, url = "https://youtu.be/abc",
                dir = "/d", qualityLabel = "q", formatLabel = sampleRequest.format.label,
                requestJson = "{}", status = com.ytdlpgui.data.db.DownloadStatus.FAILED
            )
        )
        val vm = makeViewModel()
        advanceUntilIdle()

        vm.startDownload(sampleRequest.copy(url = "https://youtu.be/abc"))
        advanceUntilIdle()

        assertEquals(1, store.inserted.size)
        assertEquals(1, serviceStarts)
        assertEquals(null, vm.toastMessage.value)
    }

    @Test
    fun startPlaylistDownload_skipsAlreadyDownloaded_andToastsCount() = runTest(dispatcher) {
        store.recentEntries = listOf(
            DownloadEntry(
                id = 3, url = "https://youtu.be/done1",
                dir = "/d", qualityLabel = "q", formatLabel = "f",
                requestJson = "{}", status = com.ytdlpgui.data.db.DownloadStatus.DONE
            )
        )
        val vm = makeViewModel()
        advanceUntilIdle()

        vm.startPlaylistDownload(
            listOf(
                PlaylistEntry(url = "https://youtu.be/done1", title = "a", durationSeconds = null),
                PlaylistEntry(url = "https://youtu.be/new2", title = "b", durationSeconds = null)
            ),
            quality = DownloadQuality.BEST,
            format = OutputFormat.Video(VideoContainer.MP4),
            dir = "/d"
        )
        advanceUntilIdle()

        assertEquals(1, store.inserted.size)
        assertEquals("https://youtu.be/new2", store.inserted[0].url)
        assertEquals(
            com.ytdlpgui.R.string.msg_playlist_skipped_fmt,
            vm.toastMessage.value?.resId
        )
        assertEquals("1", vm.toastMessage.value?.arg)
    }

    @Test
    fun startPlaylistDownload_allDone_queuesNothing() = runTest(dispatcher) {
        store.recentEntries = listOf(
            DownloadEntry(
                id = 3, url = "https://youtu.be/done1",
                dir = "/d", qualityLabel = "q", formatLabel = "f",
                requestJson = "{}", status = com.ytdlpgui.data.db.DownloadStatus.DONE
            )
        )
        val vm = makeViewModel()
        advanceUntilIdle()

        vm.startPlaylistDownload(
            listOf(PlaylistEntry(url = "https://youtu.be/done1", title = "a", durationSeconds = null)),
            quality = DownloadQuality.BEST,
            format = OutputFormat.Video(VideoContainer.MP4),
            dir = "/d"
        )
        advanceUntilIdle()

        assertEquals(0, store.inserted.size)
        assertEquals(0, serviceStarts)
        assertEquals(MainUiState.Ready, vm.uiState.value)
    }

    @Test
    fun busStatus_completeRestoresReady() = runTest(dispatcher) {
        val vm = makeViewModel()
        advanceUntilIdle()

        vm.startDownload(sampleRequest)
        advanceUntilIdle()
        assertEquals(MainUiState.Downloading, vm.uiState.value)

        bus.setStatus(BusStatus.COMPLETED)
        advanceUntilIdle()
        assertEquals(MainUiState.Ready, vm.uiState.value)
        assertEquals(0, vm.progress.value)
    }

    @Test
    fun pauseDownload_onlyWhileDownloading() = runTest(dispatcher) {
        val vm = makeViewModel()
        advanceUntilIdle()

        vm.startDownload(sampleRequest)
        advanceUntilIdle()
        vm.pauseDownload()
        advanceUntilIdle()
        assertEquals(1, serviceStops)
    }

    @Test
    fun updateEngine_reportsVersion_andRestoresReady() = runTest(dispatcher) {
        val vm = makeViewModel()
        advanceUntilIdle()

        vm.startUpdateEngine()
        advanceUntilIdle()
        assertEquals(MainUiState.Ready, vm.uiState.value)
        assertEquals("aloha.1", vm.engineVersion.value)
        assertEquals(com.ytdlpgui.R.string.msg_engine_updated_fmt, vm.toastMessage.value?.resId)
    }

    @Test
    fun toggleLog_flips() {
        val vm = makeViewModel()
        val initial = vm.logExpanded.value
        vm.toggleLog()
        assertEquals(!initial, vm.logExpanded.value)
    }

    @Test
    fun startDownload_channelUrl_routesToFetchInfo() = runTest(dispatcher) {
        val vm = makeViewModel()
        advanceUntilIdle()

        vm.startDownload(sampleRequest.copy(url = "https://www.youtube.com/@someChannel/videos"))
        advanceUntilIdle()

        assertEquals(0, store.inserted.size)
        assertEquals(0, serviceStarts)
        assertTrue(vm.infoState.value != null)
    }
}
