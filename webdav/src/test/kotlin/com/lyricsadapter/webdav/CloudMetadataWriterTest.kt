package com.lyricsadapter.webdav

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudMetadataWriterTest {
    /** A WebDAV server in memory: GET, PUT and MKCOL; PUT into a missing folder answers 409, as 123pan does. */
    private class Dav : Dispatcher() {
        val files = ConcurrentHashMap<String, String>()
        val folders = ConcurrentHashMap.newKeySet<String>()
        val log = CopyOnWriteArrayList<String>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.url.encodedPath.removePrefix("/dav").trimEnd('/')
            log += "${request.method} $path"
            return when (request.method) {
                "GET" -> files[path]?.let { MockResponse.Builder().body(it).build() } ?: MockResponse.Builder().code(404).build()
                "MKCOL" -> if (folders.add(path)) MockResponse.Builder().code(201).build() else MockResponse.Builder().code(405).build()
                "PUT" -> if (path.substringBeforeLast('/') in folders) {
                    files[path] = request.body!!.utf8()
                    MockResponse.Builder().code(201).build()
                } else {
                    MockResponse.Builder().code(409).build()
                }
                else -> MockResponse.Builder().code(405).build()
            }
        }
    }

    private val dav = Dav()
    private val server = MockWebServer().apply { dispatcher = dav }
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private fun writer() = CloudMetadataWriter(WebDavClient(WebDavConfig(server.url("/dav/").toString(), "me", "secret"))) { Instant.parse("2026-10-25T00:00:00Z") }

    private fun song(path: String, cover: String? = "data:image/jpeg;base64,AAAA", lyrics: String? = "[00:01.00]hi") = LocalMetadata(
        path = path,
        title = path.substringAfterLast('/').substringBefore('.'),
        artist = "A",
        album = "",
        duration = 200.0,
        fileSize = 1000,
        lastModified = "Sat, 24 Oct 2026 10:00:00 GMT",
        coverDataUrl = cover,
        lyrics = lyrics,
        syncedLyrics = listOf(SyncedLine(1.0, "hi")),
    )

    private fun manifest() = json.decodeFromString<Manifest>(dav.files.getValue("/Metadata/_manifest.json"))

    private fun chunk(id: String) = json.decodeFromString<Chunk>(dav.files.getValue("/Metadata/_chunk_$id.json"))

    @Test
    fun `writes chunks before the manifest, making the folder first`() = runTest {
        assertTrue(writer().upload(listOf(song("/a.flac"), song("/b.flac")), setOf("/a.flac", "/b.flac")))

        val writes = dav.log.filter { !it.startsWith("GET") }
        assertEquals(listOf("MKCOL /Metadata", "PUT /Metadata/_chunk_0001.json", "PUT /Metadata/_manifest.json"), writes)
        val manifest = manifest()
        assertEquals(3, manifest.version)
        assertEquals("2026-10-25T00:00:00Z", manifest.generatedAt)
        assertEquals("Unknown Album", manifest.entries.getValue("/a.flac").album)
        assertTrue(manifest.entries.getValue("/a.flac").let { it.hasCover && it.hasLyrics && it.hasSyncedLyrics })
        assertEquals("data:image/jpeg;base64,AAAA", chunk("0001").entries.getValue("/b.flac").coverUrl)
    }

    @Test
    fun `keeps server entries, drops unlisted files and keeps a stored cover`() = runTest {
        writer().upload(listOf(song("/a.flac"), song("/b.flac"), song("/c.flac")), setOf("/a.flac", "/b.flac", "/c.flac"))

        // /c.flac is gone from the listing; /a.flac is read again without a cover.
        writer().upload(listOf(song("/a.flac", cover = null, lyrics = "new")), setOf("/a.flac", "/b.flac"))

        val manifest = manifest()
        assertEquals(setOf("/a.flac", "/b.flac"), manifest.entries.keys)
        assertTrue(manifest.entries.getValue("/a.flac").hasCover, "the server's cover flag survives")
        val chunk = chunk("0001")
        assertEquals("new", chunk.entries.getValue("/a.flac").lyrics)
        assertEquals("data:image/jpeg;base64,AAAA", chunk.entries.getValue("/a.flac").coverUrl)
        assertNull(chunk.entries["/c.flac"])
    }

    @Test
    fun `writes nothing when nothing changed`() = runTest {
        writer().upload(listOf(song("/a.flac")), setOf("/a.flac"))
        dav.log.clear()

        assertFalse(writer().upload(emptyList(), setOf("/a.flac")))
        assertTrue(dav.log.none { it.startsWith("PUT") })
    }

    @Test
    fun `opens a new chunk when the newest is full`() {
        val full = (1..50).associate { "/$it.mp3" to ManifestEntry(chunkId = "0001") }
        assertEquals("0002", CloudMetadataWriter.assignChunkId(full, 50))
        assertEquals("0001", CloudMetadataWriter.assignChunkId(full - "/1.mp3", 50))
        assertEquals("0001", CloudMetadataWriter.assignChunkId(emptyMap(), 50))
    }
}
