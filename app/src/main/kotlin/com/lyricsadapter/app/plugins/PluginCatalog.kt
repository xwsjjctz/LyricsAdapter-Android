package com.lyricsadapter.app.plugins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** One official plugin offered for download. */
data class CatalogEntry(
    val id: String,
    val name: String,
    /** `music.source` or `lyrics.translation`. */
    val kind: String,
    val version: String,
    val url: String,
    val sha256: String,
    val description: String?,
) {
    val isMusicSource: Boolean get() = kind == "music.source"

    /** Legacy music sources and lyric translation plugins run on Android. */
    val isSupported: Boolean get() = isMusicSource || kind == "lyrics.translation"
}

private const val OWNER = "xwsjjctz"
private const val PACKAGES = "https://raw.githubusercontent.com/$OWNER/LyricsAdapter-Music-Plugins/main/packages/"
private const val MAX_CATALOG_BYTES = 512L * 1024
private const val MAX_PACKAGE_BYTES = 24L * 1024 * 1024

/**
 * The official plugin catalog, as desktop's `MusicPluginCatalog` reads it
 * (catalog v2): only the official account's packages are offered, and every
 * download must match the SHA-256 the catalog pins.
 */
class PluginCatalog(private val registry: PluginRegistry, http: OkHttpClient = OkHttpClient()) {
    private val http = http.newBuilder().callTimeout(60, TimeUnit.SECONDS).build()

    suspend fun entries(): List<CatalogEntry> = withContext(Dispatchers.IO) {
        val value = Json.parseToJsonElement(get("${PACKAGES}catalog.v2.json", MAX_CATALOG_BYTES).decodeToString()).jsonObject
        if ((value["apiVersion"] as? JsonPrimitive)?.intOrNull != 2) throw IOException("插件目录格式无效")
        value.getValue("plugins").jsonArray.map { item ->
            val entry = item.jsonObject
            fun text(key: String) = (entry[key] as? JsonPrimitive)?.contentOrNull
            val id = text("id") ?: throw IOException("插件目录格式无效")
            CatalogEntry(
                id = id,
                name = text("name") ?: id,
                kind = text("kind").orEmpty(),
                version = text("version").orEmpty(),
                url = packageUrl(id, text("file"), text("url")),
                sha256 = text("sha256").orEmpty(),
                description = text("description"),
            )
        }
    }

    /** Downloads, checks and installs one catalog entry. */
    suspend fun install(entry: CatalogEntry): PluginManifest {
        val bytes = withContext(Dispatchers.IO) { get(entry.url, MAX_PACKAGE_BYTES) }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (digest != entry.sha256) throw PluginPackageException("插件下载校验失败")
        return registry.installPackage(bytes, entry.id, entry.version)
    }

    /** A package next to the catalog, or a release asset of a repository the official account owns. */
    private fun packageUrl(id: String, file: String?, url: String?): String {
        if (file != null && url == null && file == "$id.laplugin") return PACKAGES + file
        if (file == null && url != null && url.startsWith("https://github.com/$OWNER/") && url.contains("/releases/download/") && url.endsWith("/$id.laplugin")) return url
        throw IOException("插件目录格式无效")
    }

    private fun get(url: String, limit: Long): ByteArray {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("下载失败：HTTP ${response.code}")
            val body = response.body
            if (body.contentLength() > limit) throw IOException("下载内容过大")
            val bytes = body.source().apply { request(limit + 1) }.buffer.let { buffer ->
                if (buffer.size > limit) throw IOException("下载内容过大")
                buffer.readByteArray()
            }
            return bytes
        }
    }
}
