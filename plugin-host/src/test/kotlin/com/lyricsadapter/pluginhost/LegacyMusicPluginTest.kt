package com.lyricsadapter.pluginhost

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/**
 * Runs test-plugin.cjs in a real QuickJS runtime. Expected crypto and zlib
 * values were produced by Node 24, so these pin the compat layer to Node.
 * Real time (runBlocking) because the runtime's timers and the transport's
 * delay run on other dispatchers.
 */
class LegacyMusicPluginTest {
    private val store = InMemoryPluginDataStore()
    private val sentRequests = ConcurrentLinkedQueue<HttpRequest>()
    private val transport = HttpTransport { request ->
        sentRequests += request
        delay(TRANSPORT_DELAY_MILLIS)
        HttpResponse(
            url = request.url,
            status = 302,
            statusText = "Found",
            headers = listOf("set-cookie" to "a=1; Path=/", "set-cookie" to "b=2", "location" to "/next"),
            body = ByteArray(256) { it.toByte() },
        )
    }
    private val environment = PluginEnvironment(logger = { _, _, _ -> }, store = store, transport = transport)
    private val code = requireNotNull(javaClass.getResource("/test-plugin.cjs")) { "test-plugin.cjs is missing" }.readText()

    private fun withPlugin(block: suspend (LegacyMusicPlugin) -> Unit) = runBlocking {
        LegacyMusicPlugin.start("test-source", code, environment).use { block(it) }
    }

    private fun args(vararg values: String) = JsonArray(values.map(::JsonPrimitive))

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

    @Test
    fun `crypto matches node byte for byte`() = withPlugin { plugin ->
        val result = plugin.invoke("crypto", args()).jsonObject

        assertEquals("5rL/N079bQaMU5YywaS/M5h7e/9i21B7dusYKIxK1jk=", result.string("cbc"))
        assertEquals("55502872bd1c277a0af07bb8c4c53fe7", result.string("ecb"))
        assertEquals("5d41402abc4b2a76b9719d911017c592", result.string("md5"))
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8", result.string("hmac"))
        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(result.string("uuid")))
        assertEquals(24, result.getValue("randomLength").jsonPrimitive.int)
    }

    @Test
    fun `zlib inflates node output and streams through createDeflate`() = withPlugin { plugin ->
        val result = plugin.invoke("zlib", args("eJyLNjCwMjDUMzCIfTZzy9MlK7miRwVGBUYFyBcAAJ3DTnA=")).jsonObject

        val expected = "[00:01.00]晴天\n".repeat(50)
        assertEquals(expected, result.string("text"))
        assertEquals(expected, result.string("streamed"))
    }

    @Test
    fun `fetch keeps node semantics for manual redirects, cookies and binary bodies`() = withPlugin { plugin ->
        val result = plugin.invoke("fetch", args("https://y.qq.com/start")).jsonObject

        assertEquals(302, result.getValue("status").jsonPrimitive.int)
        assertEquals(listOf("a=1; Path=/", "b=2"), result.getValue("cookies").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("/next", result.string("location"))
        assertEquals(ByteArray(256) { it.toByte() }.toList(), Base64.getDecoder().decode(result.string("body")).toList())
        val sent = sentRequests.single()
        assertEquals("uin=1", sent.headers.first { it.first.equals("cookie", ignoreCase = true) }.second)
        assertEquals("node", sent.headers.first { it.first == "user-agent" }.second)
    }

    @Test
    fun `calls are served concurrently`() = withPlugin { plugin ->
        val elapsed = measureTime {
            coroutineScope {
                List(3) { async { plugin.invoke("fetch", args("https://example.com/$it")) } }.awaitAll()
            }
        }
        assertTrue(elapsed < (TRANSPORT_DELAY_MILLIS * 2).milliseconds, "three 300 ms requests took $elapsed")
    }

    @Test
    fun `a cleared timer does not hold the call`() = withPlugin { plugin ->
        val elapsed = measureTime { assertEquals("done", plugin.invoke("timers", args()).jsonPrimitive.content) }
        assertTrue(elapsed < 2_000.milliseconds, "call took $elapsed")
    }

    @Test
    fun `url, text and base64 globals behave like node`() = withPlugin { plugin ->
        val result = plugin.invoke("web", args()).jsonObject

        assertEquals("https://music.163.com/api/search?q=%E6%99%B4", result.string("href"))
        assertEquals("晴", result.string("q"))
        assertEquals("s=%E6%99%B4%E5%A4%A9&type=1", result.string("params"))
        assertEquals(listOf(230, 153, 180), result.getValue("encoded").jsonArray.map { it.jsonPrimitive.int })
        assertEquals("晴", result.string("decoded"))
        assertEquals("b2s=", result.string("base64"))
    }

    @Test
    fun `secrets are scoped to the plugin`() = withPlugin { plugin ->
        assertEquals("uin=1", plugin.invoke("secrets", args("uin=1")).jsonPrimitive.content)
        assertEquals("uin=1", store.readSecret("test-source", "cookie"))
        assertEquals("", store.readSecret("qq", "cookie"))
    }

    @Test
    fun `provider calls follow the desktop whitelist and cover mapping`() = withPlugin { plugin ->
        val song = plugin.call("searchMusic", args("x")).jsonArray.single().jsonObject
        assertEquals("https://img.example.com/1.jpg", song.string("coverUrlFullSize"))
        assertTrue(plugin.call("validateCookie", args("ok")).jsonObject.getValue("valid").jsonPrimitive.boolean)
        assertEquals(mapOf("Referer" to "https://example.com", "Cookie" to "c=1"), plugin.streamHeaders("c=1"))

        val refused = assertFailsWith<PluginCallException> { plugin.call("eval", args()) }
        assertEquals("Unsupported provider method: eval", refused.message)
    }

    @Test
    fun `plugin errors reach the caller with their message`() = withPlugin { plugin ->
        val error = assertFailsWith<PluginCallException> { plugin.invoke("fail", args()) }
        assertEquals("upstream said no", error.message)
    }

    private companion object {
        const val TRANSPORT_DELAY_MILLIS = 300L
    }
}
