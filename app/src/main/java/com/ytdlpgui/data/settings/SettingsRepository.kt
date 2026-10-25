package com.ytdlpgui.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "ytdlp_settings")

interface SettingsStore {
    val savedFolder: Flow<FolderSelection>
    val savedIndices: Flow<Pair<Int, Int>>
    val cookiesPath: Flow<String?>
    val themeDark: Flow<Boolean?>
    val wifiOnly: Flow<Boolean>
    val maxConcurrent: Flow<Int>
    val backgroundDownload: Flow<Boolean>
    val saveHistory: Flow<Boolean>
    suspend fun saveFolder(path: String?, treeUri: String?)
    suspend fun saveIndices(qualityIndex: Int, formatIndex: Int)
    suspend fun saveCookiesPath(path: String?)
    suspend fun saveThemeDark(dark: Boolean)
    suspend fun saveWifiOnly(enabled: Boolean)
    suspend fun saveMaxConcurrent(count: Int)
    suspend fun saveBackgroundDownload(enabled: Boolean)
    suspend fun saveSaveHistory(enabled: Boolean)

    data class FolderSelection(
        val path: String?,
        val treeUri: String?
    )
}

class SettingsRepository(private val context: Context) : SettingsStore {

    private object Keys {
        val PATH = stringPreferencesKey("download_path")
        val TREE_URI = stringPreferencesKey("saf_tree_uri")
        val QUALITY_INDEX = intPreferencesKey("quality_index")
        val FORMAT_INDEX = intPreferencesKey("format_index")
        val COOKIES_PATH = stringPreferencesKey("cookies_path")
        val THEME_DARK = intPreferencesKey("theme_dark")
        val WIFI_ONLY = androidx.datastore.preferences.core.booleanPreferencesKey("wifi_only")
        val MAX_CONCURRENT = intPreferencesKey("max_concurrent")
        val BG_DOWNLOAD = androidx.datastore.preferences.core.booleanPreferencesKey("bg_download")
        val SAVE_HISTORY = androidx.datastore.preferences.core.booleanPreferencesKey("save_history")
    }

    override val savedFolder: Flow<SettingsStore.FolderSelection> = context.settingsStore.data.map { prefs ->
        val rawPath = prefs[Keys.PATH]
        val migratedPath = if (rawPath != null && rawPath.endsWith("/yt-dlp")) {
            rawPath.removeSuffix("/yt-dlp") + "/ClipCatch"
        } else {
            rawPath
        }
        SettingsStore.FolderSelection(path = migratedPath, treeUri = prefs[Keys.TREE_URI])
    }

    override val savedIndices: Flow<Pair<Int, Int>> = context.settingsStore.data.map { prefs ->
        (prefs[Keys.QUALITY_INDEX] ?: 0) to (prefs[Keys.FORMAT_INDEX] ?: 0)
    }

    override suspend fun saveFolder(path: String?, treeUri: String?) {
        context.settingsStore.edit { prefs ->
            path?.let { prefs[Keys.PATH] = it }
            prefs[Keys.TREE_URI] = treeUri ?: ""
        }
    }

    override suspend fun saveIndices(qualityIndex: Int, formatIndex: Int) {
        context.settingsStore.edit { prefs ->
            prefs[Keys.QUALITY_INDEX] = qualityIndex
            prefs[Keys.FORMAT_INDEX] = formatIndex
        }
    }

    override val cookiesPath: Flow<String?> = context.settingsStore.data.map { prefs ->
        prefs[Keys.COOKIES_PATH]?.takeIf { it.isNotBlank() }
    }

    override suspend fun saveCookiesPath(path: String?) {
        context.settingsStore.edit { prefs ->
            if (path.isNullOrBlank()) prefs.remove(Keys.COOKIES_PATH)
            else prefs[Keys.COOKIES_PATH] = path
        }
    }

    override val themeDark: Flow<Boolean?> = context.settingsStore.data.map { prefs ->
        val raw = prefs[Keys.THEME_DARK]
        when (raw) {
            null -> null
            1 -> true
            0 -> false
            else -> null
        }
    }

    override suspend fun saveThemeDark(dark: Boolean) {
        context.settingsStore.edit { prefs ->
            prefs[Keys.THEME_DARK] = if (dark) 1 else 0
        }
    }

    override val wifiOnly: Flow<Boolean> = context.settingsStore.data.map { prefs ->
        prefs[Keys.WIFI_ONLY] ?: false
    }

    override suspend fun saveWifiOnly(enabled: Boolean) {
        context.settingsStore.edit { prefs ->
            prefs[Keys.WIFI_ONLY] = enabled
        }
    }

    override val maxConcurrent: Flow<Int> = context.settingsStore.data.map { prefs ->
        prefs[Keys.MAX_CONCURRENT] ?: 2
    }

    override suspend fun saveMaxConcurrent(count: Int) {
        context.settingsStore.edit { prefs ->
            prefs[Keys.MAX_CONCURRENT] = count.coerceIn(1, 6)
        }
    }

    override val backgroundDownload: Flow<Boolean> = context.settingsStore.data.map { prefs ->
        prefs[Keys.BG_DOWNLOAD] ?: true
    }

    override suspend fun saveBackgroundDownload(enabled: Boolean) {
        context.settingsStore.edit { prefs ->
            prefs[Keys.BG_DOWNLOAD] = enabled
        }
    }

    override val saveHistory: Flow<Boolean> = context.settingsStore.data.map { prefs ->
        prefs[Keys.SAVE_HISTORY] ?: true
    }

    override suspend fun saveSaveHistory(enabled: Boolean) {
        context.settingsStore.edit { prefs ->
            prefs[Keys.SAVE_HISTORY] = enabled
        }
    }
}
