package com.lyricsadapter.pluginhost

import java.util.concurrent.ConcurrentHashMap

/**
 * Persistence behind the legacy host API. Secrets are scoped by plugin id;
 * setting keys arrive already prefixed with `plugin:<id>:`, as on desktop.
 */
interface PluginDataStore {
    fun readSecret(pluginId: String, name: String): String
    fun writeSecrets(pluginId: String, entries: Map<String, String>)
    fun readSetting(key: String): String?
    fun writeSetting(key: String, value: String?)
}

/** Keeps everything in memory; for tests and the spike, where nothing may be persisted. */
class InMemoryPluginDataStore : PluginDataStore {
    private val secrets = ConcurrentHashMap<Pair<String, String>, String>()
    private val settings = ConcurrentHashMap<String, String>()

    override fun readSecret(pluginId: String, name: String): String = secrets[pluginId to name].orEmpty()

    override fun writeSecrets(pluginId: String, entries: Map<String, String>) {
        entries.forEach { (name, value) -> secrets[pluginId to name] = value }
    }

    override fun readSetting(key: String): String? = settings[key]

    override fun writeSetting(key: String, value: String?) {
        if (value == null) settings.remove(key) else settings[key] = value
    }
}
