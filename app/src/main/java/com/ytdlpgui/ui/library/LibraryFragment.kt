package com.ytdlpgui.ui.library

import android.app.AlertDialog
import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
import android.widget.Toast
import androidx.core.content.FileProvider
import com.ytdlpgui.ui.common.AppToast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.ytdlpgui.R
import com.ytdlpgui.data.settings.SettingsRepository
import com.ytdlpgui.databinding.DialogPlayerBinding
import com.ytdlpgui.databinding.FragmentLibraryBinding
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: LibraryAdapter
    private val videoExtensions = setOf("mp4", "mkv", "webm", "mp3", "m4a", "opus", "flac", "wav")
    private val pureVideoExtensions = setOf("mp4", "mkv", "webm", "avi", "mov")
    private val pureAudioExtensions = setOf("mp3", "m4a", "opus", "flac", "wav", "aac")
    private var playerDialog: Dialog? = null

    private var allFiles = listOf<File>()
    private var searchQuery = ""
    private var activeFilter = 0 // 0: All, 1: Video, 2: Audio

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = LibraryAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            onPlay = ::playFile,
            onDelete = ::confirmDelete
        )
        binding.libraryList.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.libraryList.adapter = adapter

        binding.refreshBtn.setOnClickListener {
            refreshCurrent()
        }

        binding.libSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString()?.trim() ?: ""
                applyFilterAndSearch()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        binding.libFilterGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            activeFilter = when (checkedIds.first()) {
                R.id.chipFilterVideo -> 1
                R.id.chipFilterAudio -> 2
                else -> 0
            }
            applyFilterAndSearch()
        }

        refreshCurrent()
    }

    private fun applyFilterAndSearch() {
        val filtered = allFiles.filter { file ->
            val ext = file.extension.lowercase()
            val matchCategory = when (activeFilter) {
                1 -> ext in pureVideoExtensions
                2 -> ext in pureAudioExtensions
                else -> true
            }
            val matchQuery = searchQuery.isBlank() || file.name.contains(searchQuery, ignoreCase = true)
            matchCategory && matchQuery
        }

        _binding?.let { b ->
            adapter.submitFiles(filtered)
            val empty = filtered.isEmpty()
            b.emptyContainer.isVisible = empty
            b.libraryList.isVisible = !empty
        }
    }

    override fun onResume() {
        super.onResume()
        refreshCurrent()
    }

    fun onTabSelected() {
        refreshCurrent()
    }

    override fun onDestroyView() {
        playerDialog?.dismiss()
        playerDialog = null
        super.onDestroyView()
        _binding = null
    }

    private fun getExternalStoragePublicDir(): String {
        return android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        ).let { File(it, "ClipCatch") }.absolutePath
    }

    fun refreshCurrent() {
        val context = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = SettingsRepository(context.applicationContext)
            val saved = settings.savedFolder.firstOrNull()?.path?.takeIf { it.isNotBlank() }
            val dir = File(saved ?: getExternalStoragePublicDir()).also { if (!it.exists()) it.mkdirs() }
            val files = dir.listFiles { f ->
                f.isFile && f.extension.lowercase() in videoExtensions
            }?.sortedByDescending { it.lastModified() } ?: emptyList()

            allFiles = files
            applyFilterAndSearch()
        }
    }

    private fun contentUriFor(file: File): Uri {
        val context = requireContext().applicationContext
        return FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
    }

    private fun playFile(file: File) {
        val ext = file.extension.lowercase(java.util.Locale.ROOT)
        val isAudio = ext in setOf("mp3", "m4a", "opus", "flac", "wav", "aac")
        val mimeType = if (isAudio) "audio/*" else "video/*"

        try {
            val uri = contentUriFor(file)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooserTitle = if (isAudio) "تشغيل الصوت بواسطة" else "تشغيل الفيديو بواسطة"
            val chooser = android.content.Intent.createChooser(intent, chooserTitle)
            chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(chooser)
        } catch (_: Exception) {
            context?.let {
                AppToast.showLong(it, R.string.msg_playback_failed)
            }
        }
    }

    private fun confirmDelete(file: File) {
        val context = context ?: return
        AlertDialog.Builder(context)
            .setTitle(file.name)
            .setMessage(getString(R.string.msg_delete_confirm))
            .setPositiveButton(getString(R.string.btn_delete_short)) { d, _ ->
                d.dismiss()
                if (file.delete()) {
                    adapter.remove(file)
                    AppToast.show(context, R.string.lib_deleted)
                    refreshCurrent()
                } else {
                    AppToast.show(context, R.string.msg_delete_failed)
                }
            }
            .setNegativeButton(getString(R.string.btn_close), null)
            .show()
    }
}
