package com.lyricsadapter.app.data

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi

/**
 * The app's own language, as desktop's language setting: one of the six
 * languages desktop ships, or the system's. Android keeps the choice itself
 * (per-app languages, Android 13 and later) and restarts the screen with it;
 * earlier versions follow the system language.
 */
object AppLanguage {
    /** Desktop's `LANGUAGE_OPTIONS`, with each language named in itself. */
    val options: List<Pair<String, String>> = listOf(
        "zh" to "中文",
        "en" to "English",
        "ja" to "日本語",
        "ko" to "한국어",
        "de" to "Deutsch",
        "fr" to "Français",
    )

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** The chosen language tag, or null when the app follows the system. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun current(context: Context): String? =
        context.getSystemService(LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun set(context: Context, tag: String?) {
        context.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    }
}
