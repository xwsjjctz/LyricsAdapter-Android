package com.lyricsadapter.pluginhost

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.GzipSource
import okio.InflaterSource
import okio.Source
import okio.buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.Inflater
import kotlin.coroutines.resumeWithException

class OkHttpTransport(
    private val client: OkHttpClient = defaultClient(),
    private val maxResponseBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
) : HttpTransport {

    override suspend fun execute(request: HttpRequest): HttpResponse {
        val call = client.newCall(request.toOkHttpRequest())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(runCatching { response.use { it.toHttpResponse() } })
                }
            })
        }
    }

    private fun HttpRequest.toOkHttpRequest(): Request {
        val headerList = Headers.Builder()
        headers.forEach { (name, value) -> headerList.addUnsafeNonAscii(name, value) }
        // Asking ourselves turns off OkHttp's transparent gzip, which throws on
        // an empty body marked as gzip (QQ's OAuth redirect); [decoded] copes.
        headerList.set("Accept-Encoding", ACCEPT_ENCODING)
        val requestBody = body?.toRequestBody() ?: ByteArray(0).toRequestBody().takeIf { method in METHODS_WITH_BODY }
        return Request.Builder()
            .url(url)
            .headers(headerList.build())
            .method(method, requestBody)
            .build()
    }

    private fun Response.toHttpResponse(): HttpResponse = HttpResponse(
        url = request.url.toString(),
        status = code,
        statusText = message,
        headers = headers.toList(),
        body = decoded(readLimited(body.source()), header("Content-Encoding")),
    )

    private fun readLimited(source: Source): ByteArray {
        val buffer = source.buffer()
        if (buffer.request(maxResponseBytes + 1)) throw IOException("Response body exceeds $maxResponseBytes bytes")
        return buffer.readByteArray()
    }

    /** Undoes gzip or deflate as Node's fetch does; an empty body stays empty, unknown codings stay as sent. */
    private fun decoded(body: ByteArray, contentEncoding: String?): ByteArray {
        if (body.isEmpty() || contentEncoding == null) return body
        return contentEncoding.split(',').map { it.trim().lowercase() }.filter(String::isNotEmpty).asReversed()
            .fold(body) { data, coding ->
                when (coding) {
                    "gzip", "x-gzip" -> readLimited(GzipSource(Buffer().write(data)))
                    // Servers send zlib-wrapped deflate, a few send it raw; Node accepts both.
                    "deflate" -> readLimited(InflaterSource(Buffer().write(data), Inflater(!isZlib(data))))
                    else -> return data
                }
            }
    }

    /** A zlib header: deflate method in the low nibble, and the two bytes a multiple of 31. */
    private fun isZlib(data: ByteArray): Boolean =
        data.size >= 2 && data[0].toInt() and 0x0f == 8 && ((data[0].toInt() and 0xff) * 256 + (data[1].toInt() and 0xff)) % 31 == 0

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 32L * 1024 * 1024
        private const val ACCEPT_ENCODING = "gzip, deflate"
        private val METHODS_WITH_BODY = setOf("POST", "PUT", "PATCH", "PROPPATCH", "REPORT")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
