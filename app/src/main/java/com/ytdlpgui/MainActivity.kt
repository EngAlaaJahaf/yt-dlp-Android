package com.ytdlpgui

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ytdlpgui.databinding.ActivityMainBinding
import com.ytdlpgui.ui.MainViewModel
import com.ytdlpgui.ui.downloads.DownloadsFragment
import com.ytdlpgui.ui.home.HomeFragment
import com.ytdlpgui.ui.library.LibraryFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel

    private lateinit var homeFragment: HomeFragment
    private lateinit var downloadsFragment: DownloadsFragment
    private lateinit var libraryFragment: LibraryFragment
    private lateinit var settingsFragment: com.ytdlpgui.ui.settings.SettingsFragment

    private var currentTabIndex = 0

    companion object {
        private const val TAG_HOME = "tag_home"
        private const val TAG_DOWNLOADS = "tag_downloads"
        private const val TAG_LIBRARY = "tag_library"
        private const val TAG_SETTINGS = "tag_settings"
        const val EXTRA_TAB_INDEX = "extra_tab_index"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.ytdlpgui.data.settings.ThemeMode.apply(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(
            this,
            viewModelFactory {
                initializer {
                    MainViewModel(
                        engine = com.ytdlpgui.data.engine.DownloadEngine(applicationContext),
                        store = com.ytdlpgui.data.db.RoomDownloadStore(applicationContext),
                        settings = com.ytdlpgui.data.settings.SettingsRepository(applicationContext),
                        startService = { com.ytdlpgui.service.DownloadService.start(applicationContext) },
                        stopService = { com.ytdlpgui.service.DownloadService.stop(applicationContext) }
                    )
                }
            }
        )[MainViewModel::class.java]

        setupToolbar()
        setupFragments(savedInstanceState)
        setupBottomNav()
        setupBackPressHandler()
        setupSplash(savedInstanceState)
        observeDownloadsBadge()

        if (savedInstanceState != null) {
            currentTabIndex = savedInstanceState.getInt("saved_tab_index", 0)
            binding.bottomNav.selectedItemId = when (currentTabIndex) {
                1 -> R.id.nav_downloads
                2 -> R.id.nav_library
                3 -> R.id.nav_settings
                else -> R.id.nav_home
            }
            switchTab(currentTabIndex)
        }

        handleSharedText(intent)
        handleInitialTab(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("saved_tab_index", currentTabIndex)
    }

    private fun observeDownloadsBadge() {
        lifecycleScope.launch {
            com.ytdlpgui.data.db.DownloadDatabase.get(applicationContext).downloadDao().recentFlow().collect { list ->
                val activeCount = list.count {
                    it.status == com.ytdlpgui.data.db.DownloadStatus.RUNNING ||
                    it.status == com.ytdlpgui.data.db.DownloadStatus.QUEUED
                }
                val badge = binding.bottomNav.getOrCreateBadge(R.id.nav_downloads)
                if (activeCount > 0) {
                    badge.isVisible = true
                    badge.number = activeCount
                    badge.backgroundColor = getColor(R.color.vidmate_red)
                } else {
                    badge.isVisible = false
                }
            }
        }
    }

    private fun setupSplash(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) return
        binding.splashOverlay.visibility = android.view.View.VISIBLE
        val totalMs = 2600L
        val start = android.os.SystemClock.elapsedRealtime()
        binding.splashSkipBtn.setOnClickListener { binding.splashOverlay.animate().alpha(0f).setDuration(220L).withEndAction { binding.splashOverlay.visibility = android.view.View.GONE }.start() }
        lifecycleScope.launch {
            while (android.os.SystemClock.elapsedRealtime() - start < totalMs) {
                val elapsed = android.os.SystemClock.elapsedRealtime() - start
                val remain = (totalMs - elapsed).coerceAtLeast(0)
                binding.splashCountdown.countText = ((remain / 1000) + 1).coerceAtMost(3).toString()
                binding.splashCountdown.progress = elapsed.toFloat() / totalMs
                kotlinx.coroutines.delay(50)
            }
            binding.splashOverlay.animate().alpha(0f).setDuration(220L)
                .withEndAction { binding.splashOverlay.visibility = android.view.View.GONE }
                .start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedText(intent)
        handleInitialTab(intent)
    }

    private fun setupToolbar() {
        binding.toolbar.inflateMenu(R.menu.top_menu)
        setupBrandGradients()
        refreshThemeIcon()
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_theme -> {
                    toggleTheme()
                    true
                }
                R.id.action_settings -> {
                    binding.bottomNav.selectedItemId = R.id.nav_settings
                    true
                }
                R.id.action_update_engine -> {
                    viewModel.startUpdateEngine()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupBrandGradients() {
        val startColor = android.graphics.Color.parseColor("#00B36E")
        val endColor = android.graphics.Color.parseColor("#1C63E1")
        applyTextGradient(binding.tvBrandCatch, startColor, endColor)
        applyTextGradient(binding.tvSplashCatch, startColor, endColor)
    }

    private fun applyTextGradient(textView: android.widget.TextView, startColor: Int, endColor: Int) {
        textView.post {
            val width = textView.paint.measureText(textView.text.toString())
            if (width > 0) {
                val shader = android.graphics.LinearGradient(
                    0f, 0f, width, 0f,
                    startColor, endColor,
                    android.graphics.Shader.TileMode.CLAMP
                )
                textView.paint.shader = shader
                textView.invalidate()
            }
        }
    }

    private fun toggleTheme() {
        val dark = !com.ytdlpgui.data.settings.ThemeMode.isDark(this)
        lifecycleScope.launch {
            com.ytdlpgui.data.settings.SettingsRepository(applicationContext).saveThemeDark(dark)
            com.ytdlpgui.data.settings.ThemeMode.apply(applicationContext)
            refreshThemeIcon()
            setupBrandGradients()
            com.ytdlpgui.ui.common.AppToast.show(
                this@MainActivity,
                if (dark) com.ytdlpgui.R.string.msg_theme_dark_on else com.ytdlpgui.R.string.msg_theme_dark_off
            )
        }
    }

    private fun refreshThemeIcon() {
        val item = binding.toolbar.menu.findItem(R.id.action_theme) ?: return
        val dark = com.ytdlpgui.data.settings.ThemeMode.isDark(this)
        item.setIcon(if (dark) R.drawable.ic_sun else R.drawable.ic_moon)
    }

    private fun setupFragments(savedInstanceState: Bundle?) {
        val fm = supportFragmentManager
        val existingHome = fm.findFragmentByTag(TAG_HOME) as? HomeFragment
        val existingDownloads = fm.findFragmentByTag(TAG_DOWNLOADS) as? DownloadsFragment
        val existingLibrary = fm.findFragmentByTag(TAG_LIBRARY) as? LibraryFragment
        val existingSettings = fm.findFragmentByTag(TAG_SETTINGS) as? com.ytdlpgui.ui.settings.SettingsFragment

        homeFragment = existingHome ?: HomeFragment()
        downloadsFragment = existingDownloads ?: DownloadsFragment()
        libraryFragment = existingLibrary ?: LibraryFragment()
        settingsFragment = existingSettings ?: com.ytdlpgui.ui.settings.SettingsFragment()

        if (savedInstanceState == null) {
            fm.beginTransaction()
                .add(R.id.fragmentContainer, homeFragment, TAG_HOME)
                .add(R.id.fragmentContainer, downloadsFragment, TAG_DOWNLOADS).hide(downloadsFragment)
                .add(R.id.fragmentContainer, libraryFragment, TAG_LIBRARY).hide(libraryFragment)
                .add(R.id.fragmentContainer, settingsFragment, TAG_SETTINGS).hide(settingsFragment)
                .commit()
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    switchTab(0)
                    true
                }
                R.id.nav_downloads -> {
                    switchTab(1)
                    true
                }
                R.id.nav_library -> {
                    switchTab(2)
                    true
                }
                R.id.nav_settings -> {
                    switchTab(3)
                    true
                }
                else -> false
            }
        }
    }

    private fun switchTab(index: Int) {
        val transaction = supportFragmentManager.beginTransaction()
        when (index) {
            0 -> {
                transaction.show(homeFragment).hide(downloadsFragment).hide(libraryFragment).hide(settingsFragment)
                binding.appLogo.visibility = android.view.View.VISIBLE
                binding.brandWordmark.visibility = android.view.View.VISIBLE
                binding.toolbarTitle.visibility = android.view.View.GONE
            }
            1 -> {
                transaction.show(downloadsFragment).hide(homeFragment).hide(libraryFragment).hide(settingsFragment)
                downloadsFragment.onTabSelected()
                binding.appLogo.visibility = android.view.View.GONE
                binding.brandWordmark.visibility = android.view.View.GONE
                binding.toolbarTitle.visibility = android.view.View.VISIBLE
                binding.toolbarTitle.text = getString(R.string.downloads_title)
            }
            2 -> {
                transaction.show(libraryFragment).hide(homeFragment).hide(downloadsFragment).hide(settingsFragment)
                libraryFragment.onTabSelected()
                binding.appLogo.visibility = android.view.View.GONE
                binding.brandWordmark.visibility = android.view.View.GONE
                binding.toolbarTitle.visibility = android.view.View.VISIBLE
                binding.toolbarTitle.text = getString(R.string.library_title)
            }
            3 -> {
                transaction.show(settingsFragment).hide(homeFragment).hide(downloadsFragment).hide(libraryFragment)
                binding.appLogo.visibility = android.view.View.GONE
                binding.brandWordmark.visibility = android.view.View.GONE
                binding.toolbarTitle.visibility = android.view.View.VISIBLE
                binding.toolbarTitle.text = getString(R.string.settings_title)
            }
        }
        transaction.commit()
        currentTabIndex = index
    }

    private fun setupBackPressHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTabIndex != 0) {
                    binding.bottomNav.selectedItemId = R.id.nav_home
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun handleInitialTab(intent: Intent?) {
        val tab = intent?.getIntExtra(EXTRA_TAB_INDEX, -1) ?: return
        if (tab in 0..3) {
            when (tab) {
                0 -> binding.bottomNav.selectedItemId = R.id.nav_home
                1 -> binding.bottomNav.selectedItemId = R.id.nav_downloads
                2 -> binding.bottomNav.selectedItemId = R.id.nav_library
                3 -> binding.bottomNav.selectedItemId = R.id.nav_settings
            }
        }
    }

    private fun handleSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val url = com.ytdlpgui.data.model.UrlSanitizer.sanitize(text) ?: return
        binding.bottomNav.selectedItemId = R.id.nav_home
        homeFragment.setUrl(url)
    }
}
