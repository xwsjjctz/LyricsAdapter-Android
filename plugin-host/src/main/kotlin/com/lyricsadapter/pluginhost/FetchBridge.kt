package com.lyricsadapter.pluginhost

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** A request as the compat layer's fetch() sends it; headers are `[name, value]` pairs. */
@Serializable
data class FetchRequestMeta(
    val url: String,
    val method: String = "GET",
    val headers: List<List<String>> = emptyList(),
    val redirect: String = "follow",
)

@Serializable
data class FetchResponseMeta(
    val url: String,
    val status: Int,
    val statusText: String,
    val headers: List<List<String>>,
    val redirected: Boolean,
)

class FetchResult(val meta: FetchResponseMeta, val body: ByteArray)

class FetchException(message: String) : Exception(message)

/**
 * Gives plugins the fetch behaviour Node (undici) gives them on desktop:
 * Node's default headers, the three redirect modes, method rewriting on
 * 301/302/303, and dropping credentials when a redirect changes origin.
 */
class FetchBridge(private val transport: HttpTransport) {

    suspend fun fetch(meta: FetchRequestMeta, body: ByteArray?): FetchResult {
        val mode = RedirectMode.parse(meta.redirect)
        if (body != null && (meta.method == "GET" || meta.method == "HEAD")) {
            throw FetchException("Request with GET/HEAD method cannot have body.")
        }
        var url = meta.url.toHttpUrlOrNull() ?: throw FetchException("Unsupported URL: ${meta.url}")
        var method = meta.method
        var headers = withDefaults(meta.headers.map(::toHeader))
        var requestBody = body
        var redirected = false

        repeat(MAX_REDIRECTS + 1) {
            val response = transport.execute(HttpRequest(url.toString(), method, headers, requestBody))
            val location = response.header("location")
            if (response.status !in REDIRECT_STATUSES || location == null || mode == RedirectMode.MANUAL) {
                return FetchResult(response.toMeta(redirected), response.body)
            }
            if (mode == RedirectMode.ERROR) throw FetchException("unexpected redirect")

            val next = url.resolve(location) ?: throw FetchException("Invalid redirect location: $location")
            if (rewritesToGet(response.status, method)) {
                method = "GET"
                requestBody = null
                headers = headers.filterNot { it.first.lowercase() in BODY_HEADERS }
            }
            if (!next.sameOrigin(url)) {
                headers = headers.filterNot { it.first.lowercase() in CROSS_ORIGIN_DROPPED_HEADERS }
            }
            url = next
            redirected = true
        }
        throw FetchException("redirect count exceeded")
    }

    private fun toHeader(pair: List<String>): Pair<String, String> {
        if (pair.size != 2) throw FetchException("Invalid header entry")
        return pair[0] to pair[1]
    }

    /** Adds Node's default headers and leaves connection-level ones to the transport. */
    private fun withDefaults(headers: List<Pair<String, String>>): List<Pair<String, String>> {
        val present = headers.mapTo(mutableSetOf()) { it.first.lowercase() }
        val kept = headers.filterNot { it.first.lowercase() in TRANSPORT_HEADERS }
        return kept + DEFAULT_HEADERS.filterNot { it.first in present }
    }

    private fun rewritesToGet(status: Int, method: String): Boolean =
        (status == 303 && method != "GET" && method != "HEAD") || ((status == 301 || status == 302) && method == "POST")

    private fun HttpUrl.sameOrigin(other: HttpUrl): Boolean =
        scheme == other.scheme && host == other.host && port == other.port

    private fun HttpResponse.toMeta(redirected: Boolean) = FetchResponseMeta(
        url = url,
        status = status,
        statusText = statusText,
        headers = headers.map { (name, value) -> listOf(name.lowercase(), value) },
        redirected = redirected,
    )

    private enum class RedirectMode {
        FOLLOW, MANUAL, ERROR;

        companion object {
            fun parse(value: String): RedirectMode =
                entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: throw FetchException("Invalid redirect mode: $value")
        }
    }

    private companion object {
        const val MAX_REDIRECTS = 20
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)
        val DEFAULT_HEADERS = listOf(
            "accept" to "*/*",
            "accept-language" to "*",
            "sec-fetch-mode" to "cors",
            "user-agent" to "node",
        )
        val TRANSPORT_HEADERS = setOf("accept-encoding", "connection", "content-length", "host", "keep-alive", "te", "trailer", "transfer-encoding", "upgrade")
        val BODY_HEADERS = setOf("content-type", "content-length", "content-encoding", "content-language", "content-location")
        val CROSS_ORIGIN_DROPPED_HEADERS = setOf("authorization", "cookie", "proxy-authorization")
    }
}
