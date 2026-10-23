package com.lyricsadapter.webdav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.IOException
import java.io.StringReader
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** Where the WebDAV library lives; the password is an app password, as desktop asks for. */
data class WebDavConfig(val serverUrl: String, val username: String, val password: String) {
    val isComplete: Boolean get() = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

/** One entry of a directory listing; [path] is relative to the server URL. */
data class WebDavFile(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: String,
    val isDirectory: Boolean,
)

/** A request the server refused or could not answer, with a message for people. */
class WebDavException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Desktop's `AUDIO_EXTENSIONS`: only these files are listed. */
private val AUDIO_EXTENSIONS = setOf("flac", "mp3", "m4a", "wav", "ogg", "aac")
private const val TIMEOUT_SECONDS = 20L
private const val DAV = "DAV:"

/**
 * The WebDAV requests the library needs, following desktop's `webdavCore.ts`
 * and `webdavClient.ts`: PROPFIND with Basic authentication to test a
 * connection and list a directory, GET for text files such as `meta.json`.
 */
class WebDavClient(
    private val config: WebDavConfig,
    http: OkHttpClient = OkHttpClient(),
) {
    private val http = http.newBuilder()
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val authorization = Credentials.basic(config.username, config.password, Charsets.UTF_8)

    /** Succeeds when the server answers a Depth 0 PROPFIND of the base URL, as desktop's test does. */
    suspend fun testConnection() {
        propfind(config.serverUrl.toHttpUrl(), depth = "0")
    }

    /** Subdirectories and audio files directly in [directory]. */
    suspend fun list(directory: String = "/"): List<WebDavFile> =
        parseListing(propfind(WebDavPath.url(config.serverUrl, directory, directory = true), depth = "1"), directory)

    /** A text file's content, or null when it does not exist. */
    suspend fun fetchText(path: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(WebDavPath.url(config.serverUrl, path)).header("Authorization", authorization).get().build()
        execute(request) { response ->
            when {
                response.code == 404 -> null
                response.isSuccessful -> response.body.string()
                else -> throw WebDavException("GET failed: ${response.code} ${response.message}")
            }
        }
    }

    /** Bytes [start] to [endInclusive] of a file; servers that ignore ranges send the whole file, which is cut to size. */
    suspend fun fetchRange(path: String, start: Long, endInclusive: Long): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(WebDavPath.url(config.serverUrl, path))
            .header("Authorization", authorization)
            .header("Range", "bytes=$start-$endInclusive")
            .get()
            .build()
        execute(request) { response ->
            if (!response.isSuccessful) throw WebDavException("GET failed: ${response.code} ${response.message}".trim())
            val wanted = (endInclusive - start + 1).toInt()
            response.body.source().use { source ->
                if (response.code != 206 && start > 0) source.skip(start)
                val buffer = okio.Buffer()
                while (buffer.size < wanted && source.read(buffer, (wanted - buffer.size).coerceAtMost(SEGMENT)) != -1L) Unit
                buffer.readByteArray()
            }
        }
    }

    /** The URL to stream [path] from, with the header that authorises it. */
    fun streamUrl(path: String): String = WebDavPath.url(config.serverUrl, path).toString()

    val authorizationHeader: String get() = authorization

    private suspend fun propfind(url: HttpUrl, depth: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", authorization)
            .header("Depth", depth)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
            .build()
        execute(request) { response ->
            if (!response.isSuccessful && response.code != 207) {
                throw WebDavException("PROPFIND failed: ${response.code} ${response.message}".trim())
            }
            response.body.string()
        }
    }

    private fun <T> execute(request: Request, read: (Response) -> T): T = try {
        http.newCall(request).execute().use(read)
    } catch (error: WebDavException) {
        throw error
    } catch (error: IOException) {
        throw WebDavException(error.message ?: error.javaClass.simpleName, error)
    }

    /** Desktop's `parsePropfindResponse`: successful entries only, the directory itself and non-audio files left out. */
    internal fun parseListing(xml: String, requestPath: String): List<WebDavFile> {
        val document = try {
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        } catch (error: Exception) {
            throw WebDavException("Invalid PROPFIND response", error)
        }
        val responses = document.getElementsByTagNameNS(DAV, "response").elements()
        if (responses.isEmpty()) throw WebDavException("Invalid PROPFIND response")
        val normalizedRequest = "/" + requestPath.split('/').filter(String::isNotEmpty).joinToString("/")
        return responses.mapNotNull { response ->
            val href = response.child("href")?.textContent?.trim() ?: return@mapNotNull null
            val path = WebDavPath.fromHref(href, config.serverUrl)
            val name = path.substringAfterLast('/')
            if (name.isEmpty()) return@mapNotNull null
            val propstat = response.getElementsByTagNameNS(DAV, "propstat").elements().firstOrNull { stat ->
                stat.child("status")?.textContent?.let { Regex("\\s2\\d\\d\\s").containsMatchIn(" $it ") } == true
            } ?: return@mapNotNull null
            val isDirectory = propstat.getElementsByTagNameNS(DAV, "collection").length > 0
            if (isDirectory && path == normalizedRequest) return@mapNotNull null
            if (!isDirectory && name.substringAfterLast('.', "").lowercase() !in AUDIO_EXTENSIONS) return@mapNotNull null
            WebDavFile(
                name = name,
                path = path,
                size = propstat.child("getcontentlength")?.textContent?.trim()?.toLongOrNull() ?: 0,
                lastModified = propstat.child("getlastmodified")?.textContent?.trim().orEmpty(),
                isDirectory = isDirectory,
            )
        }
    }

    private fun Element.child(localName: String): Element? =
        getElementsByTagNameNS(DAV, localName).elements().firstOrNull()

    private fun NodeList.elements(): List<Element> = (0 until length).mapNotNull { item(it) as? Element }

    private companion object {
        const val SEGMENT = 64L * 1024
        val XML = "application/xml; charset=utf-8".toMediaType()
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
    }
}
