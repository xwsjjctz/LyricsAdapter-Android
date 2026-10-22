package com.lyricsadapter.pluginhost

/** One HTTP exchange as the platform performs it. */
class HttpRequest(
    val url: String,
    val method: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray?,
)

class HttpResponse(
    val url: String,
    val status: Int,
    val statusText: String,
    val headers: List<Pair<String, String>>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
}

/**
 * The platform HTTP client behind plugin fetch. Implementations return
 * redirects instead of following them and keep no cookies, so [FetchBridge]
 * can apply the fetch semantics plugins were written against.
 */
fun interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}
