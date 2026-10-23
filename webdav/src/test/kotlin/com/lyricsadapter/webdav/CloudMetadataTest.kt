package com.lyricsadapter.webdav

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudMetadataTest {
    private val server = MockWebServer()

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private fun reader() = CloudMetadataReader(WebDavClient(WebDavConfig(server.url("/dav/").toString(), "me", "secret")))

    @Test
    fun `reads a v3 manifest as desktop writes it`() = runTest {
        server.enqueue(MockResponse.Builder().body(MANIFEST).build())

        val manifest = reader().manifest()!!

        assertEquals("/dav/Metadata/_manifest.json", server.takeRequest().url.encodedPath)
        val entry = manifest.entries.getValue("/晴天.flac")
        assertEquals("晴天", entry.title)
        assertEquals("0001", entry.chunkId)
        assertTrue(entry.matches(WebDavFile("晴天.flac", "/晴天.flac", 1234, "Mon, 01 Jan 2024 00:00:00 GMT", isDirectory = false)))
        assertTrue(!entry.matches(WebDavFile("晴天.flac", "/晴天.flac", 999, "Mon, 01 Jan 2024 00:00:00 GMT", isDirectory = false)))
    }

    @Test
    fun `ignores older manifest versions and missing files`() = runTest {
        server.enqueue(MockResponse.Builder().body("""{"version":2,"entries":{}}""").build())
        server.enqueue(MockResponse.Builder().code(404).build())

        assertNull(reader().manifest())
        assertNull(reader().manifest())
    }

    @Test
    fun `reads a chunk with cover and timed lyrics`() = runTest {
        server.enqueue(MockResponse.Builder().body(CHUNK).build())

        val entry = reader().chunk("0001")!!.entries.getValue("/晴天.flac")

        assertEquals("/dav/Metadata/_chunk_0001.json", server.takeRequest().url.encodedPath)
        assertEquals("[00:01.50]故事的小黄花\n[01:02.05]从出生那年就飘着", entry.syncedLyrics!!.toLrc())
        val cover = decodeDataUrl(entry.coverUrl!!)!!
        assertEquals("image/png", cover.mimeType)
        assertContentEquals(byteArrayOf(1, 2, 3), cover.data)
    }

    @Test
    fun `leaves machine-local cover pointers alone`() {
        assertNull(decodeDataUrl("cover://abc.jpg"))
    }

    private companion object {
        const val MANIFEST = """{"version":3,"generatedAt":"2026-10-01T00:00:00Z","chunkSize":50,"entries":{
            "/晴天.flac":{"title":"晴天","artist":"周杰伦","album":"叶惠美","duration":269.5,"fileSize":1234,
            "fileName":"晴天.flac","lastModified":"Mon, 01 Jan 2024 00:00:00 GMT","chunkId":"0001",
            "hasCover":true,"hasLyrics":true,"hasSyncedLyrics":true}}}"""
        const val CHUNK = """{"chunkId":"0001","entries":{"/晴天.flac":{"coverUrl":"data:image/png;base64,AQID",
            "lyrics":"故事的小黄花","syncedLyrics":[{"time":1.5,"text":"故事的小黄花"},{"time":62.05,"text":"从出生那年就飘着"}]}}}"""
    }
}
