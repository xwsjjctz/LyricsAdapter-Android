package com.lyricsadapter.pluginhost

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import okio.DeflaterSink
import okio.GzipSink
import okio.buffer
import java.util.zip.Deflater
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class OkHttpTransportTest {
    private val server = MockWebServer()
    private val transport = OkHttpTransport()

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private fun get(path: String = "/") = HttpRequest(server.url(path).toString(), "GET", emptyList(), null)

    private fun gzip(text: String): Buffer {
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { it.writeUtf8(text) }
        return compressed
    }

    @Test
    fun `an empty redirect marked as gzip comes back empty`() = runTest {
        // QQ's OAuth authorize answers a signed-in POST like this.
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/next").addHeader("Content-Encoding", "gzip").build())

        val response = transport.execute(HttpRequest(server.url("/authorize").toString(), "POST", emptyList(), "a=1".encodeToByteArray()))

        assertEquals(302, response.status)
        assertEquals("/next", response.header("location"))
        assertContentEquals(ByteArray(0), response.body)
    }

    @Test
    fun `gzip bodies are decoded`() = runTest {
        server.enqueue(MockResponse.Builder().addHeader("Content-Encoding", "gzip").body(gzip("hello")).build())

        assertEquals("hello", transport.execute(get()).body.decodeToString())
    }

    @Test
    fun `deflate bodies are decoded with or without the zlib wrapper`() = runTest {
        for (raw in listOf(false, true)) {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, raw)
            val compressed = Buffer()
            DeflaterSink(compressed, deflater).buffer().use { it.writeUtf8("hello") }
            server.enqueue(MockResponse.Builder().addHeader("Content-Encoding", "deflate").body(compressed).build())

            assertEquals("hello", transport.execute(get()).body.decodeToString())
        }
    }

    @Test
    fun `asks for gzip and deflate as Node does`() = runTest {
        server.enqueue(MockResponse.Builder().body("ok").build())

        transport.execute(get())

        assertEquals("gzip, deflate", server.takeRequest().headers["Accept-Encoding"])
    }
}
