package com.ytdlpgui.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ytdlpgui.R
import com.ytdlpgui.data.engine.FormatSizeEstimator
import com.ytdlpgui.databinding.ItemLibraryFileBinding
import com.ytdlpgui.ui.common.ImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class LibraryAdapter(
    private val scope: CoroutineScope,
    private val onPlay: (File) -> Unit,
    private val onDelete: (File) -> Unit
) : RecyclerView.Adapter<LibraryAdapter.Holder>() {

    private val files = mutableListOf<File>()

    fun submitFiles(list: List<File>) {
        files.clear()
        files.addAll(list)
        notifyDataSetChanged()
    }

    fun remove(file: File) {
        val idx = files.indexOfFirst { it.absolutePath == file.absolutePath }
        if (idx >= 0) {
            files.removeAt(idx)
            notifyItemRemoved(idx)
        }
    }

    class Holder(val binding: ItemLibraryFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemLibraryFileBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = files.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val file = files[position]
        val ext = file.extension.lowercase(Locale.ROOT)
        val isAudio = ext in setOf("mp3", "m4a", "opus", "flac", "wav", "aac", "ogg")

        with(holder.binding) {
            fileName.text = file.name

            // شارة الحجم المستقلة على المصغّرة (لا تتداخل مع المدة أو النصوص)
            val sizeStr = FormatSizeEstimator.formatBytes(file.length())
            sizeBadge.text = sizeStr
            sizeBadge.visibility = if (sizeStr.isBlank()) View.GONE else View.VISIBLE

            val extUpper = ext.uppercase(Locale.ROOT)
            val typeStr = if (isAudio) "صوت" else "فيديو"
            fileMeta.text = "$typeStr · $extUpper"

            if (isAudio) {
                thumbnailImage.setImageDrawable(null)
                centerPlayIcon.setImageResource(R.drawable.ic_music)
                durationBadge.visibility = View.GONE
            } else {
                centerPlayIcon.setImageResource(R.drawable.ic_play)
                thumbnailImage.setImageDrawable(null)
                // تحميل الصورة المصغرة الحقيقية للفيديو
                ImageLoader.loadVideoThumbnailInto(thumbnailImage, file, scope)

                // جلب مدة الفيديو لعرضها على الشارة
                durationBadge.visibility = View.GONE
                durationBadge.tag = file.absolutePath
                scope.launch {
                    val durSec = ImageLoader.getMediaDurationSeconds(file)
                    val fmtDur = ImageLoader.formatDuration(durSec)
                    if (durationBadge.tag == file.absolutePath && fmtDur.isNotBlank()) {
                        durationBadge.text = fmtDur
                        durationBadge.visibility = View.VISIBLE
                    }
                }
            }

            root.setOnClickListener { onPlay(file) }
            root.setOnLongClickListener {
                onDelete(file)
                true
            }
        }
    }
}

