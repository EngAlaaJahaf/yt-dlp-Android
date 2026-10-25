package com.ytdlpgui.data.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking

object ThemeMode {

    /** يطابق التفضيل المحفوظ (null = اتبع النظام) قبل عرض أي واجهة. */
    fun apply(context: Context) {
        runCatching {
            val dark = runBlocking {
                SettingsRepository(context.applicationContext).themeDark.firstOrNull()
            }
            when (dark) {
                true -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                false -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                null -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }.onFailure { android.util.Log.e("ThemeGate", "apply failed", it) }
    }

    fun isDark(context: Context): Boolean {
        val mode = AppCompatDelegate.getDefaultNightMode()
        if (mode == AppCompatDelegate.MODE_NIGHT_YES) return true
        if (mode == AppCompatDelegate.MODE_NIGHT_NO) return false
        val nightFlags = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return nightFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    val isDarkNow: Boolean
        get() = AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
}
