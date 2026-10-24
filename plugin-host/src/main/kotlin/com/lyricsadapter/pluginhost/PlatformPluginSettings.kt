package com.lyricsadapter.pluginhost

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/**
 * A platform plugin's configuration as desktop keeps it
 * (`readPluginConfiguration`, electron/services/pluginRuntime.ts): each value
 * is JSON in the setting `plugin:<id>:configuration:<key>`, secret fields are
 * the plugin's secrets of the same name, and anything stored that no longer
 * fits the field falls back to the default.
 */
class PlatformPluginSettings(private val manifest: PlatformManifest, private val store: PluginDataStore) {
    fun values(): Map<String, JsonPrimitive> = manifest.configuration.associate { field ->
        field.key to if (field.format == SECRET) JsonPrimitive(store.readSecret(manifest.id, field.key)) else stored(field) ?: field.default
    }

    /** Validates and stores one value, as desktop's `pluginConfigurationWrite`. */
    fun set(key: String, value: JsonPrimitive) {
        val field = manifest.configuration.firstOrNull { it.key == key } ?: throw IllegalArgumentException("Unknown configuration key")
        require(fits(field, value)) { "Invalid plugin configuration value" }
        if (field.format == SECRET) store.writeSecrets(manifest.id, mapOf(key to value.content))
        else store.writeSetting(settingKey(key), value.toString())
    }

    /** The declared origins plus the origins of configured endpoint fields. */
    fun allowedOrigins(): Set<String> {
        val values = values()
        return manifest.network.toSet() + manifest.configuration
            .filter { it.format == ENDPOINT }
            .mapNotNull { field -> values[field.key]?.content?.takeIf(String::isNotEmpty)?.let(PlatformManifest::networkOrigin) }
    }

    private fun stored(field: ConfigurationField): JsonPrimitive? = try {
        store.readSetting(settingKey(field.key))?.let { Json.parseToJsonElement(it) as? JsonPrimitive }?.takeIf { fits(field, it) }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun fits(field: ConfigurationField, value: JsonPrimitive): Boolean {
        val typed = when (field.type) {
            ConfigurationType.String -> value.isString && value.content.length <= if (field.format == SECRET) 65_536 else 16_384
            ConfigurationType.Boolean -> !value.isString && (value.content == "true" || value.content == "false")
            ConfigurationType.Number -> !value.isString && value.content.toDoubleOrNull()?.isFinite() == true
        }
        return typed && (field.enum == null || value in field.enum) &&
            (field.format != ENDPOINT || value.content.isEmpty() || PlatformManifest.networkOrigin(value.content) != null)
    }

    private fun settingKey(key: String) = "plugin:${manifest.id}:configuration:$key"

    private companion object {
        const val SECRET = "secret"
        const val ENDPOINT = "endpoint"
    }
}
