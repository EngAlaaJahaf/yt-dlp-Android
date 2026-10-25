package com.ytdlpgui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.ytdlpgui.ui.common.AppToast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ytdlpgui.data.settings.SettingsRepository
import com.ytdlpgui.data.settings.SettingsStore
import com.ytdlpgui.databinding.ActivitySettingsBinding
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val settings: SettingsStore by lazy { SettingsRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.ytdlpgui.data.settings.ThemeMode.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        renderCookiesState()

        binding.importCookiesBtn.setOnClickListener { openFilePicker() }
        binding.removeCookiesBtn.setOnClickListener { removeCookies() }

        lifecycleScope.launch {
            val pref = settings.themeDark.firstOrNull()
            binding.themeSwitch.isChecked = pref
                ?: ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES)
            binding.themeSwitch.setOnCheckedChangeListener { _, dark ->
                lifecycleScope.launch {
                    settings.saveThemeDark(dark)
                    com.ytdlpgui.data.settings.ThemeMode.apply(applicationContext)
                    AppToast.show(
                        this@SettingsActivity,
                        if (dark) com.ytdlpgui.R.string.msg_theme_dark_on else com.ytdlpgui.R.string.msg_theme_dark_off
                    )
                }
            }
        }

        binding.engineVersionText.text = getString(
            com.ytdlpgui.R.string.settings_engine_version,
            intent.getStringExtra(EXTRA_ENGINE_VERSION) ?: "-"
        )
        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "-"
        } catch (_: Exception) { "-" }
        binding.appVersionText.text = getString(com.ytdlpgui.R.string.settings_app_version, appVersion)

        binding.backBtn.setOnClickListener { finish() }
    }

    private fun renderCookiesState() {
        lifecycleScope.launch {
            val path = settings.cookiesPath.firstOrNull()
            binding.cookiesStatus.text = if (path != null && File(path).exists()) {
                getString(com.ytdlpgui.R.string.settings_cookies_active)
            } else {
                getString(com.ytdlpgui.R.string.settings_cookies_none)
            }
        }
    }

    private val pickCookiesLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importCookies(uri)
    }

    private fun openFilePicker() {
        pickCookiesLauncher.launch(arrayOf("*/*"))
    }

    private fun importCookies(uri: Uri) {
        lifecycleScope.launch {
            try {
                val target = File(filesDir, "cookies.txt")
                contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("cannot open")
                settings.saveCookiesPath(target.absolutePath)
                AppToast.show(this@SettingsActivity, com.ytdlpgui.R.string.msg_cookies_imported)
                renderCookiesState()
            } catch (_: Exception) {
                AppToast.showLong(this@SettingsActivity, com.ytdlpgui.R.string.msg_cookies_import_failed)
            }
        }
    }

    private fun removeCookies() {
        lifecycleScope.launch {
            settings.cookiesPath.firstOrNull()?.let { File(it).delete() }
            settings.saveCookiesPath(null)
            AppToast.show(this@SettingsActivity, com.ytdlpgui.R.string.msg_cookies_removed)
            renderCookiesState()
        }
    }

    companion object {
        const val EXTRA_ENGINE_VERSION = "engine_version"
    }
}
