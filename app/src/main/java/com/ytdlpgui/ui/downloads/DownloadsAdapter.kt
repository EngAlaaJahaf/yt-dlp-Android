package com.ytdlpgui.ui.downloads

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ytdlpgui.R
import com.ytdlpgui.data.bus.DownloadProgressInfo
import com.ytdlpgui.data.db.DownloadEntry
import com.ytdlpgui.data.db.DownloadStatus
import com.ytdlpgui.data.engine.FormatSizeEstimator
import com.ytdlpgui.databinding.ItemDownloadBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadsAdapter(
    private val onPause: (DownloadEntry) -> Unit,
    private val onResume: (DownloadEntry) -> Unit,
    private val onRetry: (DownloadEntry) -> Unit,
    private val onPlay: (DownloadEntry) -> Unit,
    private val onDelete: (DownloadEntry) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.Holder>() {

    private val entries = mutableListOf<DownloadEntry>()
    private val progressInfoMap = mutableMapOf<Long, DownloadProgressInfo>()
    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    fun submitList(list: List<DownloadEntry>) {
        entries.clear()
        entries.addAll(list)
        notifyDataSetChanged()
    }

    fun updateProgressInfo(info: DownloadProgressInfo) {
        progressInfoMap[info.rowId] = info
        val idx = entries.indexOfFirst { it.id == info.rowId }
        if (idx >= 0) {
            val old = entries[idx]
            if (old.progress != info.percent) {
                entries[idx] = old.copy(progress = info.percent)
            }
            notifyItemChanged(idx)
        }
    }

    fun updateProgressById(rowId: Long, progress: Int) {
        val idx = entries.indexOfFirst { it.id == rowId }
        if (idx >= 0 && entries[idx].progress != progress) {
            entries[idx] = entries[idx].copy(progress = progress)
            notifyItemChanged(idx)
        }
    }

    class Holder(val binding: ItemDownloadBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun getItemCount(): Int = entries.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        val info = progressInfoMap[entry.id]
        val ctx = holder.binding.root.context

        with(holder.binding) {
            // أيقونة الفيديو / الصوت
            val isAudio = entry.formatLabel.contains("mp3", ignoreCase = true) ||
                    entry.formatLabel.contains("m4a", ignoreCase = true) ||
                    entry.formatLabel.contains("audio", ignoreCase = true)
            mediaIcon.setImageResource(if (isAudio) R.drawable.ic_music else R.drawable.ic_video)

            // عنوان الفيديو (العنوان الملتقط أو الرابط)
            val title = info?.title ?: extractCleanTitle(entry.url)
            urlText.text = title

            // شريط الحالة الجانبي الملون
            sideStripe.backgroundTintList = android.content.res.ColorStateList.valueOf(
                DownloadStatus.indicatorColor(entry.status)
            )

            // شارة الحالة ذات الخلفية الملونة الناعمة والخط الواضح
            statusText.text = DownloadStatus.label(ctx, entry.status)
            statusText.setTextColor(DownloadStatus.color(entry.status))
            val badgeBg = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_badge_pill)?.mutate()
            badgeBg?.setTint(DownloadStatus.bgColor(entry.status))
            statusText.background = badgeBg

            // شارة الحجم: الحجم الحقيقي للمكتمل، والحجم الكلي للجارٍ/المتوقف
            val sizeLabel = when {
                entry.status == DownloadStatus.DONE -> FormatSizeEstimator.formatBytes(entry.fileSize)
                entry.status == DownloadStatus.RUNNING ||
                    entry.status == DownloadStatus.PAUSED -> info?.totalStr.orEmpty()
                else -> ""
            }
            sizeText.text = sizeLabel
            sizeText.visibility = if (sizeLabel.isBlank()) View.GONE else View.VISIBLE

            // البيانات الوصفية (الجودة، الصيغة، التاريخ)
            infoText.text = "${entry.qualityLabel} • ${entry.formatLabel} • ${fmt.format(Date(entry.createdAt))}"

            // شريط التقدم
            val currentPercent = info?.percent ?: entry.progress
            val showProgress = entry.status == DownloadStatus.RUNNING ||
                    entry.status == DownloadStatus.PAUSED ||
                    entry.status == DownloadStatus.QUEUED

            if (showProgress) {
                itemProgress.visibility = View.VISIBLE
                itemProgress.progress = currentPercent
            } else {
                itemProgress.visibility = View.GONE
            }

            // نص القياسات والتفاصيل اللحظية (VidMate style)
            metricsText.text = when (entry.status) {
                DownloadStatus.RUNNING -> {
                    if (info != null && info.displaySummary.isNotBlank()) {
                        "${info.percent}% • ${info.displaySummary}"
                    } else {
                        "$currentPercent% • ${ctx.getString(R.string.status_running)}"
                    }
                }
                DownloadStatus.PAUSED -> {
                    if (info != null && !info.downloadedStr.isNullOrBlank()) {
                        "${ctx.getString(R.string.status_paused)} (${info.downloadedStr} • $currentPercent%)"
                    } else {
                        "${ctx.getString(R.string.status_paused)} ($currentPercent%)"
                    }
                }
                DownloadStatus.QUEUED -> ctx.getString(R.string.status_queued)
                DownloadStatus.DONE -> ctx.getString(R.string.status_done)
                DownloadStatus.FAILED -> entry.error?.let { com.ytdlpgui.data.engine.ErrorClassifier.classifyShort(it) } ?: ctx.getString(R.string.status_failed)
                DownloadStatus.CANCELLED -> ctx.getString(R.string.status_cancelled)
                else -> ""
            }

            // أزرار التحكم التفاعلية المباشرة
            btnPause.visibility = if (entry.status == DownloadStatus.RUNNING) View.VISIBLE else View.GONE
            btnResume.visibility = if (entry.status == DownloadStatus.PAUSED) View.VISIBLE else View.GONE
            btnRetry.visibility = if (entry.status == DownloadStatus.FAILED || entry.status == DownloadStatus.CANCELLED) View.VISIBLE else View.GONE
            btnPlay.visibility = if (entry.status == DownloadStatus.DONE) View.VISIBLE else View.GONE
            btnDelete.visibility = View.VISIBLE

            btnPause.setOnClickListener { onPause(entry) }
            btnResume.setOnClickListener { onResume(entry) }
            btnRetry.setOnClickListener { onRetry(entry) }
            btnPlay.setOnClickListener { onPlay(entry) }
            btnDelete.setOnClickListener { onDelete(entry) }

            root.setOnClickListener {
                if (entry.status == DownloadStatus.DONE) {
                    onPlay(entry)
                }
            }
        }
    }

    private fun extractCleanTitle(url: String): String {
        return url.replaceFirst(Regex("^https?://(www\\.)?"), "").take(60)
    }
}
