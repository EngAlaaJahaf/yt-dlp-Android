package com.ytdlpgui.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.ytdlpgui.MainActivity
import com.ytdlpgui.R
import com.ytdlpgui.data.bus.BusStatus
import com.ytdlpgui.data.bus.DownloadStateBus
import com.ytdlpgui.data.bus.UiEvent
import com.ytdlpgui.data.db.DownloadDatabase
import com.ytdlpgui.data.db.DownloadStatus
import com.ytdlpgui.data.engine.DownloadEngine
import com.ytdlpgui.data.engine.DownloadResult
import com.ytdlpgui.data.engine.ErrorClassifier
import com.ytdlpgui.data.model.DownloadRequest
import com.ytdlpgui.data.model.RequestCodec
import com.ytdlpgui.data.model.UrlSanitizer
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DownloadService : LifecycleService() {

    private lateinit var engine: DownloadEngine
    private val db by lazy { DownloadDatabase.get(this) }
    private val pumpRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    /** الصفوف التي طُلب إيقافها مؤقتاً — تميّز PAUSED عن CANCELLED عند اكتمال التدمير */
    private val pausedRows = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /** عنوان الفيديو المرصود من مخرجات yt-dlp — يظهر في بطاقة الإشعار */
    @Volatile private var notifTitle: String? = null

    override fun onCreate() {
        super.onCreate()
        engine = DownloadEngine(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> handlePauseAll()
            ACTION_PAUSE_ITEM -> {
                val rowId = intent.getLongExtra(EXTRA_ROW_ID, -1L)
                if (rowId > 0) handlePauseItem(rowId)
            }
            ACTION_RESUME_ITEM -> {
                val rowId = intent.getLongExtra(EXTRA_ROW_ID, -1L)
                if (rowId > 0) handleResumeItem(rowId)
            }
            ACTION_CANCEL_ITEM -> {
                val rowId = intent.getLongExtra(EXTRA_ROW_ID, -1L)
                if (rowId > 0) handleCancelItem(rowId)
            }
            ACTION_START -> {
                notifTitle = null
                startForegroundCompat(buildNotification(0))
                lifecycleScope.launch { pump() }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * المضخة: تسحب عناصر من الطابور وتنزّلها بالتوازي (حتى CONCURRENCY) حتى ينضب الطابور.
     */
    private suspend fun pump() {
        if (!pumpRunning.compareAndSet(false, true)) return
        try {
            while (true) {
                val batch = mutableListOf<Pair<DownloadRequest, Long>>()
                repeat(CONCURRENCY) {
                    nextValidQueuedRow()?.also { item ->
                        db.downloadDao().updateStatus(item.second, DownloadStatus.RUNNING)
                        batch += item
                    }
                }
                if (batch.isEmpty()) break

                val jobs = batch.map { (request, rowId) ->
                    lifecycleScope.launch {
                        runSingle(request, rowId)
                    }
                }
                jobs.forEach { it.join() }
            }

            if (bus.activeCount == 0) {
                bus.setStatus(BusStatus.IDLE)
                stopForegroundCompat()
                stopSelf()
            } else {
                // الطابور فرغ لكن عناصر مدمجة/نشطة لازالت تعمل — راقب حتى تنتهي ثم أوقف الخدمة
                lifecycleScope.launch {
                    while (bus.activeCount > 0) delay(1000)
                    if (pumpRunning.get()) return@launch
                    bus.setStatus(BusStatus.IDLE)
                    stopForegroundCompat()
                    stopSelf()
                }
            }
        } finally {
            pumpRunning.set(false)
        }
    }

    private suspend fun nextValidQueuedRow(): Pair<DownloadRequest, Long>? {
        while (true) {
            val entry = db.downloadDao().nextQueued() ?: return null
            val req = RequestCodec.decode(entry.requestJson)
            if (req == null) {
                db.downloadDao().updateStatus(entry.id, DownloadStatus.FAILED, error = "bad request payload")
                continue
            }
            return req to entry.id
        }
    }

    private val activeTitles = java.util.concurrent.ConcurrentHashMap<Long, String>()
    @Volatile private var lastNotificationTime = 0L

    private suspend fun runSingle(request: DownloadRequest, rowId: Long) {
        bus.itemStart(rowId)
        val initialTitle = shortTitleFor(request.url)
        activeTitles[rowId] = initialTitle
        db.downloadDao().updateStatus(rowId, DownloadStatus.RUNNING)
        bus.emitLog("🚀 بدء التحميل: ${request.url}")

        var lastDbPct = 0
        var displayTitle = initialTitle
        var outputPath: String? = null
        val result: DownloadResult = try {
            engine.execute(request, processIdFor(rowId)) { progress, line ->
            var currentPercent = progress
            if (!line.isNullOrBlank()) {
                extractOutputPath(line)?.let { outputPath = it }
                extractTitle(line)?.let { t ->
                    displayTitle = t
                    activeTitles[rowId] = t
                }
                val info = com.ytdlpgui.data.engine.DownloadProgressParser.parse(rowId, line, progress, displayTitle)
                currentPercent = info.percent
                bus.emitProgressInfo(info)
            } else {
                bus.emitProgressEvent(rowId, progress)
            }

            if (currentPercent % 25 == 0 && currentPercent > 0) bus.emitLog("التحميل: $currentPercent%")
            val now = System.currentTimeMillis()
            if (now - lastNotificationTime > 400 || currentPercent == 100) {
                lastNotificationTime = now
                updateNotification(bus.progress.value)
            }
            if (currentPercent / 10 > lastDbPct / 10) {
                lastDbPct = currentPercent
                lifecycleScope.launch { db.downloadDao().updateProgress(rowId, currentPercent) }
            }
        }
        } catch (e: Exception) {
            android.util.Log.w("DownloadService", "engine crash row=$rowId: ${e.message}", e)
            DownloadResult.Failed(e.message ?: "internal error")
        }

        activeTitles.remove(rowId)
        when (result) {
            is DownloadResult.Completed -> {
                db.downloadDao().updateStatus(rowId, DownloadStatus.DONE, progress = 100)
                val finalFile = resolveCompletedFile(request, outputPath)
                db.downloadDao().updateFileInfo(rowId, finalFile?.absolutePath, finalFile?.length() ?: 0L)
                bus.emitLog("✅ اكتمل التحميل: ${request.url}")
                bus.emitUiEvent(UiEvent.ItemDone(rowId, displayTitle))
            }
            is DownloadResult.Cancelled -> {
                if (pausedRows.remove(rowId)) {
                    db.downloadDao().updateStatus(rowId, DownloadStatus.PAUSED)
                    bus.emitLog("⏸ أُوقف مؤقتاً (قابل للاستئناف): ${request.url}")
                } else {
                    db.downloadDao().updateStatus(rowId, DownloadStatus.CANCELLED)
                    bus.emitLog("⏹ تم إلغاء التحميل: ${request.url}")
                }
            }
            is DownloadResult.Failed -> {
                if (pausedRows.remove(rowId)) {
                    // الإيقاف المؤقت يقتل العملية فترجع المكتبة FAILED — اعتبرها إيقافاً مؤقتاً
                    db.downloadDao().updateStatus(rowId, DownloadStatus.PAUSED)
                    bus.emitLog("⏸ أُوقف مؤقتاً (قابل للاستئناف): ${request.url}")
                } else {
                    db.downloadDao().updateStatus(rowId, DownloadStatus.FAILED, error = result.message)
                    bus.emitLog("❌ خطأ: ${ErrorClassifier.classify(result.message)}")
                    bus.setError(result.message)
                    bus.emitUiEvent(UiEvent.ItemFailed(rowId, ErrorClassifier.classify(result.message)))
                }
            }
        }
        bus.itemEnd(rowId)
    }

    /**
     * "الإيقاف" من الشاشة الرئيسية = إيقاف مؤقت: تُحفظ ملفات .part والاستئناف ممكن من الطابور.
     */
    private fun handlePauseAll() {
        lifecycleScope.launch {
            db.downloadDao().allQueued().forEach { row ->
                pausedRows.add(row.id)
                db.downloadDao().updateStatus(row.id, DownloadStatus.PAUSED)
            }
            db.downloadDao().allRunning().forEach { row ->
                pausedRows.add(row.id)
                engine.destroyProcess(processIdFor(row.id))
            }
            bus.setStatus(BusStatus.PAUSED)
            bus.emitLog("⏸️ تم إيقاف التنزيلات مؤقتاً")
        }
    }

    private fun handlePauseItem(rowId: Long) {
        lifecycleScope.launch {
            pausedRows.add(rowId)
            val row = db.downloadDao().byId(rowId) ?: return@launch
            if (row.status == DownloadStatus.RUNNING) {
                engine.destroyProcess(processIdFor(rowId))
            } else if (row.status == DownloadStatus.QUEUED) {
                db.downloadDao().updateStatus(rowId, DownloadStatus.PAUSED)
            }
            bus.emitLog("⏸ أُوقف التنزيل مؤقتاً.")
        }
    }

    private fun handleResumeItem(rowId: Long) {
        lifecycleScope.launch {
            db.downloadDao().resumePaused(rowId)
            bus.emitLog("▶ استئناف التنزيل.")
            startForegroundCompat(buildNotification(bus.progress.value))
            pump()
        }
    }

    private fun handleCancelItem(rowId: Long) {
        lifecycleScope.launch {
            val row = db.downloadDao().byId(rowId) ?: return@launch
            if (row.status == DownloadStatus.RUNNING) {
                engine.destroyProcess(processIdFor(rowId))
            } else if (row.status == DownloadStatus.QUEUED || row.status == DownloadStatus.PAUSED) {
                db.downloadDao().updateStatus(rowId, DownloadStatus.CANCELLED)
            }
        }
    }

    /**
     * استخراج عنوان الفيديو من مخرجات yt-dlp:
     * "Destination: <مسار>/%(title)s.%(ext)s[.part]" أو "Merging formats into \"<مسار>\"" أو "has already been downloaded"
     */
    private fun extractTitle(line: String): String? {
        val raw = Regex("Destination: (.+)$").find(line)?.groupValues?.get(1)
            ?: Regex("Merging formats into \"([^\"]+)\"").find(line)?.groupValues?.get(1)
            ?: if (line.contains("has already been downloaded")) {
                line.replaceFirst(Regex("^\\[download\\] "), "").substringBefore(" has already")
            } else null
            ?: return null

        var name = raw.trim().substringAfterLast('/')
        if (name.endsWith(".part", ignoreCase = true)) name = name.dropLast(5)
        name = name.substringBeforeLast('.')
        name = name.replace(Regex("\\.f\\d+$"), "")
        val clean = name.replace(Regex("\\s*\\[[a-zA-Z0-9_-]{4,}\\]$"), "").trim()
        return (if (clean.isNotBlank()) clean else name).takeIf { it.isNotBlank() }
    }

    /**
     * استخراج مسار الملف النهائي من مخرجات yt-dlp:
     * "Destination: <path>", "[Merger] Merging formats into \"<path>\"" أو "has already been downloaded"
     */
    private fun extractOutputPath(line: String): String? {
        Regex("Merging formats into \"([^\"]+)\"").find(line)?.groupValues?.get(1)?.let {
            return cleanOutputPath(it)
        }
        Regex("Destination: (.+)$").find(line)?.groupValues?.get(1)?.let {
            return cleanOutputPath(it)
        }
        if (line.contains("has already been downloaded")) {
            return cleanOutputPath(
                line.replaceFirst(Regex("^\\[download\\] "), "").substringBefore(" has already")
            )
        }
        return null
    }

    private fun cleanOutputPath(raw: String): String {
        val trimmed = raw.trim().trim('"')
        return if (trimmed.endsWith(".part", ignoreCase = true)) trimmed.dropLast(5) else trimmed
    }

    /**
     * يحدد ملف المخرجات النهائي بعد الاكتمال — يستخدم المسار المرصود من السجل
     * أو يبحث عن الملف الذي يحمل معرّف الفيديو داخل مجلد التنزيل.
     */
    private fun resolveCompletedFile(request: DownloadRequest, candidate: String?): File? {
        val direct = candidate?.let { File(it) }
        if (direct != null && direct.isFile && direct.exists()) return direct

        val dir = File(request.downloadDir)
        val files = dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part", ignoreCase = true) }
            ?: emptyList()
        val key = UrlSanitizer.normalizeKey(request.url)?.substringAfter(":")
        if (!key.isNullOrBlank()) {
            files.filter { it.name.contains(key) }
                .maxByOrNull { it.lastModified() }
                ?.let { return it }
        }
        return direct?.takeIf { it.isFile && it.exists() }
    }

    private fun shortTitleFor(url: String): String = url.replaceFirst(Regex("^https?://"), "").take(50)

    override fun onDestroy() {
        if (bus.activeCount > 0) {
            lifecycleScope.launch {
                db.downloadDao().allRunning().forEach { row ->
                    engine.destroyProcess(processIdFor(row.id))
                }
                bus.emitLog("⏹ توقفت التنزيلات (أُغلقت الخدمة).")
            }
        }
        super.onDestroy()
    }

    private fun buildNotification(progress: Int): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val activeCount = bus.activeCount
        val title = if (activeCount > 1) {
            getString(R.string.notif_title) + " ($activeCount عناصر)"
        } else {
            activeTitles.values.firstOrNull()?.takeIf { it.isNotBlank() } ?: getString(R.string.notif_title)
        }
        val info = bus.progressInfo.value
        val contentText = if (info != null && info.displaySummary.isNotBlank()) {
            "${info.percent}% • ${info.displaySummary}"
        } else {
            getString(R.string.notif_progress, progress)
        }

        val pauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title.take(60))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, progress <= 0)
            .setContentIntent(openAppIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.status_paused), pauseIntent)

        if (activeTitles.isNotEmpty()) {
            val list = activeTitles.values.joinToString("\n")
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(list))
        }

        return builder.build()
    }

    private fun updateNotification(progress: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(progress))
    }

    private fun startForegroundCompat(notification: Notification) {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(com.ytdlpgui.R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = getString(com.ytdlpgui.R.string.notif_channel_desc) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "ytdlp_downloads"
        const val NOTIFICATION_ID = 10
        const val ACTION_START = "com.ytdlpgui.action.START_DOWNLOAD"
        const val ACTION_STOP = "com.ytdlpgui.action.STOP_DOWNLOAD"
        const val ACTION_PAUSE_ITEM = "com.ytdlpgui.action.PAUSE_ITEM"
        const val ACTION_RESUME_ITEM = "com.ytdlpgui.action.RESUME_ITEM"
        const val ACTION_CANCEL_ITEM = "com.ytdlpgui.action.CANCEL_ITEM"
        const val EXTRA_ROW_ID = "extra_row_id"
        const val CONCURRENCY = 2
        val bus = DownloadStateBus()

        fun processIdFor(rowId: Long): String = "yt_dlp_task_$rowId"

        /** تشغيل المضخة (يزيل أي عنصر جديد في الطابور) */
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply { action = ACTION_START }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply { action = ACTION_STOP }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun pauseItem(context: Context, rowId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_PAUSE_ITEM
                putExtra(EXTRA_ROW_ID, rowId)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }

        fun resumeItem(context: Context, rowId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_RESUME_ITEM
                putExtra(EXTRA_ROW_ID, rowId)
            }
            try {
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {}
        }

        fun cancelItem(context: Context, rowId: Long) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_ITEM
                putExtra(EXTRA_ROW_ID, rowId)
            }
            try {
                context.startService(intent)
            } catch (_: Exception) {}
        }
    }
}
