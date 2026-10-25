package com.ytdlpgui.ui

import android.content.Context
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytdlpgui.data.bus.BusStatus
import com.ytdlpgui.data.bus.DownloadStateBus
import com.ytdlpgui.data.db.DownloadEntry
import com.ytdlpgui.data.db.DownloadStatus
import com.ytdlpgui.data.db.DownloadStore
import com.ytdlpgui.data.engine.DownloadEngine
import com.ytdlpgui.data.engine.EngineApi
import com.ytdlpgui.data.model.DownloadRequest
import com.ytdlpgui.data.model.RequestCodec
import com.ytdlpgui.data.settings.SettingsRepository
import com.ytdlpgui.data.settings.SettingsStore
import com.ytdlpgui.service.DownloadService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File

class MainViewModel(
    private val engine: EngineApi,
    private val store: DownloadStore,
    private val settings: SettingsStore,
    private val startService: () -> Unit,
    private val stopService: () -> Unit,
    private val serviceBus: DownloadStateBus = DownloadService.bus,
) : ViewModel() {

    private val _uiState = MutableStateFlow<MainUiState>(MainUiState.Initializing)
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    val progressInfo: StateFlow<com.ytdlpgui.data.bus.DownloadProgressInfo?> = serviceBus.progressInfo

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _engineVersion = MutableStateFlow("")
    val engineVersion: StateFlow<String> = _engineVersion.asStateFlow()

    private val _infoState = MutableStateFlow<InfoUi?>(null)
    val infoState: StateFlow<InfoUi?> = _infoState.asStateFlow()

    private val _logExpanded = MutableStateFlow(true)
    val logExpanded: StateFlow<Boolean> = _logExpanded.asStateFlow()

    /** رسالة Toast صريحة تنتظر العرض في الواجهة (تُستهلك بعد العرض) */
    data class ToastMessage(val resId: Int, val arg: String? = null)

    private val _toastMessage = MutableStateFlow<ToastMessage?>(null)
    val toastMessage: StateFlow<ToastMessage?> = _toastMessage.asStateFlow()

    fun consumeToastMessage() {
        _toastMessage.value = null
    }

    /** أحداث اكتمال/فشل العناصر الموجهة للواجهة (من الخدمة عبر الـbus) */
    val uiEvents: kotlinx.coroutines.flow.SharedFlow<com.ytdlpgui.data.bus.UiEvent> =
        serviceBus.uiEvents

    /** يمنع الطلبات المتزامنة المزدوجة (نقرات سريعة) قبل تثبيت الحالة الجديدة */
    private var requestInFlight = false

    val savedFolder: kotlinx.coroutines.flow.Flow<com.ytdlpgui.data.settings.SettingsStore.FolderSelection> = settings.savedFolder
    val savedIndices: kotlinx.coroutines.flow.Flow<Pair<Int, Int>> = settings.savedIndices
    val cookiesPath: kotlinx.coroutines.flow.Flow<String?> = settings.cookiesPath

    init {
        initEngine()
        cleanupStaleRows()
        observeServiceBus()
    }

    private fun cleanupStaleRows() {
        if (serviceBus.status.value == BusStatus.DOWNLOADING) return
        viewModelScope.launch { store.cancelAllRunning() }
    }

    private fun initEngine() {
        _uiState.value = MainUiState.Initializing
        appendLog("⏳ يتم تجهيز المحرك...")
        viewModelScope.launch {
            engine.initialize()
                .onSuccess { version ->
                    _engineVersion.value = version
                    _uiState.value = MainUiState.Ready
                    appendLog("✅ محرك yt-dlp و FFmpeg جاهزان (الإصدار: $version)")
                }
                .onFailure { e ->
                    _uiState.value = MainUiState.InitFailed(e.message ?: "unknown")
                    appendLog("❌ فشل تهيئة المحرك: ${e.message}")
                }
        }
    }

    private fun observeServiceBus() {
        viewModelScope.launch {
            serviceBus.status.collect { status ->
                when (status) {
                    BusStatus.DOWNLOADING -> _uiState.value = MainUiState.Downloading
                    BusStatus.PAUSED -> _uiState.value = MainUiState.Paused
                    BusStatus.COMPLETED, BusStatus.CANCELLED, BusStatus.FAILED -> {
                        if (_uiState.value == MainUiState.Downloading || _uiState.value == MainUiState.Paused) {
                            _uiState.value = MainUiState.Ready
                            _progress.value = 0
                        }
                    }
                    BusStatus.IDLE -> {
                        if (_uiState.value == MainUiState.Downloading) {
                            _uiState.value = MainUiState.Ready
                            _progress.value = 0
                        }
                    }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            serviceBus.logEvents.collect { appendLog(it) }
        }
        viewModelScope.launch {
            serviceBus.progress.collect { _progress.value = it }
        }
    }

    fun retryInit() {
        if (_uiState.value is MainUiState.InitFailed) initEngine()
    }

    fun startDownload(request: DownloadRequest) {
        if (requestInFlight) return
        if (_uiState.value != MainUiState.Ready) {
            // استرداد من حالة عالقة بعد موت الخدمة (طابور فارغ/عملية ماتت) — لا شيء ينزَّل فعلياً
            val stale = _uiState.value
            if (stale is MainUiState.Downloading || stale is MainUiState.Paused) {
                if (serviceBus.status.value == BusStatus.DOWNLOADING) return
                _uiState.value = MainUiState.Ready
            } else {
                return
            }
        }
        requestInFlight = true
        viewModelScope.launch {
            try {
                val cleanUrl = com.ytdlpgui.data.model.UrlSanitizer.cleanSingleVideoUrl(request.url) ?: request.url
                if (com.ytdlpgui.data.model.UrlSanitizer.isPlaylistOrChannel(cleanUrl)) {
                    appendLog("📋 تم رصد رابط قناة/قائمة تشغيل — جارٍ جلب العناصر...")
                    fetchInfo(cleanUrl)
                    return@launch
                }

                val cleanRequest = request.copy(url = cleanUrl)
                val key = com.ytdlpgui.data.model.UrlSanitizer.normalizeKey(cleanRequest.url)
                val existing = key?.let { k ->
                    store.recent().firstOrNull {
                        com.ytdlpgui.data.model.UrlSanitizer.normalizeKey(it.url) == k &&
                            it.formatLabel.equals(cleanRequest.format.label, ignoreCase = true)
                    }
                }
                appendLog("🔎 فحص التكرار: مفتاح=$key — موجود=${existing?.status ?: "لا"}")
                if (existing != null && existing.status != DownloadStatus.FAILED &&
                    existing.status != DownloadStatus.CANCELLED
                ) {
                    _toastMessage.value = if (existing.status == DownloadStatus.DONE)
                        ToastMessage(com.ytdlpgui.R.string.msg_already_downloaded)
                    else
                        ToastMessage(com.ytdlpgui.R.string.msg_already_in_queue)
                    appendLog("⛔ عناصر مسبقة: حالة=${existing.status} لهذا الرابط")
                    return@launch
                }
                _uiState.value = MainUiState.Downloading
                _progress.value = 0
                val cookies = settings.cookiesPath.firstOrNull()
                insertRow(cleanRequest.copy(cookiesPath = cookies))
                try {
                    startService()
                } catch (e: Exception) {
                    appendLog("⚠️ تعذر بدء خدمة التنزيل: ${e.message}")
                    _uiState.value = MainUiState.Ready
                    _progress.value = 0
                }
            } finally {
                requestInFlight = false
            }
        }
    }

    fun startPlaylistDownload(
        entries: List<com.ytdlpgui.data.model.PlaylistEntry>,
        quality: com.ytdlpgui.data.model.DownloadQuality,
        format: com.ytdlpgui.data.model.OutputFormat,
        dir: String
    ) {
        if (_uiState.value != MainUiState.Ready || requestInFlight) return
        if (entries.isEmpty()) return
        requestInFlight = true
        viewModelScope.launch {
            try {
                val recent = store.recent()
                fun existingKey(u: String) = com.ytdlpgui.data.model.UrlSanitizer.normalizeKey(u)
                val doneKeys = recent.filter { it.status == DownloadStatus.DONE }
                    .mapNotNull { existingKey(it.url) }.toSet()
                val pendingKeys = recent.filter {
                    it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.RUNNING ||
                        it.status == DownloadStatus.PAUSED
                }.mapNotNull { existingKey(it.url) }.toSet()

                var skipped = 0
                val toQueue = entries.filter { entry ->
                    val key = existingKey(entry.url)
                    when {
                        key == null -> true
                        key in doneKeys || key in pendingKeys -> { skipped++; false }
                        else -> true
                    }
                }
                if (skipped > 0) {
                    _toastMessage.value = ToastMessage(
                        com.ytdlpgui.R.string.msg_playlist_skipped_fmt,
                        skipped.toString()
                    )
                }
                if (toQueue.isEmpty()) return@launch

                _uiState.value = MainUiState.Downloading
                _progress.value = 0
                val cookies = settings.cookiesPath.firstOrNull()
                toQueue.forEach { entry ->
                    insertRow(
                        DownloadRequest(
                            url = entry.url,
                            quality = quality,
                            format = format,
                            downloadDir = dir,
                            cookiesPath = cookies
                        )
                    )
                }
                appendLog("📋 أُضيف ${toQueue.size} عنصراً إلى الطابور")
                try {
                    startService()
                } catch (e: Exception) {
                    appendLog("⚠️ تعذر بدء خدمة التنزيل: ${e.message}")
                    _uiState.value = MainUiState.Ready
                    _progress.value = 0
                }
            } finally {
                requestInFlight = false
            }
        }
    }

    private suspend fun insertRow(request: DownloadRequest): Long {
        val entry = DownloadEntry(
            url = request.url,
            dir = request.downloadDir,
            qualityLabel = request.quality.label,
            formatLabel = request.format.label,
            requestJson = RequestCodec.encode(request),
            status = DownloadStatus.QUEUED
        )
        return store.insert(entry)
    }

    /**
     * إيقاف مؤقت: الخدمة تدمّر العمليات وتعلّم الصفوف PAUSED — الاستئناف متاح في أي وقت.
     */
    fun pauseDownload() {
        if (_uiState.value != MainUiState.Downloading) return
        serviceBus.emitLog("⏸ جارٍ الإيقاف المؤقت...")
        stopService()
        _uiState.value = MainUiState.Paused
    }

    /**
     * استئناف التنزيلات المتوقفة مؤقتاً
     */
    fun resumeDownload() {
        viewModelScope.launch {
            val paused = store.allPaused()
            if (paused.isNotEmpty()) {
                paused.forEach { store.updateStatus(it.id, DownloadStatus.QUEUED) }
                serviceBus.emitLog("▶ جارٍ استئناف التنزيل...")
            }
            _uiState.value = MainUiState.Downloading
            try {
                startService()
            } catch (e: Exception) {
                appendLog("⚠️ تعذر استئناف خدمة التنزيل: ${e.message}")
                _uiState.value = MainUiState.Ready
            }
        }
    }

    /**
     * إلغاء التنزيل الجاري أو المتوقف مؤقتاً
     */
    fun cancelDownload() {
        viewModelScope.launch {
            store.cancelAllRunning()
            store.allPaused().forEach { store.updateStatus(it.id, DownloadStatus.CANCELLED) }
            stopService()
            _uiState.value = MainUiState.Ready
            _progress.value = 0
            serviceBus.emitLog("⏹ تم إلغاء التنزيل")
        }
    }

    fun startUpdateEngine() {
        if (_uiState.value != MainUiState.Ready) return
        _uiState.value = MainUiState.UpdatingEngine
        appendLog("⏳ جارٍ التحقق من تحديثات yt-dlp...")
        viewModelScope.launch {
            engine.updateEngine()
                .onSuccess {
                    val status = it.substringBefore("|").trim()
                    val ver = it.substringAfter("|").trim()
                    _engineVersion.value = ver
                    appendLog("✅ حالة التحديث: $status — الإصدار: $ver")
                    val msgRes = if (status.contains("ALREADY", ignoreCase = true)) {
                        com.ytdlpgui.R.string.msg_engine_already_latest
                    } else {
                        com.ytdlpgui.R.string.msg_engine_updated_fmt
                    }
                    _toastMessage.value = ToastMessage(msgRes, ver)
                    _uiState.value = MainUiState.Ready
                }
                .onFailure { e ->
                    appendLog("⚠️ لا يوجد تحديث أو فشل الاتصال: ${e.message}")
                    _toastMessage.value = ToastMessage(
                        com.ytdlpgui.R.string.msg_engine_update_failed_fmt,
                        e.message
                    )
                    _uiState.value = MainUiState.Ready
                }
        }
    }

    fun fetchInfo(url: String) {
        if (url.isBlank()) return
        if (_infoState.value is InfoUi.Loading) return
        _infoState.value = InfoUi.Loading
        viewModelScope.launch {
            engine.fetchVideoInfo(url)
                .onSuccess { info ->
                    if (info != null) {
                        _infoState.value = InfoUi.Loaded(info)
                    } else {
                        // قائمة تشغيل — جلب الإدخالات
                        appendLog("📋 رابط قائمة تشغيل — جارٍ جلب الإدخالات...")
                        engine.fetchPlaylist(url)
                            .onSuccess { entries ->
                                _infoState.value = if (entries.isEmpty())
                                    InfoUi.Failed(com.ytdlpgui.data.engine.ErrorClassifier.classify("empty playlist"))
                                else InfoUi.Playlist(entries)
                            }
                            .onFailure { e ->
                                _infoState.value = InfoUi.Failed(com.ytdlpgui.data.engine.ErrorClassifier.classify(e.message))
                            }
                    }
                }
                .onFailure { e ->
                    _infoState.value = InfoUi.Failed(com.ytdlpgui.data.engine.ErrorClassifier.classify(e.message))
                }
        }
    }

    fun dismissInfo() {
        _infoState.value = null
    }

    /** يمسح محتوى التحميل السابق من الشاشة (لا يعمل أثناء تنزيل نشط) */
    fun resetDownloadUi() {
        if (_uiState.value == MainUiState.Downloading || _uiState.value == MainUiState.Paused) return
        _progress.value = 0
        serviceBus.clearProgressInfo()
    }

    fun toggleLog() {
        _logExpanded.value = !_logExpanded.value
    }

    fun defaultDownloadDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ClipCatch")

    fun persistFolder(path: String?, treeUri: String?) {
        viewModelScope.launch { settings.saveFolder(path, treeUri) }
    }

    fun persistSelections(path: String, qualityIndex: Int, formatIndex: Int) {
        viewModelScope.launch {
            settings.saveIndices(qualityIndex, formatIndex)
            val resolved = path.ifBlank { defaultDownloadDir().absolutePath }
            settings.saveFolder(resolved, null)
        }
    }

    fun persistCookiesPath(path: String?) {
        viewModelScope.launch { settings.saveCookiesPath(path) }
    }

    private fun appendLog(line: String) {
        _log.value = _log.value + line
    }

    fun clearLog() {
        _log.value = emptyList()
    }
}
