package com.lyricsadapter.update

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URISyntaxException

/** The answer was not a list of releases: a server error, a rate limit, or something else in its place. */
class UpdateFeedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The APK attached to a release. [sha256] is in lower-case hex, when the server states one. */
data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)

/** A published version of the app that can be installed. */
data class AppRelease(val version: AppVersion, val tag: String, val preRelease: Boolean, val apk: ReleaseAsset, val page: String?)

/**
 * The app's releases, as GitHub lists them (`GET /repos/{owner}/{repo}/releases`).
 * The list rather than `releases/latest`, which leaves pre-releases out, and
 * every version before 1.0 is published as one.
 */
object ReleaseFeed {
    private const val SHA256 = "sha256:"
    private val HEX_SHA256 = Regex("[0-9a-f]{64}")

    /**
     * The releases in [json] that can be installed: published, tagged with a
     * version, and carrying an APK. A tag whose APK has not been uploaded yet
     * is left out, as desktop treats a release without its update files.
     *
     * @throws UpdateFeedException when [json] is not a list of releases.
     */
    fun parse(json: String): List<AppRelease> {
        val root = try {
            Json.parseToJsonElement(json)
        } catch (error: SerializationException) {
            throw UpdateFeedException("The release list is not JSON", error)
        }
        if (root !is JsonArray) throw UpdateFeedException("The release list is not a list")
        return root.mapNotNull { item -> release(item as? JsonObject ?: throw UpdateFeedException("The release list holds something that is not a release")) }
    }

    /**
     * The release to move an app of version [installed] to: the newest one
     * above it, among the full releases only unless [installed] follows
     * pre-releases. Null when there is none.
     */
    fun update(releases: List<AppRelease>, installed: AppVersion): AppRelease? = releases
        .filter { installed.takesPreReleases || !(it.preRelease || it.version.preRelease != null) }
        .filter { it.version > installed }
        .maxByOrNull { it.version }

    /**
     * Whether an APK at [downloadUrl] may be fetched for a feed at [feedUrl]:
     * over https, or from the feed's own server when the feed itself is
     * plain http (a test feed on the local network).
     */
    fun trusts(feedUrl: String, downloadUrl: String): Boolean {
        val download = uri(downloadUrl) ?: return false
        if (download.host.isNullOrEmpty()) return false
        if (download.scheme.equals("https", ignoreCase = true)) return true
        val feed = uri(feedUrl) ?: return false
        return download.scheme.equals("http", ignoreCase = true) &&
            feed.scheme.equals("http", ignoreCase = true) &&
            download.host.equals(feed.host, ignoreCase = true) &&
            download.port == feed.port
    }

    private fun release(value: JsonObject): AppRelease? {
        if (value.boolean("draft") == true) return null
        val tag = value.text("tag_name") ?: return null
        val version = AppVersion.parse(tag) ?: return null
        val apk = (value["assets"] as? JsonArray).orEmpty().firstNotNullOfOrNull(::apk) ?: return null
        return AppRelease(version, tag, value.boolean("prerelease") == true, apk, value.text("html_url"))
    }

    private fun apk(value: JsonElement): ReleaseAsset? {
        val asset = value as? JsonObject ?: return null
        val name = asset.text("name")?.takeIf { it.endsWith(".apk", ignoreCase = true) } ?: return null
        val url = asset.text("browser_download_url")?.takeIf(String::isNotBlank) ?: return null
        val size = (asset["size"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: return null
        val digest = asset.text("digest")?.lowercase()?.takeIf { it.startsWith(SHA256) }?.removePrefix(SHA256)?.takeIf(HEX_SHA256::matches)
        return ReleaseAsset(name, url, size, digest)
    }

    private fun uri(text: String): URI? = try {
        URI(text)
    } catch (_: URISyntaxException) {
        null
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
