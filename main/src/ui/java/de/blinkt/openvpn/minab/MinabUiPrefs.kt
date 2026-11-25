/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabUiPrefs: manages Day/Night theme mode and in-app language selection
 * consistently across API 23..37 via AppCompatDelegate.
 */

package de.blinkt.openvpn.minab

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import de.blinkt.openvpn.R

object MinabUiPrefs {
    private const val PREFS_NAME = "MinabUiPrefs"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_LANGUAGE = "language_tag"

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    const val LANG_SYSTEM = "system"

    data class LanguageOption(val tag: String, val nativeLabel: String)

    val SUPPORTED_LANGUAGES: List<LanguageOption> = listOf(
        LanguageOption(LANG_SYSTEM, ""),
        LanguageOption("fa", "فارسی"),
        LanguageOption("en", "English"),
        LanguageOption("ar", "العربية"),
        LanguageOption("tr", "Türkçe"),
        LanguageOption("ru", "Русский"),
        LanguageOption("de", "Deutsch"),
        LanguageOption("fr", "Français"),
        LanguageOption("zh-CN", "简体中文")
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @JvmStatic
    fun getThemeMode(context: Context): String =
        prefs(context).getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM

    @JvmStatic
    fun setThemeMode(context: Context, mode: String) {
        val clean = when (mode) {
            THEME_LIGHT, THEME_DARK -> mode
            else -> THEME_SYSTEM
        }
        prefs(context).edit().putString(KEY_THEME, clean).apply()
        applyThemeMode(clean)
    }

    @JvmStatic
    fun getLanguageTag(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, LANG_SYSTEM) ?: LANG_SYSTEM

    @JvmStatic
    fun setLanguageTag(context: Context, tag: String) {
        val clean = if (SUPPORTED_LANGUAGES.any { it.tag.equals(tag, ignoreCase = true) }) {
            SUPPORTED_LANGUAGES.first { it.tag.equals(tag, ignoreCase = true) }.tag
        } else {
            LANG_SYSTEM
        }
        prefs(context).edit().putString(KEY_LANGUAGE, clean).apply()
        applyLanguageTag(clean)
    }

    @JvmStatic
    fun applySavedPreferences(context: Context) {
        try {
            applyThemeMode(getThemeMode(context))
        } catch (_: Exception) {
        }
        try {
            applyLanguageTag(getLanguageTag(context))
        } catch (_: Exception) {
        }
    }

    private fun applyThemeMode(mode: String) {
        val nightMode = when (mode) {
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        if (AppCompatDelegate.getDefaultNightMode() != nightMode) {
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }
    }

    private fun applyLanguageTag(tag: String) {
        val current = AppCompatDelegate.getApplicationLocales()
        if (tag == LANG_SYSTEM) {
            if (!current.isEmpty) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            }
            return
        }
        val desired = LocaleListCompat.forLanguageTags(tag)
        if (!current.toLanguageTags().equals(desired.toLanguageTags(), ignoreCase = true)) {
            AppCompatDelegate.setApplicationLocales(desired)
        }
    }

    @JvmStatic
    fun themeDisplayName(context: Context, mode: String = getThemeMode(context)): String =
        when (mode) {
            THEME_LIGHT -> context.getString(R.string.minab_theme_light)
            THEME_DARK -> context.getString(R.string.minab_theme_dark)
            else -> context.getString(R.string.minab_theme_system)
        }

    @JvmStatic
    fun languageDisplayName(context: Context, tag: String = getLanguageTag(context)): String {
        if (tag == LANG_SYSTEM) return context.getString(R.string.minab_lang_system)
        return SUPPORTED_LANGUAGES.firstOrNull { it.tag.equals(tag, ignoreCase = true) }
            ?.nativeLabel
            ?: context.getString(R.string.minab_lang_system)
    }
}
