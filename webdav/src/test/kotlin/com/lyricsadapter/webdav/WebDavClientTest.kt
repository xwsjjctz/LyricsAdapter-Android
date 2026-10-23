package com.lyricsadapter.webdav

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Credentials
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WebDavClientTest {
    private val server = MockWebServer()

    @BeforeTest
    fun start() = server.start()

    @AfterTest
    fun stop() = server.close()

    private fun client(): WebDavClient = WebDavClient(WebDavConfig(server.url("/dav/").toString(), "me", "secret"))

    @Test
    fun `tests a connection with an authorised depth 0 PROPFIND of the base URL`() = runTest {
        server.enqueue(MockResponse.Builder().code(207).body(listing()).build())

        client().testConnection()

        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("/dav/", request.url.encodedPath)
        assertEquals("0", request.headers["Depth"])
        assertEquals(Credentials.basic("me", "secret"), request.headers["Authorization"])
    }

    @Test
    fun `reports a refused login`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).build())

        val error = assertFailsWith<WebDavException> { client().testConnection() }
        assertEquals(true, error.message?.startsWith("PROPFIND failed: 401"))
    }

    @Test
    fun `lists subdirectories and audio files relative to the server URL`() = runTest {
        server.enqueue(MockResponse.Builder().code(207).body(listing()).build())

        val files = client().list("/Music")

        assertEquals("/dav/Music/", server.takeRequest().url.encodedPath)
        assertEquals(
            listOf(
                WebDavFile("周杰伦", "/Music/周杰伦", 0, "", isDirectory = true),
                WebDavFile("晴天 (Live).flac", "/Music/晴天 (Live).flac", 1234, "Mon, 01 Jan 2024 00:00:00 GMT", isDirectory = false),
            ),
            files,
        )
    }

    @Test
    fun `builds URLs under the base path without doubling it`() {
        assertEquals("https://host/dav/Music/a%20b.flac", WebDavPath.url("https://host/dav/", "/Music/a b.flac").toString())
        assertEquals("https://host/dav/Music/x.mp3", WebDavPath.url("https://host/dav", "/dav/Music/x.mp3").toString())
        assertEquals("/Music/x.mp3", WebDavPath.fromHref("/dav/Music/x.mp3", "https://host/dav/"))
        assertEquals("/x.mp3", WebDavPath.fromHref("https://host/dav/x.mp3", "https://host/dav"))
    }

    @Test
    fun `returns null for a missing text file`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())

        assertNull(client().fetchText("/Music/a.meta.json"))
    }

    private fun listing() = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/dav/Music/</D:href>
    <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/Music/%E5%91%A8%E6%9D%B0%E4%BC%A6/</D:href>
    <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/Music/%E6%99%B4%E5%A4%A9%20(Live).flac</D:href>
    <D:propstat>
      <D:prop><D:resourcetype/><D:getcontentlength>1234</D:getcontentlength><D:getlastmodified>Mon, 01 Jan 2024 00:00:00 GMT</D:getlastmodified></D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/Music/cover.jpg</D:href>
    <D:propstat><D:prop><D:resourcetype/><D:getcontentlength>10</D:getcontentlength></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/Music/locked.flac</D:href>
    <D:propstat><D:prop><D:resourcetype/></D:prop><D:status>HTTP/1.1 403 Forbidden</D:status></D:propstat>
  </D:response>
</D:multistatus>"""
}
