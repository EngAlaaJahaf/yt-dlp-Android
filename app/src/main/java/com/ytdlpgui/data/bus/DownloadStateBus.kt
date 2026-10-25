package com.ytdlpgui.data.bus

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

enum class BusStatus {
    IDLE, DOWNLOADING, PAUSED, COMPLETED, CANCELLED, FAILED, UPDATING
}

/** أحداث موجّهة للمستخدم عبر Toast في الواجهة */
sealed interface UiEvent {
    data class ItemDone(val rowId: Long, val title: String?) : UiEvent
    data class ItemFailed(val rowId: Long, val message: String) : UiEvent
}

data class DownloadProgressInfo(
    val rowId: Long,
    val percent: Int,
    val downloadedStr: String? = null,
    val totalStr: String? = null,
    val speedStr: String? = null,
    val etaStr: String? = null,
    val title: String? = null
) {
    val displaySummary: String
        get() = buildString {
            if (!downloadedStr.isNullOrBlank() && !totalStr.isNullOrBlank()) {
                append("$downloadedStr / $totalStr")
            } else if (!totalStr.isNullOrBlank()) {
                append(totalStr)
            }
            if (!speedStr.isNullOrBlank()) {
                if (isNotEmpty()) append(" • ")
                append(speedStr)
            }
            if (!etaStr.isNullOrBlank() && etaStr != "Unknown") {
                if (isNotEmpty()) append(" • ")
                append(etaStr)
            }
        }
}

class DownloadStateBus {

    private val _status = MutableStateFlow(BusStatus.IDLE)
    val status: StateFlow<BusStatus> = _status.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val _progressInfo = MutableStateFlow<DownloadProgressInfo?>(null)
    val progressInfo: StateFlow<DownloadProgressInfo?> = _progressInfo.asStateFlow()

    /** حدث تقدم مرتبط بصف محدد من الطابور (rowId, progress%) */
    private val _progressEvents = MutableSharedFlow<Pair<Long, Int>>(extraBufferCapacity = 256)
    val progressEvents: SharedFlow<Pair<Long, Int>> = _progressEvents.asSharedFlow()

    private val _progressInfoEvents = MutableSharedFlow<DownloadProgressInfo>(extraBufferCapacity = 256)
    val progressInfoEvents: SharedFlow<DownloadProgressInfo> = _progressInfoEvents.asSharedFlow()

    private val _logEvents = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 128)
    val logEvents: SharedFlow<String> = _logEvents.asSharedFlow()

    private val _uiEvents = MutableSharedFlow<UiEvent>(replay = 0, extraBufferCapacity = 32)
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()

    fun emitUiEvent(event: UiEvent) {
        _uiEvents.tryEmit(event)
    }

    private val _lastError = MutableStateFlow("")
    val lastError: StateFlow<String> = _lastError.asStateFlow()

    private val activeItems = AtomicInteger(0)
    private val activeProgressMap = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    fun emitProgressInfo(info: DownloadProgressInfo) {
        _progressInfo.value = info
        _progressInfoEvents.tryEmit(info)
        emitProgressEvent(info.rowId, info.percent)
    }

    fun emitProgressEvent(rowId: Long, progress: Int) {
        activeProgressMap[rowId] = progress
        val avg = if (activeProgressMap.isNotEmpty()) {
            activeProgressMap.values.average().toInt().coerceIn(0, 100)
        } else progress
        _progress.value = avg
        _progressEvents.tryEmit(rowId to progress)
    }

    fun itemStart(rowId: Long? = null) {
        if (rowId != null) activeProgressMap[rowId] = 0
        val count = activeItems.incrementAndGet()
        if (count == 1) setStatus(BusStatus.DOWNLOADING)
    }

    fun itemEnd(rowId: Long? = null) {
        if (rowId != null) activeProgressMap.remove(rowId)
        val remaining = activeItems.decrementAndGet()
        if (remaining <= 0) {
            activeProgressMap.clear()
            _progress.value = 0
            _progressInfo.value = null
        }
    }

    val activeCount: Int
        get() = activeItems.get()

    fun setStatus(status: BusStatus) {
        _status.value = status
        if (status != BusStatus.DOWNLOADING && status != BusStatus.UPDATING && status != BusStatus.PAUSED) {
            _progress.value = 0
            _progressInfo.value = null
        }
    }

    /** يمسح بيانات التقدم القديمة من الواجهة دون المساس بأي تنزيل نشط */
    fun clearProgressInfo() {
        if (activeItems.get() > 0) return
        _progressInfo.value = null
        _progress.value = 0
    }

    fun emitLog(line: String) {
        _logEvents.tryEmit(line)
    }

    fun setError(message: String) {
        _lastError.value = message
    }
}
