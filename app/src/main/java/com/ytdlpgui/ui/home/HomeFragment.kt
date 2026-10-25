package com.ytdlpgui.ui.home

import android.content.ClipboardManager
import android.content.Context.CLIPBOARD_SERVICE
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import com.ytdlpgui.ui.common.AppToast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.ytdlpgui.data.model.DownloadQuality
import com.ytdlpgui.data.model.DownloadRequest
import com.ytdlpgui.data.model.OutputFormat
import com.ytdlpgui.databinding.FragmentHomeBinding
import com.ytdlpgui.databinding.DialogVideoPreviewBinding
import com.ytdlpgui.ui.common.ImageLoader
import com.ytdlpgui.ui.MainUiState
import com.ytdlpgui.ui.MainViewModel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel
    private lateinit var qualities: List<DownloadQuality>
    private lateinit var formats: List<OutputFormat>

    private var advancedExpanded = false
    private var currentLoadedInfo: com.ytdlpgui.data.model.VideoInfo? = null
    private var autoPreviewPending = false

    private val dirPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val ctx = requireContext()
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val path = getPathFromTreeUri(it)
            val directWritable = com.ytdlpgui.data.storage.DocumentUtils.canWriteDirectPath(path)

            if (path != null && directWritable) {
                viewModel.persistFolder(path, it.toString())
                binding.pathEdit.setText(path)
                AppToast.show(ctx, getString(com.ytdlpgui.R.string.msg_folder_selected, path))
            } else {
                val fallback = viewModel.defaultDownloadDir().absolutePath
                binding.pathEdit.setText(fallback)
                viewModel.persistFolder(fallback, null)
                AppToast.show(ctx, getString(com.ytdlpgui.R.string.msg_folder_may_not_write), true)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.any { !it.value }) {
            context?.let {
                AppToast.show(it, getString(com.ytdlpgui.R.string.msg_permissions_hint), true)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        setupSpinners()
        setupChips()
        setupUrlInput()
        setupAdvancedPanel()
        setupDownloadPath()
        setupLogBox()
        requestEssentialPermissions()

        observeState()
        observeToasts()
        observeEngineEvents()
        restoreSettings()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    fun setUrl(url: String) {
        _binding?.urlEdit?.setText(url)
        if (url.isNotBlank()) {
            autoPreviewPending = true
            clearPreviousDownloadUi()
            viewModel.fetchInfo(url)
        }
    }

    private fun requestEssentialPermissions() {
        val missing = mutableListOf<String>()
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT <= 32) {
            val write = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            val read = android.Manifest.permission.READ_EXTERNAL_STORAGE
            if (ctx.checkSelfPermission(write) != android.content.pm.PackageManager.PERMISSION_GRANTED) missing += write
            if (ctx.checkSelfPermission(read) != android.content.pm.PackageManager.PERMISSION_GRANTED) missing += read
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val postNotifications = "android.permission.POST_NOTIFICATIONS"
            if (ctx.checkSelfPermission(postNotifications) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                missing += postNotifications
            }
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun setupUrlInput() {
        binding.pasteBtn.setOnClickListener { pasteFromClipboard() }
        binding.btnSearchGo.setOnClickListener {
            val url = currentUrl()
            if (url == null) {
                AppToast.show(requireContext(), com.ytdlpgui.R.string.msg_insert_url_first)
            } else {
                viewModel.fetchInfo(url)
            }
        }
        binding.infoCloseBtn.setOnClickListener {
            binding.infoCardContainer.visibility = View.GONE
            binding.infoThumbImage.setImageDrawable(null)
            currentLoadedInfo = null
            viewModel.dismissInfo()
        }
        binding.infoThumbLayout.setOnClickListener {
            currentLoadedInfo?.let { showVideoPreviewDialog(it) }
        }
        binding.infoCardContainer.setOnClickListener {
            currentLoadedInfo?.let { showVideoPreviewDialog(it) }
        }
        binding.infoBtn.setOnClickListener {
            val url = currentUrl()
            if (url == null) {
                AppToast.show(requireContext(), com.ytdlpgui.R.string.msg_insert_url_first)
            } else {
                viewModel.fetchInfo(url)
            }
        }
        binding.downloadBtn.setOnClickListener { startDownload() }
        binding.pauseBtn.setOnClickListener { viewModel.pauseDownload() }
        binding.resumeBtn.setOnClickListener { viewModel.resumeDownload() }
        binding.cancelBtn.setOnClickListener { viewModel.cancelDownload() }
    }

    private fun setupChips() {
        binding.qualityChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val chipId = checkedIds.first()
            val qualityIdx = when (chipId) {
                com.ytdlpgui.R.id.chip4k -> 0   // Best / 4K
                com.ytdlpgui.R.id.chip1080 -> 1 // 1080p
                com.ytdlpgui.R.id.chip720 -> 2  // 720p
                com.ytdlpgui.R.id.chip360 -> 4  // 360p
                else -> 1
            }
            if (qualityIdx in 0 until binding.qualitySpinner.adapter.count) {
                binding.qualitySpinner.setSelection(qualityIdx)
                updateEstimatedSizeDisplay()
            }
        }

        binding.formatChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val chipId = checkedIds.first()
            val formatIdx = when (chipId) {
                com.ytdlpgui.R.id.chipMp4 -> 0  // MP4
                com.ytdlpgui.R.id.chipWebm -> 2 // WEBM
                com.ytdlpgui.R.id.chipMp3 -> 3  // MP3
                com.ytdlpgui.R.id.chipM4a -> 4  // M4A
                else -> 0
            }
            if (formatIdx in 0 until binding.formatSpinner.adapter.count) {
                binding.formatSpinner.setSelection(formatIdx)
                updateEstimatedSizeDisplay()
            }
        }
    }

    private fun syncChipWithFormat(formatIndex: Int) {
        val chipId = when (formatIndex) {
            0 -> com.ytdlpgui.R.id.chipMp4   // MP4
            2 -> com.ytdlpgui.R.id.chipWebm  // WEBM
            3 -> com.ytdlpgui.R.id.chipMp3   // MP3
            4 -> com.ytdlpgui.R.id.chipM4a   // M4A
            else -> com.ytdlpgui.R.id.chipMp4
        }
        binding.formatChipGroup.check(chipId)
    }

    private fun syncChipWithQuality(qualityIndex: Int) {
        val chipId = when (qualityIndex) {
            0 -> com.ytdlpgui.R.id.chip4k
            1 -> com.ytdlpgui.R.id.chip1080
            2 -> com.ytdlpgui.R.id.chip720
            4 -> com.ytdlpgui.R.id.chip360
            else -> com.ytdlpgui.R.id.chip1080
        }
        binding.qualityChipGroup.check(chipId)
    }

    private fun setupAdvancedPanel() {
        binding.pathGhostBtn.setOnClickListener { binding.chooseFolderBtn.performClick() }
        binding.advancedToggle.setOnClickListener {
            advancedExpanded = !advancedExpanded
            binding.advancedPanel.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
            binding.advancedArrow.animate()
                .rotation(if (advancedExpanded) 180f else 0f)
                .setDuration(250)
                .start()
        }
    }

    private fun setupSpinners() {
        qualities = DownloadQuality.All
        val qualityLabels = resources.getStringArray(com.ytdlpgui.R.array.quality_options)
        binding.qualitySpinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, qualityLabels
        )
        binding.qualitySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                syncChipWithQuality(position)
                updateEstimatedSizeDisplay()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        formats = OutputFormat.formatsList
        val formatLabels = resources.getStringArray(com.ytdlpgui.R.array.format_options)
        binding.formatSpinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, formatLabels
        )
        binding.formatSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                syncChipWithFormat(position)
                updateEstimatedSizeDisplay()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun updateEstimatedSizeDisplay() {
        val info = currentLoadedInfo ?: return
        val quality = currentQuality()
        val format = currentFormat()
        val est = info.getEstimatedSize(quality, format)
        if (!est.isNullOrBlank()) {
            binding.estimatedSizeContainer.visibility = View.VISIBLE
            binding.estimatedSizeText.text = getString(com.ytdlpgui.R.string.estimated_size_fmt, est)
        } else {
            binding.estimatedSizeContainer.visibility = View.GONE
        }
    }

    private fun setupDownloadPath() {
        binding.pathEdit.setText(viewModel.defaultDownloadDir().absolutePath)
        binding.chooseFolderBtn.setOnClickListener {
            dirPickerLauncher.launch(null)
        }
    }

    private fun setupLogBox() {
        binding.logHeaderRow.setOnClickListener {
            val showing = binding.logScroll.visibility == View.VISIBLE
            binding.logScroll.visibility = if (!showing) View.VISIBLE else View.GONE
            binding.logToggle.text = getString(
                if (!showing) com.ytdlpgui.R.string.log_arrow_collapse
                else com.ytdlpgui.R.string.log_arrow_expand
            )
        }
    }

    private fun startDownload() {
        val url = currentUrl()
        if (url == null) {
            AppToast.show(requireContext(), com.ytdlpgui.R.string.msg_insert_url_first)
            return
        }
        val quality = currentQuality()
        val format = currentFormat()

        viewModel.persistSelections(
            getDownloadDir().absolutePath,
            binding.qualitySpinner.selectedItemPosition,
            binding.formatSpinner.selectedItemPosition
        )
        val request = DownloadRequest(
            url = url,
            quality = quality,
            format = format,
            downloadDir = getDownloadDir().absolutePath
        )
        viewModel.startDownload(request)
    }

    private fun currentQuality(): DownloadQuality =
        qualities.getOrNull(binding.qualitySpinner.selectedItemPosition)
            ?: DownloadQuality.BEST

    private fun currentFormat(): OutputFormat =
        formats.getOrNull(binding.formatSpinner.selectedItemPosition)
            ?: OutputFormat.formatsList.first()

    private fun currentUrl(): String? =
        com.ytdlpgui.data.model.UrlSanitizer.sanitize(binding.urlEdit.text.toString())

    private fun getDownloadDir(): File {
        val customPath = binding.pathEdit.text.toString().trim()
        val dir = if (customPath.isNotEmpty()) File(customPath)
        else viewModel.defaultDownloadDir()
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun pasteFromClipboard() {
        val clip = requireContext().getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val raw = clip.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        val text = com.ytdlpgui.data.model.UrlSanitizer.sanitize(raw) ?: raw.trim()
        if (text.isNotBlank()) {
            binding.urlEdit.setText(text)
            autoPreviewPending = true
            clearPreviousDownloadUi()
            viewModel.fetchInfo(text)
        } else {
            AppToast.show(requireContext(), com.ytdlpgui.R.string.msg_clipboard_empty)
        }
    }

    /**
     * يمسح بقايا التحميل السابق (بطاقة التقدم، النسبة، معلومات الفيديو) قبل معاينة رابط جديد.
     * لا يمس أي تنزيل نشط.
     */
    private fun clearPreviousDownloadUi() {
        if (_binding == null) return
        val state = viewModel.uiState.value
        if (state == MainUiState.Downloading || state == MainUiState.Paused) return

        binding.infoCardContainer.visibility = View.GONE
        binding.infoThumbImage.setImageDrawable(null)
        binding.estimatedSizeContainer.visibility = View.GONE
        binding.estimatedSizeText.text = ""
        binding.progress.progress = 0
        binding.percentText.text = getString(com.ytdlpgui.R.string.progress_percent, 0)
        binding.progressTitle.text = ""
        binding.progressDetails.text = ""
        binding.progressCard.visibility = View.GONE
        currentLoadedInfo = null
        viewModel.resetDownloadUi()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collect { state -> renderState(state) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.progress.collect { progress ->
                binding.progress.progress = progress
                binding.percentText.text = getString(com.ytdlpgui.R.string.progress_percent, progress)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.progressInfo.collect { info ->
                if (info != null) {
                    binding.progressCard.visibility = View.VISIBLE
                    binding.progress.progress = info.percent
                    binding.percentText.text = getString(com.ytdlpgui.R.string.progress_percent, info.percent)
                    if (!info.title.isNullOrBlank()) {
                        binding.progressTitle.text = info.title
                    }
                    if (info.displaySummary.isNotBlank()) {
                        binding.progressDetails.text = info.displaySummary
                    } else {
                        binding.progressDetails.text = ""
                    }
                } else {
                    binding.progress.progress = 0
                    binding.percentText.text = getString(com.ytdlpgui.R.string.progress_percent, 0)
                    binding.progressTitle.text = ""
                    binding.progressDetails.text = ""
                    binding.progressCard.visibility = View.GONE
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.log.collect { lines ->
                binding.log.text = lines.joinToString("\n")
                if (binding.logScroll.visibility == View.VISIBLE) {
                    binding.logScroll.post { binding.logScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.engineVersion.collect { ver ->
                if (ver.isNotBlank())
                    binding.versionText.text = getString(com.ytdlpgui.R.string.engine_default_label, ver)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.infoState.collect { showInfoDialog(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.logExpanded.collect { expanded ->
                binding.logScroll.visibility = if (expanded) View.VISIBLE else View.GONE
                binding.logToggle.text = getString(
                    if (expanded) com.ytdlpgui.R.string.log_arrow_collapse
                    else com.ytdlpgui.R.string.log_arrow_expand
                )
            }
        }
    }

    private fun showInfoDialog(state: com.ytdlpgui.ui.InfoUi?) {
        val ctx = context ?: return
        when (state) {
            is com.ytdlpgui.ui.InfoUi.Loading ->
                AppToast.show(ctx, com.ytdlpgui.R.string.msg_fetching_info)
            is com.ytdlpgui.ui.InfoUi.Loaded -> {
                val info = state.info
                currentLoadedInfo = info
                updateEstimatedSizeDisplay()
                binding.infoCardContainer.visibility = View.VISIBLE
                binding.infoTitleText.text = info.title ?: "-"
                binding.infoChannelText.text = info.uploader ?: "-"
                val dur = info.describeDuration()
                binding.infoDurText.text = dur
                binding.infoDurText.visibility = if (dur.isBlank()) View.GONE else View.VISIBLE
                binding.infoUrlText.text = currentUrl() ?: ""
                binding.infoMetaChip.text = "${currentQuality().label} · ${currentFormat().label}"
                ImageLoader.loadInto(
                    binding.infoThumbImage,
                    info.thumbnailUrl,
                    viewLifecycleOwner.lifecycleScope
                )
                if (autoPreviewPending) {
                    autoPreviewPending = false
                    showVideoPreviewDialog(info)
                }
            }
            is com.ytdlpgui.ui.InfoUi.Playlist -> {
                autoPreviewPending = false
                val n = state.entries.size
                AlertDialog.Builder(ctx)
                    .setTitle(getString(com.ytdlpgui.R.string.playlist_dialog_title))
                    .setMessage(getString(com.ytdlpgui.R.string.msg_playlist_count, n))
                    .setPositiveButton(getString(com.ytdlpgui.R.string.btn_download_all, n)) { d, _ ->
                        d.dismiss()
                        viewModel.startPlaylistDownload(
                            state.entries,
                            currentQuality(),
                            currentFormat(),
                            getDownloadDir().absolutePath
                        )
                    }
                    .setNegativeButton(com.ytdlpgui.R.string.btn_close) { d, _ -> d.dismiss() }
                    .setOnDismissListener { viewModel.dismissInfo() }
                    .show()
            }
            is com.ytdlpgui.ui.InfoUi.Failed -> {
                autoPreviewPending = false
                AlertDialog.Builder(ctx)
                    .setTitle(getString(com.ytdlpgui.R.string.status_failed))
                    .setMessage(state.message)
                    .setPositiveButton(com.ytdlpgui.R.string.btn_close) { d, _ -> d.dismiss() }
                    .setOnDismissListener { viewModel.dismissInfo() }
                    .show()
            }
            null -> Unit
        }
    }

    private fun showVideoPreviewDialog(info: com.ytdlpgui.data.model.VideoInfo) {
        val ctx = context ?: return
        val dialogBinding = DialogVideoPreviewBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(ctx, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogBinding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogBinding.dialogTitleText.text = info.title ?: "-"
        dialogBinding.dialogUploaderText.text = info.uploader ?: "-"

        val dur = info.describeDuration()
        dialogBinding.dialogDurText.text = dur
        dialogBinding.dialogDurText.visibility = if (dur.isBlank()) View.GONE else View.VISIBLE

        ImageLoader.loadInto(
            dialogBinding.dialogThumbImage,
            info.thumbnailUrl,
            viewLifecycleOwner.lifecycleScope
        )

        val quality = currentQuality()
        val format = currentFormat()
        val est = info.getEstimatedSize(quality, format)
        if (!est.isNullOrBlank()) {
            dialogBinding.dialogSizeBadge.visibility = View.VISIBLE
            dialogBinding.dialogSizeBadge.text = getString(com.ytdlpgui.R.string.real_size_label, est)
        } else {
            dialogBinding.dialogSizeBadge.visibility = View.GONE
        }
        dialogBinding.dialogQualityBadge.text = "${quality.label} • ${format.label}"

        dialogBinding.dialogCloseBtn.setOnClickListener {
            dialog.dismiss()
        }

        val watchAction = View.OnClickListener {
            val videoUrl = info.webpageUrl ?: currentUrl()
            if (!videoUrl.isNullOrBlank()) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl))
                    startActivity(intent)
                } catch (e: Exception) {
                    AppToast.show(ctx, e.localizedMessage ?: "Error")
                }
            }
        }
        dialogBinding.dialogWatchBtn.setOnClickListener(watchAction)
        dialogBinding.dialogThumbCard.setOnClickListener(watchAction)

        dialogBinding.dialogDownloadBtn.setOnClickListener {
            dialog.dismiss()
            startDownload()
        }

        dialog.show()
    }

    private fun renderState(state: MainUiState) {
        when (state) {
            MainUiState.Downloading -> busyUi(busy = true, isDownloading = true, isPaused = false)
            MainUiState.Paused -> busyUi(busy = true, isDownloading = false, isPaused = true)
            MainUiState.UpdatingEngine -> busyUi(busy = true, isDownloading = false, isPaused = false)
            is MainUiState.InitFailed -> {
                busyUi(busy = true, isDownloading = false, isPaused = false)
                context?.let {
                    AppToast.show(it, getString(com.ytdlpgui.R.string.msg_engine_init_failed), true)
                }
            }
            else -> busyUi(busy = false, isDownloading = false, isPaused = false)
        }
    }

    private fun busyUi(busy: Boolean, isDownloading: Boolean, isPaused: Boolean) {
        android.util.Log.d(
            "YTDLP_STATE",
            "busyUi busy=$busy dl=$isDownloading paused=$isPaused " +
                "prog=${binding.progress.progress}"
        )
        binding.urlEdit.isEnabled = !busy
        binding.qualitySpinner.isEnabled = !busy
        binding.formatSpinner.isEnabled = !busy
        binding.pathEdit.isEnabled = !busy
        binding.chooseFolderBtn.isEnabled = !busy
        for (i in 0 until binding.formatChipGroup.childCount) {
            binding.formatChipGroup.getChildAt(i).isEnabled = !busy
        }

        when {
            isDownloading -> {
                binding.downloadBtn.visibility = View.GONE
                binding.pauseBtn.visibility = View.VISIBLE
                binding.resumeBtn.visibility = View.GONE
                binding.cancelBtn.visibility = View.VISIBLE
                binding.progressCard.visibility = View.VISIBLE
            }
            isPaused -> {
                binding.downloadBtn.visibility = View.GONE
                binding.pauseBtn.visibility = View.GONE
                binding.resumeBtn.visibility = View.VISIBLE
                binding.cancelBtn.visibility = View.VISIBLE
                binding.progressCard.visibility = View.VISIBLE
            }
            else -> {
                binding.downloadBtn.visibility = View.VISIBLE
                binding.pauseBtn.visibility = View.GONE
                binding.resumeBtn.visibility = View.GONE
                binding.cancelBtn.visibility = View.GONE
                binding.downloadBtn.isEnabled = !busy
                if (!busy && binding.progress.progress == 0) {
                    binding.progressCard.visibility = View.GONE
                }
            }
        }
    }

    private fun observeToasts() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.toastMessage.collect { msg ->
                val ctx = context ?: return@collect
                if (msg != null) {
                    val text = if (msg.arg != null) {
                        getString(msg.resId, msg.arg)
                    } else {
                        getString(msg.resId)
                    }
                    AppToast.show(ctx, text, true)
                    viewModel.consumeToastMessage()
                }
            }
        }
    }

    private fun observeEngineEvents() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiEvents.collect { event ->
                val ctx = context ?: return@collect
                when (event) {
                    is com.ytdlpgui.data.bus.UiEvent.ItemDone -> {
                        val title = event.title
                            ?: getString(com.ytdlpgui.R.string.msg_download_item_generic)
                        AppToast.show(
                            ctx,
                            getString(com.ytdlpgui.R.string.msg_download_completed_fmt, title),
                            true
                        )
                    }
                    is com.ytdlpgui.data.bus.UiEvent.ItemFailed -> {
                        AppToast.show(
                            ctx,
                            getString(com.ytdlpgui.R.string.msg_download_failed_fmt, event.message),
                            true
                        )
                    }
                }
            }
        }
    }

    private fun restoreSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            val folder = viewModel.savedFolder.firstOrNull() ?: return@launch
            folder.path?.takeIf { it.isNotBlank() }?.let {
                if (binding.pathEdit.text.isNullOrBlank()) {
                    binding.pathEdit.setText(it)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val (qi, fi) = viewModel.savedIndices.firstOrNull() ?: return@launch
            if (qi in 0 until binding.qualitySpinner.adapter.count) binding.qualitySpinner.setSelection(qi)
            if (fi in 0 until binding.formatSpinner.adapter.count) binding.formatSpinner.setSelection(fi)
            syncChipWithFormat(fi)
            syncChipWithQuality(qi)
        }
    }

    private fun getPathFromTreeUri(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val split = docId.split(":")
            val type = split[0]
            val relativePath = if (split.size > 1) split[1] else ""

            if ("primary".equals(type, ignoreCase = true)) {
                if (relativePath.isNotEmpty()) {
                    "${Environment.getExternalStorageDirectory().absolutePath}/$relativePath"
                } else {
                    Environment.getExternalStorageDirectory().absolutePath
                }
            } else {
                "/storage/$type/$relativePath"
            }
        } catch (_: Exception) {
            null
        }
    }
}
