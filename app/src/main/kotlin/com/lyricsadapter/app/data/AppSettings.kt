package com.lyricsadapter.app.data

import android.content.Context
import android.content.SharedPreferences
import com.lyricsadapter.webdav.WebDavConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Desktop's `appearance.colorMode`: follow the system, or force light or dark. */
enum class ColorMode { System, Light, Dark }

/**
 * Desktop's four library slots: local files, the WebDAV library, the queue of
 * online songs, and an opened online playlist. Each is a list of its own.
 */
enum class SlotId { Local, Cloud, Online, Playlist }

/** One stored choice, readable as a flow. */
class Setting<T> internal constructor(
    private val prefs: SharedPreferences,
    private val key: String,
    initial: T,
    private val encode: (T) -> String,
) {
    private val state = MutableStateFlow(initial)
    val value: StateFlow<T> = state.asStateFlow()

    fun set(value: T) {
        state.value = value
        prefs.edit().putString(key, encode(value)).apply()
    }
}

/** A whole number kept within [range], as desktop clamps its numeric settings. */
class RangeSetting internal constructor(prefs: SharedPreferences, key: String, val range: IntRange, val default: Int) {
    private val setting = Setting(prefs, key, (prefs.getString(key, null)?.toIntOrNull() ?: default).coerceIn(range)) { it.toString() }
    val value: StateFlow<Int> = setting.value
    fun set(value: Int) = setting.set(value.coerceIn(range))
}

/** Focus mode's look, with desktop's names, ranges and defaults (`settingsManager.ts`). */
data class FocusSettings(
    val backgroundBlur: Int = 80,
    val fontSize: Int = 32,
    val lineSpacing: Int = 30,
    val inactiveBlur: Int = 2,
)

/** App preferences, kept in shared preferences; secrets go through [SecretStore]. */
class AppSettings(context: Context, private val secrets: SecretStore) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val colorMode: Setting<ColorMode> = enumSetting("colorMode", ColorMode.System)
    val activeSlot: Setting<SlotId> = enumSetting("activeSlot", SlotId.Local)

    val focusBackgroundBlur = RangeSetting(prefs, "focusBgBlurRadius", 40..80, 80)
    val focusFontSize = RangeSetting(prefs, "focusLyricsFontSize", 24..40, 32)
    val focusLineSpacing = RangeSetting(prefs, "focusLyricLineSpacing", 12..48, 30)
    val focusInactiveBlur = RangeSetting(prefs, "focusInactiveLyricBlur", 0..12, 2)

    private val webDav = MutableStateFlow(loadWebDav())

    /** The saved WebDAV library, or null when none is connected. */
    val webDavConfig: StateFlow<WebDavConfig?> = webDav.asStateFlow()

    fun saveWebDav(config: WebDavConfig?) {
        prefs.edit()
            .putString("webdavServerUrl", config?.serverUrl)
            .putString("webdavUsername", config?.username)
            .apply()
        secrets.put("webdavPassword", config?.password)
        webDav.value = config
    }

    private fun loadWebDav(): WebDavConfig? {
        val url = prefs.getString("webdavServerUrl", null) ?: return null
        val username = prefs.getString("webdavUsername", null) ?: return null
        val password = secrets.get("webdavPassword") ?: return null
        return WebDavConfig(url, username, password)
    }

    private inline fun <reified T : Enum<T>> enumSetting(key: String, default: T): Setting<T> {
        val stored = prefs.getString(key, null)
        val initial = enumValues<T>().firstOrNull { it.name == stored } ?: default
        return Setting(prefs, key, initial) { it.name }
    }
}
