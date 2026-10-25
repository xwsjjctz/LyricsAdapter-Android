package com.lyricsadapter.pluginhost

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI

/** The value a configuration field holds: text, a switch or a number. */
enum class ConfigurationType { String, Boolean, Number }

/** Desktop's `ConfigurationField` (core API 1.1). */
data class ConfigurationField(
    val key: String,
    val title: String,
    val type: ConfigurationType,
    val default: JsonPrimitive,
    val enum: List<JsonPrimitive>?,
    val description: String?,
    /** `secret` fields live with the plugin's secrets; `endpoint` fields add their origin to the network permission. */
    val format: String?,
)

/** One provider a plugin contributes, with the lyrics.translation 1.2 options. */
data class ProviderContribution(val type: String, val id: String, val name: String, val languages: List<String>?, val timeoutSeconds: Int?)

/**
 * A plugin on desktop's plugin platform (`manifestVersion: 1`), validated as
 * desktop's `validatePlatformManifest` (electron/services/pluginManifest.ts)
 * does. Android hosts the music.source and lyrics.translation extensions.
 */
data class PlatformManifest(
    val id: String,
    val name: String,
    val version: String,
    val main: String,
    val homepage: String?,
    val network: List<String>,
    val ownsSecrets: Boolean,
    val providers: List<ProviderContribution>,
    val configuration: List<ConfigurationField>,
    /** The manifest as published, handed to the plugin runtime. */
    val json: JsonObject,
) {
    /** The music source this plugin offers, if any. */
    val musicSource: ProviderContribution? get() = providers.firstOrNull { it.type == MUSIC_SOURCE }

    /** The lyrics translation providers this plugin offers. */
    val translators: List<ProviderContribution> get() = providers.filter { it.type == TRANSLATION }

    /** Desktop's `translationTimeoutMs`. */
    fun translationTimeoutMillis(providerId: String): Long =
        translators.firstOrNull { it.id == providerId }?.timeoutSeconds?.let { it * 1000L } ?: DEFAULT_TRANSLATION_TIMEOUT_MILLIS

    companion object {
        const val CORE_API = "1.1.0"
        const val TRANSLATION = "lyrics.translation"
        const val MUSIC_SOURCE = "music.source"
        val EXTENSION_APIS = mapOf(MUSIC_SOURCE to "1.0.0", TRANSLATION to "1.2.0")
        const val DEFAULT_TRANSLATION_TIMEOUT_MILLIS = 180_000L

        private val PLUGIN_ID = Regex("^[a-z][a-z0-9-]{0,63}$")
        private val VERSION = Regex("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")
        private val PACKAGE_VERSION = Regex("^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?$")
        private val RANGE = Regex("^[~^]?(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)$")
        private val MAIN = Regex("^[\\w.-]+\\.cjs$")
        val LANGUAGE_TAG = Regex("^[a-zA-Z]{2,8}(?:-[a-zA-Z0-9]{1,8})*$")
        private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]")

        /** Validates [json]; throws [IllegalArgumentException] with the reason when it is not acceptable. */
        fun parse(json: JsonObject): PlatformManifest {
            require((json["manifestVersion"] as? JsonPrimitive)?.intOrNull == 1) { "Unsupported plugin manifest version" }
            val id = json.text("id")
            val name = json.text("name")
            val version = json.text("version")
            val main = json.text("main")
            require(id != null && PLUGIN_ID.matches(id) && !name.isNullOrBlank() && version != null && PACKAGE_VERSION.matches(version)) { "Invalid plugin manifest" }
            require(main != null && MAIN.matches(main)) { "Plugin entry must be a local .cjs file" }
            val coreApi = json.text("coreApi")
            require(coreApi != null && supportsApi(CORE_API, coreApi)) { "Unsupported core API: $coreApi (host $CORE_API)" }
            val extensionApis = json.ranges("extensionApis") ?: throw IllegalArgumentException("Invalid platform manifest")
            val optional = json["optionalExtensionApis"]?.let { (it as? JsonObject)?.let { _ -> json.ranges("optionalExtensionApis") } ?: throw IllegalArgumentException("Invalid platform manifest") }.orEmpty()
            for ((type, range) in extensionApis) {
                val host = EXTENSION_APIS[type]
                require(host != null && supportsApi(host, range)) { "Unsupported extension API: $type $range" }
            }
            val declared = extensionApis.keys + optional.keys
            val contributes = json["contributes"] as? JsonObject ?: throw IllegalArgumentException("Invalid platform manifest")
            val providers = (contributes["providers"] as? JsonArray ?: throw IllegalArgumentException("Invalid platform manifest")).map { item ->
                parseProvider(item as? JsonObject ?: throw IllegalArgumentException("Invalid provider contribution"), declared)
            }
            require(providers.map { "${it.type}:${it.id}" }.toSet().size == providers.size) { "Invalid provider contribution" }
            require(providers.count { it.type == MUSIC_SOURCE } <= 1) { "Only one music source per plugin is supported" }
            require(providers.any { it.type in EXTENSION_APIS }) { "This plugin offers nothing Android can use yet" }

            val permissions = json["permissions"]
            var network = emptyList<String>()
            var ownsSecrets = false
            if (permissions != null) {
                permissions as? JsonObject ?: throw IllegalArgumentException("Invalid plugin permissions")
                val secrets = permissions["secrets"]
                require(secrets == null || (secrets as? JsonPrimitive)?.content == "own") { "Invalid plugin permissions" }
                ownsSecrets = secrets != null
                permissions["network"]?.let { origins ->
                    network = (origins as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: "" }
                        ?: throw IllegalArgumentException("Invalid plugin permissions")
                    require(network.all { networkOrigin(it) == it }) { "Invalid plugin permissions" }
                }
            }
            val configuration = (contributes["configuration"] as? JsonObject)?.let { fields ->
                require(fields.size <= 64) { "Invalid plugin configuration" }
                fields.map { (key, value) -> parseField(key, value, ownsSecrets) }
            } ?: run {
                require(contributes["configuration"] == null) { "Invalid plugin configuration" }
                emptyList()
            }
            return PlatformManifest(
                id = id,
                name = name,
                version = version,
                main = main,
                homepage = json.text("homepage")?.takeIf { it.startsWith("https://") },
                network = network,
                ownsSecrets = ownsSecrets,
                providers = providers,
                configuration = configuration,
                json = json,
            )
        }

        /** Desktop's `supportsApi`: exact versions, `~` (same minor) and `^` (same major, or same minor below 1.0). */
        fun supportsApi(version: String, range: String): Boolean {
            if (!RANGE.matches(range) || !VERSION.matches(version)) return false
            val prefix = range.first().takeIf { it == '^' || it == '~' }
            val (major, minor, patch) = range.trimStart('^', '~').split('.').map(String::toInt)
            val (a, b, c) = version.split('.').map(String::toInt)
            if (prefix == null) return version == range
            if (a != major || b < minor || (b == minor && c < patch)) return false
            if (prefix == '~') return b == minor
            return if (major == 0) b == minor && (minor != 0 || c == patch) else true
        }

        /** Desktop's `networkOrigin`: HTTPS anywhere, plain HTTP only on loopback, never with credentials. */
        fun networkOrigin(value: String): String? = try {
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            val loopback = host != null && (host in LOOPBACK_HOSTS || "[$host]" in LOOPBACK_HOSTS)
            if (uri.userInfo != null || host == null || (scheme != "https" && !(scheme == "http" && loopback))) {
                null
            } else {
                val defaultPort = if (scheme == "https") 443 else 80
                val port = if (uri.port == -1 || uri.port == defaultPort) "" else ":${uri.port}"
                val shownHost = if (host.contains(':')) "[$host]" else host
                "$scheme://$shownHost$port"
            }
        } catch (_: Exception) {
            null
        }

        private fun parseProvider(item: JsonObject, declared: Set<String>): ProviderContribution {
            val type = item.text("type")
            val id = item.text("id")
            val name = item.text("name")
            require(type != null && id != null && PLUGIN_ID.matches(id) && !name.isNullOrBlank() && type in declared) { "Invalid provider contribution" }
            val languages = item["languages"]?.let { value ->
                val tags = (value as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: "" }
                require(type == TRANSLATION && tags != null && tags.size in 1..32 && tags.all { it.length <= 64 && LANGUAGE_TAG.matches(it) } &&
                    tags.map(String::lowercase).toSet().size == tags.size) { "Invalid provider contribution" }
                tags
            }
            val timeout = item["timeoutSeconds"]?.let { value ->
                val seconds = (value as? JsonPrimitive)?.intOrNull
                require(type == TRANSLATION && seconds != null && seconds in 20..600 && (value as JsonPrimitive).content == seconds.toString()) { "Invalid provider contribution" }
                seconds
            }
            return ProviderContribution(type, id, name, languages, timeout)
        }

        private fun parseField(key: String, value: JsonElement, ownsSecrets: Boolean): ConfigurationField {
            val field = value as? JsonObject
            val title = field?.text("title")
            val type = when (field?.text("type")) {
                "string" -> ConfigurationType.String
                "boolean" -> ConfigurationType.Boolean
                "number" -> ConfigurationType.Number
                else -> null
            }
            val default = field?.get("default") as? JsonPrimitive
            require(PLUGIN_ID.matches(key) && field != null && !title.isNullOrBlank() && type != null && default != null && default.matches(type)) {
                "Invalid plugin configuration field"
            }
            val enum = field["enum"]?.let { values ->
                val items = (values as? JsonArray)?.map { it as? JsonPrimitive }
                require(items != null && items.isNotEmpty() && items.all { it != null && it.matches(type) } && default in items) { "Invalid plugin configuration field" }
                items.filterNotNull()
            }
            val description = field["description"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString && p.content.length <= 1024 }?.content ?: throw IllegalArgumentException("Invalid plugin configuration field") }
            val format = field.text("format")
            if (format != null) {
                require(type == ConfigurationType.String && enum == null) { "Invalid plugin configuration field" }
                when (format) {
                    "secret" -> require(default.content.isEmpty() && ownsSecrets) { "Invalid plugin configuration field" }
                    "endpoint" -> require(default.content.isEmpty() || networkOrigin(default.content) != null) { "Invalid plugin configuration field" }
                    else -> throw IllegalArgumentException("Invalid plugin configuration field")
                }
            }
            return ConfigurationField(key, title, type, default, enum, description, format)
        }

        private fun JsonPrimitive.matches(type: ConfigurationType): Boolean = when (type) {
            ConfigurationType.String -> isString && content.length <= 16_384
            ConfigurationType.Boolean -> !isString && booleanOrNull != null
            ConfigurationType.Number -> !isString && doubleOrNull?.isFinite() == true
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.ranges(key: String): Map<String, String>? {
            val value = this[key] as? JsonObject ?: return null
            return value.mapValues { (_, range) ->
                val text = (range as? JsonPrimitive)?.takeIf { it.isString }?.content
                require(text != null && RANGE.matches(text)) { "Invalid API range: $range" }
                text
            }
        }
    }
}
