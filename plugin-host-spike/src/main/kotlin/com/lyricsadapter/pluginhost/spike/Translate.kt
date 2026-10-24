package com.lyricsadapter.pluginhost.spike

import com.lyricsadapter.pluginhost.PlatformManifest
import com.lyricsadapter.pluginhost.PlatformPlugin
import com.lyricsadapter.pluginhost.PluginEnvironment
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Runs desktop's installed LLM translation plugin (`llm-translation`) on the
 * platform host against a local stand-in for an OpenAI-compatible API, which
 * answers every line with "译:" and the line. Nothing leaves the machine.
 */
class Translate(private val directory: Path, private val environment: PluginEnvironment) {
    private val checks = CheckRunner()

    suspend fun run(): Boolean {
        val server = fakeModel()
        try {
            val folder = directory.resolve("llm-translation")
            val manifest = PlatformManifest.parse(Json.parseToJsonElement(folder.resolve("manifest.json").readText()).jsonObject)
            PlatformPlugin.start(manifest, folder.resolve(manifest.main).readText(), environment, cacheDirectory = null).use { plugin ->
                plugin.settings.set("endpoint", JsonPrimitive("http://127.0.0.1:${server.address.port}/v1"))
                plugin.settings.set("model", JsonPrimitive("stand-in"))
                checks.check("llm-translation: translate 3 lines", { it }) {
                    val result = plugin.translate(manifest.translators.first().id, request())
                    val lines = result["lines"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content }
                    require(lines == listOf("译:Hello", "译:World", "译:Hello")) { "unexpected lines $lines" }
                    lines.joinToString(" | ")
                }
            }
        } finally {
            server.stop(0)
        }
        return checks.summary()
    }

    private fun request() = buildJsonObject {
        put(
            "document",
            buildJsonObject {
                put("id", "lyrics-spike")
                put("revision", "r1")
                put("trackId", "spike")
                put("lines", buildJsonArray {
                    listOf("Hello", "World", "Hello").forEachIndexed { index, text ->
                        add(buildJsonObject { put("id", "line-$index"); put("text", text) })
                    }
                })
            },
        )
        put("targetLanguage", "zh")
    }

    /** Answers chat completions with a translation of every line in the user message. */
    private fun fakeModel(): HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/v1/chat/completions") { exchange ->
            val body = Json.parseToJsonElement(exchange.requestBody.readBytes().decodeToString()).jsonObject
            val user = body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
            val lines = Json.parseToJsonElement(user).jsonObject["lines"]!!.jsonArray
            val translations = buildJsonObject {
                put("translations", buildJsonArray {
                    lines.forEach { line ->
                        val item = line.jsonObject
                        add(buildJsonObject { put("id", item["id"]!!.jsonPrimitive.content); put("text", "译:" + item["text"]!!.jsonPrimitive.content) })
                    }
                })
            }
            val reply = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject {
                        put("finish_reason", "stop")
                        put("message", buildJsonObject { put("role", "assistant"); put("content", translations.toString()) })
                    })
                })
            }.toString().toByteArray()
            exchange.responseHeaders.add("content-type", "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        start()
    }
}
