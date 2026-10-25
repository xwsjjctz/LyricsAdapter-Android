package com.lyricsadapter.app.plugins

import android.content.Context
import android.util.Log
import com.lyricsadapter.app.data.SecretStore
import com.lyricsadapter.pluginhost.LegacyMusicPlugin
import com.lyricsadapter.pluginhost.MusicSourcePlugin
import com.lyricsadapter.pluginhost.PlatformManifest
import com.lyricsadapter.pluginhost.PlatformPlugin
import com.lyricsadapter.pluginhost.PlatformPluginSettings
import com.lyricsadapter.pluginhost.PluginCache
import com.lyricsadapter.pluginhost.PluginDataStore
import com.lyricsadapter.pluginhost.PluginEnvironment
import com.lyricsadapter.pluginhost.PluginLogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

/** Desktop's `MusicPluginManifest` for legacy (`apiVersion: 1`) music sources. */
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val main: String,
    val requiresCookie: Boolean,
    val capabilities: List<String>,
    val homepage: String?,
    /** Set for plugins on desktop's plugin platform (`manifestVersion`), such as lyric translation. */
    val platform: PlatformManifest? = null,
)

/** An installed plugin as the settings page shows it. */
data class InstalledPlugin(val manifest: PluginManifest, val enabled: Boolean, val signedIn: Boolean) {
    val id: String get() = manifest.id
}

/** A package or manifest that cannot be installed, with a message for people. */
class PluginPackageException(message: String) : Exception(message)

private val PLUGIN_ID = Regex("^[a-z][a-z0-9-]{0,63}$")
private val VERSION = Regex("^\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?$")
private val MAIN = Regex("^[\\w.-]+\\.cjs$")
private const val MAX_PACKAGE_BYTES = 24 * 1024 * 1024
private const val MAX_CODE_BYTES = 20 * 1024 * 1024
private const val MUSIC_PLUGIN_API_VERSION = 1

/**
 * Installed music-source plugins, following desktop's `MusicPluginRegistry`:
 * a `.laplugin` package is JSON with `manifest`, `code` and the code's
 * SHA-256, unpacked into `plugins/<id>/`. Each enabled plugin runs in its own
 * QuickJS runtime, started the first time it is used.
 */
