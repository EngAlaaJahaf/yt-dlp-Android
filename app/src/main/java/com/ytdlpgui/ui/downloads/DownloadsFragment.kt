package com.ytdlpgui.ui.downloads

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import com.ytdlpgui.ui.common.AppToast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.ytdlpgui.R
import com.ytdlpgui.data.db.DownloadDatabase
import com.ytdlpgui.data.db.DownloadEntry
import com.ytdlpgui.data.db.DownloadStatus
import com.ytdlpgui.data.engine.FormatSizeEstimator
import com.ytdlpgui.data.model.UrlSanitizer
import com.ytdlpgui.databinding.DialogDownloadPlayerBinding
import com.ytdlpgui.databinding.FragmentDownloadsBinding
import com.ytdlpgui.service.DownloadService
import com.ytdlpgui.ui.common.ImageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    private val db by lazy { DownloadDatabase.get(requireContext().applicationContext) }
    private lateinit var adapter: DownloadsAdapter
    private var isDrawerExpanded = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    private var allEntries = listOf<DownloadEntry>()
    private var currentFilterTab = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupTabs()
        setupDrawer()

        adapter = DownloadsAdapter(
            onPause = { entry ->
                DownloadService.pauseItem(requireContext().applicationContext, entry.id)
            },
            onResume = { entry ->
                DownloadService.resumeItem(requireContext().applicationContext, entry.id)
            },
            onRetry = { entry ->
                viewLifecycleOwner.lifecycleScope.launch {
                    db.downloadDao().markRetry(entry.id)
                    DownloadService.start(requireContext().applicationContext)
                }
            },
            onPlay = { entry ->
                playDownloadedEntry(entry)
            },
            onDelete = { entry ->
                confirmDelete(entry)
            }
        )

        binding.downloadsList.layoutManager = LinearLayoutManager(requireContext())
        binding.downloadsList.adapter = adapter

        observeData()
        observeProgress()
    }

    private fun setupTabs() {
        binding.downloadTabs.removeAllTabs()
        binding.downloadTabs.addTab(binding.downloadTabs.newTab().setText(R.string.tab_all))
        binding.downloadTabs.addTab(binding.downloadTabs.newTab().setText(R.string.tab_running))
        binding.downloadTabs.addTab(binding.downloadTabs.newTab().setText(R.string.tab_done))
        binding.downloadTabs.addTab(binding.downloadTabs.newTab().setText(R.string.tab_failed))

        binding.downloadTabs.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                currentFilterTab = tab?.position ?: 0
                applyFilter()
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
    }

    private fun setupDrawer() {
        binding.dsheetGrip.setOnClickListener {
            toggleDrawer()
        }
        binding.dsheetScrim.setOnClickListener {
            if (isDrawerExpanded) toggleDrawer()
        }
        binding.btnClearCompletedSheet.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                db.downloadDao().deleteFinished()
                AppToast.show(requireContext(), R.string.lib_deleted)
                if (isDrawerExpanded) toggleDrawer()
            }
        }
    }

    private fun toggleDrawer() {
        isDrawerExpanded = !isDrawerExpanded
        android.transition.TransitionManager.beginDelayedTransition(binding.dsheetContainer)
        binding.dsheetBody.visibility = if (isDrawerExpanded) View.VISIBLE else View.GONE
        binding.dsheetScrim.visibility = if (isDrawerExpanded) View.VISIBLE else View.GONE
        binding.gripChevron.setImageResource(
            if (isDrawerExpanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down
        )
    }

    private fun applyFilter() {
        val filtered = when (currentFilterTab) {
            1 -> allEntries.filter { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.PAUSED || it.status == DownloadStatus.QUEUED }
            2 -> allEntries.filter { it.status == DownloadStatus.DONE }
            3 -> allEntries.filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }
            else -> allEntries
        }
        adapter.submitList(filtered)
        val empty = filtered.isEmpty()
        binding.emptyContainer.isVisible = empty
        binding.downloadsList.isVisible = !empty
    }

    private fun updateStats(list: List<DownloadEntry>) {
        val countAll = list.size
        val countQueued = list.count { it.status == DownloadStatus.QUEUED }
        val countRunning = list.count { it.status == DownloadStatus.RUNNING }
        val countPaused = list.count { it.status == DownloadStatus.PAUSED }
        val countDone = list.count { it.status == DownloadStatus.DONE }
        val countFailed = list.count { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }

        binding.downloadTabs.getTabAt(0)?.text = "${getString(R.string.tab_all)} ($countAll)"
        binding.downloadTabs.getTabAt(1)?.text = "${getString(R.string.tab_running)} ($countRunning)"
        binding.downloadTabs.getTabAt(2)?.text = "${getString(R.string.tab_done)} ($countDone)"
        binding.downloadTabs.getTabAt(3)?.text = "${getString(R.string.tab_failed)} ($countFailed)"

        // حساب الحجم الإجمالي للملفات
        var totalBytes = 0L
        list.forEach { entry ->
            val f = getDownloadedFile(entry)
            if (f != null && f.exists()) totalBytes += f.length()
        }
        val sizeStr = FormatSizeEstimator.formatBytes(totalBytes).ifBlank { "0 KB" }

        // نص المقبض (الصورة 4: عنصر واحد · 640 MB إجمالي)
        val countText = when (countAll) {
            0 -> "لا توجد ملفات · $sizeStr إجمالي"
            1 -> "عنصر واحد · $sizeStr إجمالي"
            2 -> "عنصران · $sizeStr إجمالي"
            in 3..10 -> "$countAll عناصر · $sizeStr إجمالي"
            else -> "$countAll عنصر · $sizeStr إجمالي"
        }
        binding.gripText.text = countText

        // أوزان شريط التقدم المقسم متعدد الألوان (الصورة 5)
        if (countAll > 0) {
            (binding.segQueued.layoutParams as LinearLayout.LayoutParams).weight = countQueued.toFloat()
            (binding.segRunning.layoutParams as LinearLayout.LayoutParams).weight = countRunning.toFloat()
            (binding.segPaused.layoutParams as LinearLayout.LayoutParams).weight = countPaused.toFloat()
            (binding.segDone.layoutParams as LinearLayout.LayoutParams).weight = countDone.toFloat()
            (binding.segFailed.layoutParams as LinearLayout.LayoutParams).weight = countFailed.toFloat()
        } else {
            (binding.segQueued.layoutParams as LinearLayout.LayoutParams).weight = 0f
            (binding.segRunning.layoutParams as LinearLayout.LayoutParams).weight = 0f
            (binding.segPaused.layoutParams as LinearLayout.LayoutParams).weight = 0f
            (binding.segDone.layoutParams as LinearLayout.LayoutParams).weight = 1f
            (binding.segFailed.layoutParams as LinearLayout.LayoutParams).weight = 0f
        }
        binding.statbarContainer.requestLayout()

        // نصوص مفتاح الحالات (الصورة 5)
        binding.legendQueued.text = "في الطابور ($countQueued)"
        binding.legendRunning.text = "جارٍ التحميل ($countRunning)"
        binding.legendPaused.text = "متوقّف مؤقتًا ($countPaused)"
        binding.legendDone.text = "مكتمل ($countDone)"
        binding.legendFailed.text = "فشل ($countFailed)"

        // أحدث التنزيلات (الصورة 5)
        val latestDone = list.filter { it.status == DownloadStatus.DONE }.maxByOrNull { it.createdAt }
            ?: list.firstOrNull()
        if (latestDone != null) {
            binding.recentItemCard.visibility = View.VISIBLE
            binding.recentTitle.text = extractCleanTitle(latestDone.url)
            val latestFile = getDownloadedFile(latestDone)
            val lLength = latestFile?.takeIf { it.exists() }?.length() ?: 0L
            val lSizeStr = FormatSizeEstimator.formatBytes(lLength).ifBlank { "0 KB" }
            binding.recentMeta.text = "$lSizeStr · ${latestDone.formatLabel}"
            binding.recentStatusBadge.text = "• ${DownloadStatus.label(requireContext(), latestDone.status)}"
            binding.recentStatusBadge.setTextColor(DownloadStatus.color(latestDone.status))

            val isAud = latestDone.formatLabel.contains("mp3", true) || latestDone.formatLabel.contains("audio", true)
            binding.recentMediaIcon.setImageResource(if (isAud) R.drawable.ic_music else R.drawable.ic_play)

            binding.recentItemCard.setOnClickListener {
                if (latestDone.status == DownloadStatus.DONE) {
                    playDownloadedEntry(latestDone)
                }
            }
        } else {
            binding.recentItemCard.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun onTabSelected() {
        // يتم استدعاؤه عند النقر على تبويب التنزيلات
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            db.downloadDao().recentFlow().collect { list ->
                allEntries = list
                updateStats(list)
                applyFilter()
                backfillMissingSizes(list)
            }
        }
    }

    private val backfillAttempted = mutableSetOf<Long>()

    /** يعبّئ حجم/مسار الصفوف المكتملة القديمة التي سُجلت قبل إضافة الأعمدة */
    private fun backfillMissingSizes(list: List<DownloadEntry>) {
        val pending = list.filter {
            it.status == DownloadStatus.DONE && it.fileSize <= 0 && backfillAttempted.add(it.id)
        }
        if (pending.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch {
            val updates = withContext(Dispatchers.IO) {
                pending.mapNotNull { entry ->
                    val f = resolveEntryFile(entry) ?: return@mapNotNull null
                    entry.id to Pair(f.absolutePath, f.length())
                }
            }
            updates.forEach { (id, info) ->
                db.downloadDao().updateFileInfo(id, info.first, info.second)
            }
        }
    }

    /** مطابقة صارمة بالمسار المحفوظ أو بمعرّف الفيديو داخل اسم الملف */
    private fun resolveEntryFile(entry: DownloadEntry): File? {
        entry.filePath?.let { path ->
            val f = File(path)
            if (f.isFile) return f
        }
        val key = UrlSanitizer.normalizeKey(entry.url)?.substringAfter(":")?.takeIf { it.isNotBlank() }
            ?: return null
        val dir = File(entry.dir)
        if (!dir.isDirectory) return null
        val videoExtensions = setOf("mp4", "mkv", "webm", "mp3", "m4a", "opus", "flac", "wav")
        return dir.listFiles()
            ?.filter {
                it.isFile && !it.name.endsWith(".part", ignoreCase = true) &&
                    it.extension.lowercase(Locale.ROOT) in videoExtensions
            }
            ?.firstOrNull { it.name.contains(key) }
    }

    private fun observeProgress() {
        viewLifecycleOwner.lifecycleScope.launch {
            DownloadService.bus.progressInfoEvents.collect { info ->
                adapter.updateProgressInfo(info)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            DownloadService.bus.progressEvents.collect { (rowId, progress) ->
                adapter.updateProgressById(rowId, progress)
            }
        }
    }

    private fun confirmDelete(entry: DownloadEntry) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.action_delete)
            .setMessage(R.string.msg_delete_confirm)
            .setPositiveButton(R.string.btn_delete_short) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val appContext = ctx.applicationContext
                    if (entry.status == DownloadStatus.RUNNING) {
                        DownloadService.cancelItem(appContext, entry.id)
                    }
                    db.downloadDao().deleteById(entry.id)
                }
            }
            .setNegativeButton(R.string.btn_close, null)
            .show()
    }

    private fun getDownloadedFile(entry: DownloadEntry): File? {
        entry.filePath?.let { path ->
            val f = File(path)
            if (f.isFile) return f
        }
        val dir = File(entry.dir)
        if (!dir.exists() || !dir.isDirectory) return null

        val videoExtensions = setOf("mp4", "mkv", "webm", "mp3", "m4a", "opus", "flac", "wav")
        val validFiles = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.extension.lowercase(Locale.ROOT) in videoExtensions && !it.name.endsWith(".part") }

        val cleanKey = com.ytdlpgui.data.model.UrlSanitizer.normalizeKey(entry.url)?.substringAfter(":")
        return validFiles.find { f ->
            cleanKey != null && cleanKey.isNotBlank() && f.name.contains(cleanKey)
        } ?: validFiles.maxByOrNull { it.lastModified() }
    }

    private fun playDownloadedEntry(entry: DownloadEntry) {
        val targetFile = getDownloadedFile(entry)
        if (targetFile != null && targetFile.exists()) {
            showPlayerModal(targetFile, entry)
        } else {
            AppToast.show(requireContext(), R.string.msg_playback_failed)
        }
    }

    private fun showPlayerModal(file: File, entry: DownloadEntry) {
        val context = requireContext()
        val dialog = BottomSheetDialog(context, R.style.Theme_App_BottomSheetDialog)
        val playerBinding = DialogDownloadPlayerBinding.inflate(layoutInflater)
        dialog.setContentView(playerBinding.root)

        val ext = file.extension.lowercase(Locale.ROOT)
        val isAudio = ext in setOf("mp3", "m4a", "opus", "flac", "wav", "aac")
        val mimeType = if (isAudio) "audio/*" else "video/*"

        // العنوان والبيانات الوصفية
        val cleanTitle = extractCleanTitle(entry.url)
        playerBinding.playerTitle.text = cleanTitle

        val sizeStr = FormatSizeEstimator.formatBytes(file.length()).ifBlank { "0 KB" }
        val extUpper = ext.uppercase(Locale.ROOT)
        playerBinding.playerSubtitle.text = "YouTube · $sizeStr · $extUpper"

        // جلب وتحميل الصورة المصغرة الحقيقية للملف
        if (isAudio) {
            playerBinding.playerThumbnail.setImageDrawable(null)
            playerBinding.centerPlayArrow.setImageResource(R.drawable.ic_music)
            playerBinding.playerDurationBadge.visibility = View.GONE
        } else {
            playerBinding.centerPlayArrow.setImageResource(R.drawable.ic_play)
            ImageLoader.loadVideoThumbnailInto(playerBinding.playerThumbnail, file, viewLifecycleOwner.lifecycleScope)
            viewLifecycleOwner.lifecycleScope.launch {
                val durSec = ImageLoader.getMediaDurationSeconds(file)
                val durFmt = ImageLoader.formatDuration(durSec)
                if (durFmt.isNotBlank()) {
                    playerBinding.playerDurationBadge.text = durFmt
                    playerBinding.playerTotalTime.text = durFmt
                    playerBinding.playerDurationBadge.visibility = View.VISIBLE
                }
            }
        }

        // وظيفة تشغيل الفيديو مع عرض خيارات مشغلات الفيديو المتعددة المثبتة على الجهاز
        val launchChooser = {
            dialog.dismiss()
            val uri = contentUriFor(file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooserTitle = if (isAudio) "تشغيل الصوت بواسطة" else "تشغيل الفيديو بواسطة"
            val chooser = Intent.createChooser(intent, chooserTitle)
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(chooser)
        }

        playerBinding.btnCenterPlay.setOnClickListener { launchChooser() }
        playerBinding.playerPreviewBox.setOnClickListener { launchChooser() }

        // زر الإغلاق
        playerBinding.btnClosePlayer.setOnClickListener { dialog.dismiss() }

        // زر المشاركة
        playerBinding.btnSharePlayer.setOnClickListener {
            try {
                val uri = contentUriFor(file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "مشاركة الملف"))
            } catch (_: Exception) {
                AppToast.show(context, "فشلت المشاركة")
            }
        }

        // زر الحذف
        playerBinding.btnDeletePlayer.setOnClickListener {
            dialog.dismiss()
            confirmDelete(entry)
        }

        // أزرار 10- ث و 10+ ث
        playerBinding.btnReplay10.setOnClickListener {
            AppToast.show(context, "10- ث")
        }
        playerBinding.btnForward10.setOnClickListener {
            AppToast.show(context, "10+ ث")
        }

        dialog.show()
    }

    private fun contentUriFor(file: File): Uri {
        val context = requireContext().applicationContext
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    private fun extractCleanTitle(url: String): String {
        return url.replaceFirst(Regex("^https?://(www\\.)?"), "").take(60)
    }
}
