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

/** Desktop's `libraryMode`: the poster wall (the default) or the classic list. */
enum class LibraryLayout { Wall, List }

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

/**
 * Where playback stood, so a fresh start can pick it up again paused, as
 * desktop restores each slot's `currentTrackIndex` and `currentTime`.
 */
data class LastPlayback(val slot: SlotId, val trackId: String, val positionMs: Long) {
    fun encode(): String = "${slot.name}\n$trackId\n$positionMs"

    companion object {
        fun decode(text: String?): LastPlayback? {
            val parts = text?.split('\n') ?: return null
            if (parts.size != 3) return null
            val slot = SlotId.entries.firstOrNull { it.name == parts[0] } ?: return null
            return LastPlayback(slot, parts[1], parts[2].toLongOrNull() ?: 0L)
        }
    }
}

/** Focus mode's look, with desktop's names, ranges and defaults (`settingsManager.ts`). */
data class FocusSettings(
    val backgroundBlur: Int = 80,
    val fontSize: Int = 32,
    val lineSpacing: Int = 30,
    val inactiveBlur: Int = 2,
    val flowingBackground: Boolean = false,
)

/** App preferences, kept in shared preferences; secrets go through [SecretStore]. */
class AppSettings(context: Context, private val secrets: SecretStore) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val colorMode: Setting<ColorMode> = enumSetting("colorMode", ColorMode.System)
    val activeSlot: Setting<SlotId> = enumSetting("activeSlot", SlotId.Local)
    val libraryLayout: Setting<LibraryLayout> = enumSetting("libraryMode", LibraryLayout.Wall)

    /** Desktop's playlist overrides, hidden ones only: `source:id` of each playlist left out of the library. */
    val hiddenPlaylists: Setting<Set<String>> = stringSetSetting("hiddenPlaylists")

    /** The folders the local library follows, as document tree URIs. */
    val libraryFolders: Setting<Set<String>> = stringSetSetting("libraryFolders")

    /** Tracks of those folders that the user removed from the library; looking through a folder again leaves them out. */
    val folderExclusions: Setting<Set<String>> = stringSetSetting("folderExclusions")

    /** What played last, and where; null until something has played. */
    val lastPlayback: Setting<LastPlayback?> =
        Setting(prefs, "lastPlayback", LastPlayback.decode(prefs.getString("lastPlayback", null))) { it?.encode().orEmpty() }

    /** `source:id` of the playlist open in the playlist slot, fetched again on the next start. */
    val openedPlaylist: Setting<String?> = Setting(prefs, "openedPlaylist", prefs.getString("openedPlaylist", null)?.ifEmpty { null }) { it.orEmpty() }

    /** Desktop's `plugin_lyrics_language`: the language lyrics are translated into. */
    val translationLanguage: Setting<String> = Setting(prefs, "plugin_lyrics_language", prefs.getString("plugin_lyrics_language", null) ?: "zh") { it }

    val focusBackgroundBlur = RangeSetting(prefs, "focusBgBlurRadius", 40..80, 80)
    val focusFontSize = RangeSetting(prefs, "focusLyricsFontSize", 24..40, 32)
    val focusLineSpacing = RangeSetting(prefs, "focusLyricLineSpacing", 12..48, 30)
    val focusInactiveBlur = RangeSetting(prefs, "focusInactiveLyricBlur", 0..12, 2)

    /** Desktop's `focusAmllBackgroundEnabled`: the flowing background instead of the blurred cover; off by default. */
    val focusFlowingBackground: Setting<Boolean> = Setting(prefs, "focusAmllBackgroundEnabled", prefs.getString("focusAmllBackgroundEnabled", null) == "true") { it.toString() }

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

    /** A set of strings without line breaks, as URIs and ids are. */
    private fun stringSetSetting(key: String): Setting<Set<String>> =
        Setting(prefs, key, prefs.getString(key, null)?.split('\n')?.filterTo(LinkedHashSet(), String::isNotEmpty).orEmpty()) { it.joinToString("\n") }

    private inline fun <reified T : Enum<T>> enumSetting(key: String, default: T): Setting<T> {
        val stored = prefs.getString(key, null)
        val initial = enumValues<T>().firstOrNull { it.name == stored } ?: default
        return Setting(prefs, key, initial) { it.name }
    }
}
