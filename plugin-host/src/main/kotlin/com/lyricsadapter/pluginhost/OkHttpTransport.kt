package com.lyricsadapter.pluginhost

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
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
        val requestBody = body?.toRequestBody() ?: ByteArray(0).toRequestBody().takeIf { method in METHODS_WITH_BODY }
        return Request.Builder()
            .url(url)
            .headers(headerList.build())
            .method(method, requestBody)
            .build()
    }

    private fun Response.toHttpResponse(): HttpResponse {
        val source = body.source()
        if (source.request(maxResponseBytes + 1)) throw IOException("Response body exceeds $maxResponseBytes bytes")
        return HttpResponse(
            url = request.url.toString(),
            status = code,
            statusText = message,
            headers = headers.toList(),
            body = source.readByteArray(),
        )
    }

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES: Long = 32L * 1024 * 1024
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
