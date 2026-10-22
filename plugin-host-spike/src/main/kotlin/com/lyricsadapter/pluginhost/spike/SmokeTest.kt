package com.lyricsadapter.pluginhost.spike

import com.lyricsadapter.pluginhost.LegacyMusicPlugin
import com.lyricsadapter.pluginhost.PluginCallException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.time.TimeSource

/** Exercises both official plugins through the paths the app depends on, without logging in. */
class SmokeTest(private val loader: PluginLoader, private val options: SpikeOptions) {
    private val checks = CheckRunner()

    suspend fun run(): Boolean {
        val netease = checks.check("netease: load + createPlugin", { "ok" }) { loader.start("netease") }
        val qq = checks.check("qq: load + createPlugin", { "ok" }) { loader.start("qq") }
        try {
            netease?.let { neteaseChecks(it) }
            qq?.let { qqChecks(it) }
        } finally {
            netease?.close()
            qq?.close()
        }
        return checks.summary()
    }

    private suspend fun neteaseChecks(plugin: LegacyMusicPlugin) {
        val songs = checks.check("netease: searchMusic", ::describeSongs) {
            plugin.call("searchMusic", jsonArgs(options.query, 5)).also { require(it.jsonArray.isNotEmpty()) { "no results" } }
        }
        val songmids = songs?.jsonArray?.map { it.jsonObject.string("songmid") }.orEmpty()
        if (songmids.isNotEmpty()) {
            // Anonymous accounts cannot stream VIP songs, so take the first result that is free.
            val playable = checks.check("netease: getMusicUrl 320 (first free result)", { (index, url) -> "result #${index + 1}, ${describeUrl(url)}" }) {
                firstPlayable(plugin, songmids)
            }
            if (playable != null) {
                checks.check("netease: stream first bytes") {
                    val headers = plugin.streamHeaders("")
                    withContext(Dispatchers.IO) { Media.probeStream(playable.second.jsonObject.string("url"), headers) }
                }
            }
            checks.check("netease: getLyrics", ::describeLyrics) { plugin.call("getLyrics", jsonArgs(songmids.first())) }
        }
        checks.check("netease: 3 searches, then in parallel") { compareParallel(plugin) }
        checks.expectFailure("netease: getPlaylists needs login", LOGIN_REQUIRED) { plugin.call("getPlaylists", NO_ARGS) }
        val key = checks.check("netease: QR key (weapi AES+RSA)", { "unikey ${it.take(6)}…" }) {
            plugin.invoke("netease-qr-key", NO_ARGS).requireSuccess().string("unikey")
        }
        if (key != null) {
            val file = options.outputDirectory.resolve("netease-qr.png")
            checks.check("netease: QR image (qrcode+pngjs)", { "$it bytes → $file" }) {
                Media.savePng(plugin.invoke("netease-qr-create", jsonArgs(key)).requireSuccess().string("qrcode"), file)
            }
        }
    }

    private suspend fun qqChecks(plugin: LegacyMusicPlugin) {
        checks.check("qq: requiresCookie") { plugin.requiresCookie() }
        checks.expectFailure("qq: getLyrics needs login", LOGIN_REQUIRED) { plugin.call("getLyrics", jsonArgs(QQ_SAMPLE_SONGMID)) }
        val file = options.outputDirectory.resolve("qq-qr.png")
        checks.check("qq: QR login start (tough-cookie)", { "$it bytes → $file" }) {
            Media.savePng(plugin.invoke("qq-login-qr-start", NO_ARGS).requireSuccess().string("qrcode"), file)
        }
    }

    private suspend fun firstPlayable(plugin: LegacyMusicPlugin, songmids: List<String>): Pair<Int, JsonElement> {
        val failures = mutableListOf<String>()
        songmids.forEachIndexed { index, songmid ->
            try {
                return index to plugin.call("getMusicUrl", jsonArgs(songmid, "320"))
            } catch (error: PluginCallException) {
                failures += error.message.orEmpty()
            }
        }
        error("no free result: ${failures.distinct().joinToString(" / ")}")
    }

    private suspend fun compareParallel(plugin: LegacyMusicPlugin): String {
        val queries = listOf("七里香", "稻香", "夜曲")
        val sequential = TimeSource.Monotonic.markNow()
        queries.forEach { plugin.call("searchMusic", jsonArgs(it, 3)) }
        val sequentialMs = sequential.elapsedNow().inWholeMilliseconds
        val parallel = TimeSource.Monotonic.markNow()
        coroutineScope { queries.map { async { plugin.call("searchMusic", jsonArgs(it, 3)) } }.awaitAll() }
        return "sequential $sequentialMs ms, parallel ${parallel.elapsedNow().inWholeMilliseconds} ms"
    }

    private companion object {
        /** 晴天 by 周杰伦. */
        const val QQ_SAMPLE_SONGMID = "0039MnYb0qxYhV"
        val LOGIN_REQUIRED = Regex("登录|Cookie|cookie")
    }
}