class PluginRegistry(
    context: Context,
    private val directory: File,
    private val store: AndroidPluginStore,
    /** Platform plugins' caches, one folder per plugin; removed with the plugin. */
    private val dataDirectory: File,
) {
    private val prefs = context.getSharedPreferences("plugins", Context.MODE_PRIVATE)
    private val environment by lazy {
        PluginEnvironment(
            logger = { id, level, message ->
                val tag = "Plugin:$id"
                when (level) {
                    PluginLogLevel.ERROR -> Log.e(tag, message)
                    PluginLogLevel.WARN -> Log.w(tag, message)
                    PluginLogLevel.INFO -> Log.i(tag, message)
                    PluginLogLevel.DEBUG -> Log.d(tag, message)
                }
            },
            store = store,
        )
    }
    private val runtimes = HashMap<String, LegacyMusicPlugin>()
    private val translators = HashMap<String, PlatformPlugin>()
    private val lock = Mutex()
    private val state = MutableStateFlow(scan())

    val plugins: StateFlow<List<InstalledPlugin>> = state.asStateFlow()

    /** Enabled plugins that provide music. */
    fun sources(): List<InstalledPlugin> = state.value.filter { it.enabled && (it.manifest.platform == null || it.manifest.platform.musicSource != null) }

    /** Enabled plugins that translate lyrics. */
    fun translationPlugins(): List<InstalledPlugin> = state.value.filter { it.enabled && it.manifest.platform?.translators?.isNotEmpty() == true }

    fun cookie(id: String): String = store.readSecret(id, "cookie")

    /** Stores a login: the cookie the plugin reads, plus anything else it keeps, such as QQ's credential. */
    fun saveLogin(id: String, cookie: String, extra: Map<String, String> = emptyMap()) {
        store.writeSecrets(id, mapOf("cookie" to cookie) + extra)
    }

    /** Forgets the login; the plugin's other settings stay, as desktop keeps them. */
    fun logout(id: String) {
        store.writeSecrets(id, mapOf("cookie" to "", "credential" to ""))
    }

    /** Validates and installs a package; an installed version of the same plugin is replaced. */
    suspend fun installPackage(bytes: ByteArray, expectedId: String? = null, expectedVersion: String? = null): PluginManifest {
        if (bytes.size > MAX_PACKAGE_BYTES) throw PluginPackageException("插件包超过 24 MiB")
        val value = try {
            Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        } catch (_: IllegalArgumentException) {
            throw PluginPackageException("不是有效的插件包")
        }
        val manifestJson = value["manifest"] as? JsonObject ?: throw PluginPackageException("不是有效的插件包")
        val manifest = parseManifest(manifestJson)
        if (expectedId != null && manifest.id != expectedId) throw PluginPackageException("下载的插件与目录不符")
        if (expectedVersion != null && manifest.version != expectedVersion) throw PluginPackageException("下载的插件版本与目录不符")
        val code = (value["code"] as? JsonPrimitive)?.contentOrNull ?: throw PluginPackageException("插件代码无效")
        val codeBytes = code.toByteArray(Charsets.UTF_8)
        if (codeBytes.size > MAX_CODE_BYTES) throw PluginPackageException("插件代码无效")
        if ((value["sha256"] as? JsonPrimitive)?.contentOrNull != sha256(codeBytes)) throw PluginPackageException("插件校验失败")

        lock.withLock {
            runtimes.remove(manifest.id)?.close()
            translators.remove(manifest.id)?.close()
            directory.mkdirs()
            val staging = File(directory, ".package-${manifest.id}")
            staging.deleteRecursively()
            staging.mkdirs()
            File(staging, "manifest.json").writeText(manifestJson.toString())
            File(staging, manifest.main).writeBytes(codeBytes)
            val target = File(directory, manifest.id)
            target.deleteRecursively()
            if (!staging.renameTo(target)) throw PluginPackageException("无法保存插件")
        }
        refresh()
        return manifest
    }

    suspend fun uninstall(id: String) {
        lock.withLock {
            runtimes.remove(id)?.close()
            translators.remove(id)?.close()
            File(directory, id).deleteRecursively()
            File(dataDirectory, id).deleteRecursively()
        }
        refresh()
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean("music-plugin:$id:enabled", enabled).apply()
        if (!enabled) lock.withLock {
            runtimes.remove(id)?.close()
            translators.remove(id)?.close()
        }
        refresh()
    }

    /** The running music source, started on first use, whichever plugin API it was written for. */
    suspend fun runtime(id: String): MusicSourcePlugin {
        val plugin = state.value.firstOrNull { it.id == id && it.enabled } ?: throw PluginPackageException("插件未安装或已禁用：$id")
        plugin.manifest.platform?.let { platform ->
            if (platform.musicSource == null) throw PluginPackageException("这个插件不提供音乐源：$id")
            return platformRuntime(id)
        }
        return lock.withLock {
            runtimes[id] ?: LegacyMusicPlugin.start(id, File(File(directory, id), plugin.manifest.main).readText(), environment).also { runtimes[id] = it }
        }
    }

    /** The running platform plugin, started and activated on first use. */
    suspend fun platformRuntime(id: String): PlatformPlugin = lock.withLock {
        translators[id]?.let { return it }
        val plugin = state.value.firstOrNull { it.id == id && it.enabled } ?: throw PluginPackageException("插件未安装或已禁用：$id")
        val manifest = plugin.manifest.platform ?: throw PluginPackageException("不是插件平台的插件：$id")
        val code = File(File(directory, id), manifest.main).readText()
        PlatformPlugin.start(manifest, code, environment, File(dataDirectory, id)).also { translators[id] = it }
    }

    /** A platform plugin's settings, readable and writable whether or not it is running. */
    fun platformSettings(id: String): PlatformPluginSettings? =
        translators[id]?.settings ?: state.value.firstOrNull { it.id == id }?.manifest?.platform?.let { PlatformPluginSettings(it, store) }

    /** Saves one configuration value and tells a running plugin, as desktop's `notifyConfigurationChanged`. */
    suspend fun setConfiguration(id: String, key: String, value: JsonPrimitive) {
        val settings = platformSettings(id) ?: throw PluginPackageException("插件未安装：$id")
        try {
            settings.set(key, value)
        } catch (error: IllegalArgumentException) {
            throw PluginPackageException(error.message ?: "配置无效")
        }
        translators[id]?.runCatching { configurationChanged() }
    }

    /** The plugin's cache size, and clearing it, for the settings page. */
    fun cacheUsage(id: String): Pair<Int, Long> = PluginCache(File(dataDirectory, id)).usage()

    fun clearCache(id: String) = PluginCache(File(dataDirectory, id)).clear()

    /** Re-reads the installed plugins, for example after a login changed what is signed in. */
    fun refresh() {
        state.value = scan()
    }

    private fun scan(): List<InstalledPlugin> =
        directory.listFiles().orEmpty()
            .filter { it.isDirectory && PLUGIN_ID.matches(it.name) }
            .mapNotNull { folder ->
                try {
                    val manifest = parseManifest(Json.parseToJsonElement(File(folder, "manifest.json").readText()).jsonObject)
                    InstalledPlugin(
                        manifest = manifest,
                        enabled = prefs.getBoolean("music-plugin:${manifest.id}:enabled", true),
                        signedIn = store.readSecret(manifest.id, "cookie").isNotEmpty(),
                    )
                } catch (error: Exception) {
                    Log.w(TAG, "Skipping unreadable plugin ${folder.name}", error)
                    null
                }
            }
            .sortedWith(compareBy({ OFFICIAL_ORDER.indexOf(it.id).let { index -> if (index < 0) Int.MAX_VALUE else index } }, { it.manifest.name }))

    /** Desktop's `validateMusicPluginManifest`; plugins on the newer platform API are refused for now. */
    private fun parseManifest(json: JsonObject): PluginManifest {
        fun text(key: String) = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val id = text("id")
        val name = text("name")
        val version = text("version")
        val main = text("main")
        if (id == null || !PLUGIN_ID.matches(id) || name.isNullOrBlank() || version == null || !VERSION.matches(version)) {
            throw PluginPackageException("插件清单无效")
        }
        if (main == null || !MAIN.matches(main)) throw PluginPackageException("插件入口必须是本地 .cjs 文件")
        if (json.containsKey("manifestVersion")) {
            val platform = try {
                PlatformManifest.parse(json)
            } catch (error: IllegalArgumentException) {
                throw PluginPackageException(error.message ?: "插件清单无效")
            }
            // Desktop's `validatePlatformManifest` summary: platform sources never require a cookie up front.
            val capabilities = if (platform.musicSource != null) listOf("search", "stream", "lyrics", "playlists") else emptyList()
            return PluginManifest(platform.id, platform.name, platform.version, platform.main, false, capabilities, platform.homepage, platform)
        }
        val requiresCookie = (json["requiresCookie"] as? JsonPrimitive)?.booleanOrNull ?: throw PluginPackageException("插件清单无效")
        val capabilities = (json["capabilities"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: throw PluginPackageException("插件清单无效")
        val apiVersion = (json["apiVersion"] as? JsonPrimitive)?.intOrNull
        if (apiVersion != MUSIC_PLUGIN_API_VERSION) throw PluginPackageException("不支持的插件 API 版本：$apiVersion")
        return PluginManifest(id, name, version, main, requiresCookie, capabilities, text("homepage")?.takeIf { it.startsWith("https://") })
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "PluginRegistry"
        val OFFICIAL_ORDER = listOf("qq", "netease")
    }
}

/**
 * The plugin host's storage on Android. Secrets use desktop's key names
 * (`music-plugin:<id>:secret:<name>`) inside the encrypted [SecretStore];
 * settings arrive already prefixed with `plugin:<id>:`.
 */
class AndroidPluginStore(context: Context, private val secrets: SecretStore) : PluginDataStore {
    private val prefs = context.getSharedPreferences("plugin-settings", Context.MODE_PRIVATE)

    /** Told when a plugin's secrets change, such as a refreshed login. */
    var onSecretsChanged: (pluginId: String) -> Unit = {}

    override fun readSecret(pluginId: String, name: String): String = secrets.get(key(pluginId, name)).orEmpty()

    override fun writeSecrets(pluginId: String, entries: Map<String, String>) {
        entries.forEach { (name, value) -> secrets.put(key(pluginId, name), value.ifEmpty { null }) }
        onSecretsChanged(pluginId)
    }

    override fun readSetting(key: String): String? = prefs.getString(key, null)

    override fun writeSetting(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }

    private fun key(pluginId: String, name: String) = "music-plugin:$pluginId:secret:$name"
}
