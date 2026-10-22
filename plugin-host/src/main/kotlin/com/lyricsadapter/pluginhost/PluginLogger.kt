package com.lyricsadapter.pluginhost

enum class PluginLogLevel {
    DEBUG, INFO, WARN, ERROR;

    companion object {
        fun parse(value: String): PluginLogLevel = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: INFO
    }
}

/** Receives plugin console output and logger calls. */
fun interface PluginLogger {
    fun log(pluginId: String, level: PluginLogLevel, message: String)
}
