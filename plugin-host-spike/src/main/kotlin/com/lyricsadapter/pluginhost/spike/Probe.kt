package com.lyricsadapter.pluginhost.spike

import com.lyricsadapter.pluginhost.PluginCallException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Searches one source and reports which results stream without a login, to find songs for manual testing. */
class Probe(private val loader: PluginLoader, private val options: SpikeOptions) {
    suspend fun run(): Boolean {
        val plugin = loader.start(options.source)
        try {
            val songs = plugin.call("searchMusic", jsonArgs(options.query, 20)).jsonArray.map { it.jsonObject }
            for (song in songs) {
                val title = "${song.string("songname")} - ${(song["singer"]?.jsonArray?.firstOrNull() as? JsonObject)?.string("name")}"
                val result = try {
                    "free (${plugin.call("getMusicUrl", jsonArgs(song.string("songmid"), "320")).jsonObject.string("quality")})"
                } catch (error: PluginCallException) {
                    "no url: ${error.message}"
                }
                println("${song.string("songmid").padEnd(12)} $title  ->  $result")
            }
        } finally {
            plugin.close()
        }
        return true
    }
}
