package com.lyricsadapter.app.data

import android.content.Context
import android.content.SharedPreferences
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

/** App preferences, kept in shared preferences. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val colorMode: Setting<ColorMode> = enumSetting("colorMode", ColorMode.System)
    val activeSlot: Setting<SlotId> = enumSetting("activeSlot", SlotId.Local)

    private inline fun <reified T : Enum<T>> enumSetting(key: String, default: T): Setting<T> {
        val stored = prefs.getString(key, null)
        val initial = enumValues<T>().firstOrNull { it.name == stored } ?: default
        return Setting(prefs, key, initial) { it.name }
    }
}
