package com.lyricsadapter.pluginhost

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FetchBridgeTest {
    private class RecordingTransport(private val respond: (HttpRequest) -> HttpResponse) : HttpTransport {
        val requests = mutableListOf<HttpRequest>()

        override suspend fun execute(request: HttpRequest): HttpResponse {
            requests += request
            return respond(request)
        }
    }

    private fun response(url: String, status: Int, vararg headers: Pair<String, String>) =
        HttpResponse(url, status, "", headers.toList(), ByteArray(0))

    private fun HttpRequest.header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    @Test
    fun `manual redirect returns the 3xx response with every set-cookie`() = runTest {
        val transport = RecordingTransport {
            response(it.url, 302, "Set-Cookie" to "a=1", "Set-Cookie" to "b=2", "Location" to "/next")
        }

        val result = FetchBridge(transport).fetch(FetchRequestMeta("https://ptlogin2.qq.com/start", redirect = "manual"), null)

        assertEquals(302, result.meta.status)
        assertEquals(listOf(listOf("set-cookie", "a=1"), listOf("set-cookie", "b=2"), listOf("location", "/next")), result.meta.headers)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `follow rewrites POST to GET on 302 and drops the body`() = runTest {
        val transport = RecordingTransport {
            if (it.url.endsWith("/login")) response(it.url, 302, "Location" to "/done") else response(it.url, 200)
        }
        val meta = FetchRequestMeta(
            url = "https://music.163.com/login",
            method = "POST",
            headers = listOf(listOf("content-type", "application/x-www-form-urlencoded"), listOf("cookie", "os=pc")),
        )

        val result = FetchBridge(transport).fetch(meta, "a=1".toByteArray())

        val second = transport.requests[1]
        assertEquals("GET", second.method)
        assertNull(second.body)
        assertNull(second.header("content-type"))
        assertEquals("os=pc", second.header("cookie"))
        assertEquals("https://music.163.com/done", result.meta.url)
        assertTrue(result.meta.redirected)
    }

    @Test
    fun `cross-origin redirect drops cookie and authorization`() = runTest {
        val transport = RecordingTransport {
            if (it.url.contains("y.qq.com")) response(it.url, 307, "Location" to "https://cdn.example.com/a") else response(it.url, 200)
        }
        val meta = FetchRequestMeta(
            url = "https://y.qq.com/a",
            headers = listOf(listOf("Cookie", "uin=1"), listOf("Authorization", "Bearer x"), listOf("Referer", "https://y.qq.com")),
        )

        FetchBridge(transport).fetch(meta, null)

        val second = transport.requests[1]
        assertNull(second.header("cookie"))
        assertNull(second.header("authorization"))
        assertEquals("https://y.qq.com", second.header("referer"))
    }

    @Test
    fun `node default headers fill in what the plugin left out`() = runTest {
        val transport = RecordingTransport { response(it.url, 200) }
        val meta = FetchRequestMeta(
            url = "https://music.163.com/api",
            headers = listOf(listOf("User-Agent", "Mozilla/5.0"), listOf("Accept-Encoding", "br"), listOf("Host", "evil.example")),
        )

        FetchBridge(transport).fetch(meta, null)

        val request = transport.requests.single()
        assertEquals("Mozilla/5.0", request.header("user-agent"))
        assertEquals("*/*", request.header("accept"))
        assertNull(request.header("accept-encoding"))
        assertNull(request.header("host"))
    }

    @Test
    fun `GET with a body is rejected like undici`() = runTest {
        val bridge = FetchBridge(RecordingTransport { response(it.url, 200) })
        assertFailsWith<FetchException> { bridge.fetch(FetchRequestMeta("https://example.com"), byteArrayOf(1)) }
    }

    @Test
    fun `error mode rejects redirects`() = runTest {
        val bridge = FetchBridge(RecordingTransport { response(it.url, 301, "Location" to "/elsewhere") })
        assertFailsWith<FetchException> { bridge.fetch(FetchRequestMeta("https://example.com", redirect = "error"), null) }
    }
}
