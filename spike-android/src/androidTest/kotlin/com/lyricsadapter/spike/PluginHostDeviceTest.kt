package com.lyricsadapter.spike

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lyricsadapter.pluginhost.InMemoryPluginDataStore
import com.lyricsadapter.pluginhost.LegacyMusicPlugin
import com.lyricsadapter.pluginhost.PluginCallException
import com.lyricsadapter.pluginhost.PluginEnvironment
import com.lyricsadapter.pluginhost.PluginLogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlin.time.measureTimedValue

/**
 * The plugin host on a real Android runtime: QuickJS from the AAR's .so,
 * compat.js loaded as a Java resource, OkHttp and JCA from the platform.
 * Timings are logged under the LASpike tag.
 */
@RunWith(AndroidJUnit4::class)
class PluginHostDeviceTest {
    private val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
    private val environment = PluginEnvironment(
        logger = { pluginId, level, message -> Log.println(level.priority(), TAG, "[$pluginId] $message") },
        store = InMemoryPluginDataStore(),
    )

    @Test
    fun compatLayerMatchesNode() = runBlocking {
        val (plugin, startup) = measureTimedValue { LegacyMusicPlugin.start("test-source", asset("test-plugin.cjs"), environment) }
        Log.i(TAG, "test plugin runtime ready in ${startup.inWholeMilliseconds} ms")
        plugin.use {
            val crypto = it.invoke("crypto", args()).jsonObject
            assertEquals("5rL/N079bQaMU5YywaS/M5h7e/9i21B7dusYKIxK1jk=", crypto.string("cbc"))
            assertEquals("55502872bd1c277a0af07bb8c4c53fe7", crypto.string("ecb"))
            assertEquals("5d41402abc4b2a76b9719d911017c592", crypto.string("md5"))
            assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8", crypto.string("hmac"))
            val zlib = it.invoke("zlib", args("eJyLNjCwMjDUMzCIfTZzy9MlK7miRwVGBUYFyBcAAJ3DTnA=")).jsonObject
            assertEquals("[00:01.00]晴天\n".repeat(50), zlib.string("streamed"))
            assertEquals("https://music.163.com/api/search?q=%E6%99%B4", it.invoke("web", args()).jsonObject.string("href"))
            assertEquals("done", it.invoke("timers", args()).jsonPrimitive.content)
        }
    }

    @Test
    fun officialPluginsWorkOnDevice() = runBlocking {
        val (netease, neteaseStartup) = measureTimedValue { LegacyMusicPlugin.start("netease", asset("plugins/netease/index.cjs"), environment) }
        val (qq, qqStartup) = measureTimedValue { LegacyMusicPlugin.start("qq", asset("plugins/qq/index.cjs"), environment) }
        Log.i(TAG, "runtime ready: netease ${neteaseStartup.inWholeMilliseconds} ms, qq ${qqStartup.inWholeMilliseconds} ms")

        netease.use { plugin ->
            val (songs, searchTime) = measureTimedValue { plugin.call("searchMusic", args("晴天 周杰伦", 5)).jsonArray }
            Log.i(TAG, "netease search: ${songs.size} results in ${searchTime.inWholeMilliseconds} ms")
            assertTrue(songs.isNotEmpty())

            val url = firstPlayableUrl(plugin, songs)
            val headers = plugin.streamHeaders("")
            val (status, bytes) = probe(url, headers)
            Log.i(TAG, "netease stream ${url.substringBefore("://")}: HTTP $status, first bytes ${bytes.take(8).joinToString(" ") { "%02x".format(it) }}")
            assertTrue(status == 200 || status == 206)
            if (url.startsWith("http://")) {
                val https = try {
                    "HTTP ${probe(url.replaceFirst("http://", "https://"), headers).first}"
                } catch (error: IOException) {
                    "failed: ${error.message}"
                }
                Log.i(TAG, "netease stream upgraded to https: $https")
            }

            val lyrics = plugin.call("getLyrics", args(songs.first().jsonObject.string("songmid"))).jsonObject
            assertTrue(lyrics.string("lyrics").isNotBlank())

            val key = plugin.invoke("netease-qr-key", args()).jsonObject.string("unikey")
            assertPng(plugin.invoke("netease-qr-create", args(key)).jsonObject.string("qrcode"))
        }

        qq.use { plugin ->
            val (start, loginTime) = measureTimedValue { plugin.invoke("qq-login-qr-start", args()).jsonObject }
            Log.i(TAG, "qq QR login start in ${loginTime.inWholeMilliseconds} ms")
            assertPng(start.string("qrcode"))
        }
    }

    private suspend fun firstPlayableUrl(plugin: LegacyMusicPlugin, songs: JsonArray): String {
        for (song in songs) {
            try {
                return plugin.call("getMusicUrl", args(song.jsonObject.string("songmid"), "320")).jsonObject.string("url")
            } catch (error: PluginCallException) {
                Log.i(TAG, "skipping unplayable result: ${error.message}")
            }
        }
        fail("no playable search result")
        error("unreachable")
    }

    private suspend fun probe(url: String, headers: Map<String, String>): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            connection.setRequestProperty("Range", "bytes=0-15")
            connection.responseCode to connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun assertPng(dataUrl: String) {
        val bytes = Base64.getDecoder().decode(dataUrl.substringAfter("base64,"))
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47).map(Int::toByte), bytes.take(4))
    }

    private fun asset(path: String): String = assets.open(path).bufferedReader().use { it.readText() }

    private fun args(vararg values: Any): JsonArray =
        JsonArray(values.map { if (it is Number) JsonPrimitive(it) else JsonPrimitive(it.toString()) })

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonElement.string(name: String): String = jsonObject.string(name)

    private fun PluginLogLevel.priority(): Int = when (this) {
        PluginLogLevel.DEBUG -> Log.DEBUG
        PluginLogLevel.INFO -> Log.INFO
        PluginLogLevel.WARN -> Log.WARN
        PluginLogLevel.ERROR -> Log.ERROR
    }

    private companion object {
        const val TAG = "LASpike"
    }
}
