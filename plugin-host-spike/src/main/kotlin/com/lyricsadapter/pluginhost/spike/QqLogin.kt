package com.lyricsadapter.pluginhost.spike

import com.lyricsadapter.pluginhost.LegacyMusicPlugin
import com.lyricsadapter.pluginhost.PluginDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * QQ Music QR login through the plugin, then the calls that need it. The
 * cookie and credential stay in memory; nothing is written to disk.
 */
class QqLogin(
    private val loader: PluginLoader,
    private val store: PluginDataStore,
    private val options: SpikeOptions,
) {
    private val checks = CheckRunner()

    suspend fun run(): Boolean = loader.start("qq").use { plugin ->
        if (!login(plugin)) return false
        val songs = checks.check("qq: searchMusic", ::describeSongs) {
            plugin.call("searchMusic", jsonArgs(options.query, 5)).also { require(it.jsonArray.isNotEmpty()) { "no results" } }
        }
        val song = songs?.jsonArray?.first()?.jsonObject
        if (song != null) {
            val songmid = song.string("songmid")
            val mediaMid = song["mediaMid"]?.let { song.string("mediaMid") } ?: songmid
            val url = checks.check("qq: getMusicUrl 320", ::describeUrl) { plugin.call("getMusicUrl", jsonArgs(songmid, "320", mediaMid)) }
            if (url != null) {
                checks.check("qq: stream first bytes") {
                    val headers = plugin.streamHeaders(store.readSecret("qq", "cookie"))
                    withContext(Dispatchers.IO) { Media.probeStream(url.jsonObject.string("url"), headers) }
                }
            }
            checks.check("qq: getLyrics", ::describeLyrics) { plugin.call("getLyrics", jsonArgs(songmid)) }
        }
        checks.check("qq: getPlaylists", { "${it.jsonArray.size} playlists" }) { plugin.call("getPlaylists", NO_ARGS) }
        checks.summary()
    }

    private suspend fun login(plugin: LegacyMusicPlugin): Boolean {
        val start = plugin.invoke("qq-login-qr-start", NO_ARGS).requireSuccess()
        val file = options.outputDirectory.resolve("qq-login.png").toAbsolutePath()
        Media.savePng(start.string("qrcode"), file)
        println("Scan $file with the QQ Music app.")
        var lastStatus = ""
        repeat(POLL_ATTEMPTS) {
            delay(POLL_INTERVAL_MILLIS)
            val poll = plugin.invoke("qq-login-qr-poll", jsonArgs(start.string("token"))).requireSuccess()
            val status = poll.string("status")
            if (status != lastStatus) println("  status: $status")
            lastStatus = status
            when (status) {
                "done" -> {
                    val credential = poll["credential"]?.toString().orEmpty()
                    store.writeSecrets("qq", mapOf("cookie" to poll.string("cookie"), "credential" to credential))
                    return true
                }
                "expired", "error" -> return false
            }
        }
        println("  gave up waiting for the scan")
        return false
    }

    private companion object {
        const val POLL_ATTEMPTS = 90
        const val POLL_INTERVAL_MILLIS = 2_000L
    }
}
