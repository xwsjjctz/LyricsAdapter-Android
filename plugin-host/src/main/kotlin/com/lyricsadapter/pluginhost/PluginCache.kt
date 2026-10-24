package com.lyricsadapter.pluginhost

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One platform plugin's cache, as desktop's `PluginCacheStore`: a JSON file
 * per key in the plugin's data directory, or memory without one (tests and
 * the spike). Writes go through a temporary file and a rename, so a crash
 * leaves either the old or the new value.
 */
class PluginCache(private val directory: File?) {
    private val memory = ConcurrentHashMap<String, String>()

    fun get(key: String): String? {
        check(key)
        if (directory == null) return memory[key]
        val file = file(key)
        return if (file.exists()) file.readText() else null
    }

    fun set(key: String, json: String?) {
        check(key)
        if (json == null) {
            if (directory == null) memory.remove(key) else file(key).delete()
            return
        }
        require(json.toByteArray().size <= VALUE_LIMIT) { "Plugin cache value exceeds 1 MiB or is not JSON" }
        if (directory == null) {
            memory[key] = json
            return
        }
        directory.mkdirs()
        val temporary = File(directory, ".${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(json)
            if (!temporary.renameTo(file(key))) throw IllegalStateException("Could not save plugin cache entry")
        } finally {
            temporary.delete()
        }
    }

    fun clear() {
        if (directory == null) memory.clear() else directory.listFiles()?.forEach { it.delete() }
    }

    /** How many entries and bytes the cache holds, for the settings page. */
    fun usage(): Pair<Int, Long> {
        if (directory == null) return memory.size to memory.values.sumOf { it.length.toLong() }
        val files = directory.listFiles { file -> file.name.endsWith(SUFFIX) }.orEmpty()
        return files.size to files.sumOf { it.length() }
    }

    private fun file(key: String) = File(directory, "$key$SUFFIX")

    private fun check(key: String) = require(KEY.matches(key) && ".." !in key) { "Invalid plugin cache key" }

    private companion object {
        /** Desktop's `PLUGIN_CACHE_KEY`: long enough for a SHA-256 track id plus a short prefix or suffix. */
        val KEY = Regex("^[a-z0-9][a-z0-9._-]{0,127}$")
        const val VALUE_LIMIT = 1_048_576
        const val SUFFIX = ".json"
    }
}
