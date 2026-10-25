package com.ytdlpgui.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.ytdlpgui.R
import com.ytdlpgui.ui.common.AppToast
import com.ytdlpgui.data.db.DownloadDatabase
import com.ytdlpgui.data.settings.SettingsRepository
import com.ytdlpgui.data.settings.SettingsStore
import com.ytdlpgui.data.settings.ThemeMode
import com.ytdlpgui.databinding.FragmentSettingsBinding
import com.ytdlpgui.ui.MainViewModel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel
    private val settings: SettingsStore by lazy {
        SettingsRepository(requireContext().applicationContext)
    }

    private val dirPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val ctx = requireContext()
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val path = com.ytdlpgui.data.storage.DocumentUtils.getPathFromTreeUri(it)
            val directWritable = com.ytdlpgui.data.storage.DocumentUtils.canWriteDirectPath(path)

            if (path != null && directWritable) {
                viewModel.persistFolder(path, it.toString())
                binding.setPathTxt.text = path
                AppToast.show(ctx, R.string.msg_folder_selected, path)
            } else {
                val fallback = viewModel.defaultDownloadDir().absolutePath
                binding.setPathTxt.text = fallback
                viewModel.persistFolder(fallback, null)
                AppToast.showLong(ctx, R.string.msg_folder_may_not_write)
            }
        }
    }

    private val pickCookiesLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importCookies(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        setupPathSection()
        setupNetworkSection()
        setupEngineSection()
        setupHistorySection()
        setupGeneralSection()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupPathSection() {
        viewLifecycleOwner.lifecycleScope.launch {
            settings.savedFolder.collect { folder ->
                binding.setPathTxt.text = folder.path?.takeIf { it.isNotBlank() }
                    ?: viewModel.defaultDownloadDir().absolutePath
            }
        }
        binding.pathRow.setOnClickListener {
            dirPickerLauncher.launch(null)
        }
    }

    private fun setupNetworkSection() {
        viewLifecycleOwner.lifecycleScope.launch {
            settings.wifiOnly.collect { enabled ->
                binding.tglWifi.setOnCheckedChangeListener(null)
                binding.tglWifi.isChecked = enabled
                binding.tglWifi.setOnCheckedChangeListener { _, isChecked ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        settings.saveWifiOnly(isChecked)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            settings.maxConcurrent.collect { count ->
                binding.concurrentText.text = count.toString()
            }
        }

        binding.btnMinusConcurrent.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val current = settings.maxConcurrent.firstOrNull() ?: 2
                if (current > 1) settings.saveMaxConcurrent(current - 1)
            }
        }

        binding.btnPlusConcurrent.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val current = settings.maxConcurrent.firstOrNull() ?: 2
                if (current < 6) settings.saveMaxConcurrent(current + 1)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            settings.backgroundDownload.collect { enabled ->
                binding.tglBg.setOnCheckedChangeListener(null)
                binding.tglBg.isChecked = enabled
                binding.tglBg.setOnCheckedChangeListener { _, isChecked ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        settings.saveBackgroundDownload(isChecked)
                    }
                }
            }
        }
    }

    private fun setupEngineSection() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.engineVersion.collect { ver ->
                binding.engVerText.text = if (ver.isNotBlank()) ver else getString(R.string.engine_default_label)
            }
        }

        binding.btnEngUpd.setOnClickListener {
            viewModel.startUpdateEngine()
        }

        renderCookiesState()
        binding.ckImport.setOnClickListener {
            pickCookiesLauncher.launch(arrayOf("*/*"))
        }
        binding.ckRemove.setOnClickListener {
            removeCookies()
        }
    }

    private fun renderCookiesState() {
        viewLifecycleOwner.lifecycleScope.launch {
            val path = settings.cookiesPath.firstOrNull()
            val hasCookies = path != null && File(path).exists()
            binding.cookiesStatusText.text = if (hasCookies) {
                getString(R.string.settings_cookies_active)
            } else {
                getString(R.string.settings_cookies_row_desc)
            }
            binding.ckRemove.visibility = if (hasCookies) View.VISIBLE else View.GONE
        }
    }

    private fun importCookies(uri: Uri) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val target = File(ctx.filesDir, "cookies.txt")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("cannot open")
                settings.saveCookiesPath(target.absolutePath)
                AppToast.show(ctx, R.string.msg_cookies_imported)
                renderCookiesState()
            } catch (_: Exception) {
                AppToast.showLong(ctx, R.string.msg_cookies_import_failed)
            }
        }
    }

    private fun removeCookies() {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            settings.cookiesPath.firstOrNull()?.let { File(it).delete() }
            settings.saveCookiesPath(null)
            AppToast.show(ctx, R.string.msg_cookies_removed)
            renderCookiesState()
        }
    }

    private fun setupHistorySection() {
        viewLifecycleOwner.lifecycleScope.launch {
            settings.saveHistory.collect { enabled ->
                binding.tglLogSv.setOnCheckedChangeListener(null)
                binding.tglLogSv.isChecked = enabled
                binding.tglLogSv.setOnCheckedChangeListener { _, isChecked ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        settings.saveSaveHistory(isChecked)
                    }
                }
            }
        }

        binding.btnSvClear.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            AlertDialog.Builder(ctx)
                .setTitle(R.string.settings_clear_history)
                .setMessage(R.string.settings_clear_history_desc)
                .setPositiveButton(R.string.btn_clear) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        DownloadDatabase.get(ctx.applicationContext).downloadDao().deleteFinished()
                        AppToast.show(ctx, R.string.btn_clear_finished)
                    }
                }
                .setNegativeButton(R.string.btn_close, null)
                .show()
        }
    }

    private fun setupGeneralSection() {
        viewLifecycleOwner.lifecycleScope.launch {
            val pref = settings.themeDark.firstOrNull()
            val isDark = pref ?: ThemeMode.isDark(requireContext())
            binding.tglDark.setOnCheckedChangeListener(null)
            binding.tglDark.isChecked = isDark
            binding.themeSub.text = getString(if (isDark) R.string.theme_dark else R.string.theme_light)
            binding.themeRowIc.setImageResource(if (isDark) R.drawable.ic_sun else R.drawable.ic_moon)

            binding.tglDark.setOnCheckedChangeListener { _, dark ->
                viewLifecycleOwner.lifecycleScope.launch {
                    settings.saveThemeDark(dark)
                    val appCtx = requireContext().applicationContext
                    ThemeMode.apply(appCtx)
                    binding.themeSub.text = getString(if (dark) R.string.theme_dark else R.string.theme_light)
                    binding.themeRowIc.setImageResource(if (dark) R.drawable.ic_sun else R.drawable.ic_moon)
                    AppToast.show(
                        requireContext(),
                        if (dark) R.string.msg_theme_dark_on else R.string.msg_theme_dark_off
                    )
                }
            }
        }

        val appVersion = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) { "1.0.0" }
        binding.aboutSub.text = getString(R.string.settings_about_desc, appVersion)

        binding.aboutRow.setOnClickListener {
            val ctx = context ?: return@setOnClickListener
            val engVer = viewModel.engineVersion.value.ifBlank { "-" }
            AlertDialog.Builder(ctx)
                .setTitle(R.string.settings_about)
                .setMessage("ClipCatch v$appVersion\n\nمحرك yt-dlp ($engVer) و FFmpeg مدمجان بالكامل للتحميل بأعلى دقة ممكنة.")
                .setPositiveButton(R.string.btn_close, null)
                .show()
        }
    }
}
