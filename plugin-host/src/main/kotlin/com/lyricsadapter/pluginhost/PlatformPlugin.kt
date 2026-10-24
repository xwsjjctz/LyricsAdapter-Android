package com.lyricsadapter.pluginhost

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID

/**
 * One plugin on desktop's plugin platform (`activate(context)`) running in its
 * own QuickJS runtime, for the lyrics.translation extension. Requests and
 * results are checked as desktop checks them (`pluginValidation.ts`), and each
 * call has the provider's declared deadline.
 */
class PlatformPlugin private constructor(
    val manifest: PlatformManifest,
    private val js: QuickJs,
    private val requests: PluginRequestQueue,
    val settings: PlatformPluginSettings,
    val cache: PluginCache,
    private val scope: CoroutineScope,
) : AutoCloseable {

    /** Translates [request] (a `TranslationRequest`) with the provider [providerId]; returns the validated `TranslationResult`. */
    suspend fun translate(providerId: String, request: JsonObject): JsonObject {
        TranslationValidation.checkRequest(request)
        val timeout = manifest.translationTimeoutMillis(providerId)
        val requestId = UUID.randomUUID().toString()
        val payload = buildJsonObject {
            put("requestId", requestId)
            put("providerId", providerId)
            put("request", request)
            put("timeoutMs", timeout)
        }
        try {
            // A little longer than the plugin's own deadline, so its timeout error is the one reported.
            val reply = requests.submit("translate", payload.toString(), timeout + DEADLINE_MARGIN_MILLIS)
            return TranslationValidation.checkResult(Json.parseToJsonElement(reply), request)
        } catch (error: CancellationException) {
            runCatching { requests.submit("cancel", buildJsonObject { put("requestId", requestId) }.toString(), CANCEL_TIMEOUT_MILLIS) }
            throw error
        }
    }

    /** Runs the plugin's `configuration.onDidChange` listeners after a value changed. */
    suspend fun configurationChanged() {
        requests.submit("configurationChanged", "{}", CANCEL_TIMEOUT_MILLIS)
    }

    private fun serve() {
        scope.launch {
            val failure = try {
                js.evaluate<Any?>("await __la_platform.serve()", filename = "plugin:${manifest.id}/serve")
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                error
            }
            requests.close(failure)
        }
    }

    override fun close() {
        requests.close(null)
        scope.cancel()
        js.close()
    }

    companion object {
        private const val DEADLINE_MARGIN_MILLIS = 5_000L
        private const val CANCEL_TIMEOUT_MILLIS = 5_000L

        /** Loads [code] (the package's entry), activates it and starts serving calls; [cacheDirectory] null keeps the cache in memory. */
        suspend fun start(manifest: PlatformManifest, code: String, environment: PluginEnvironment, cacheDirectory: File?): PlatformPlugin {
            val requests = PluginRequestQueue(manifest.id)
            val settings = PlatformPluginSettings(manifest, environment.store)
            val cache = PluginCache(cacheDirectory)
            val js = QuickJs.create(environment.jobDispatcher)
            try {
                js.memoryLimit = environment.memoryLimitBytes
                HostBindings(manifest.id, environment, requests, HostBindings.PlatformBindings(settings, cache)).install(js)
                js.evaluate<Any?>(environment.compatScript, filename = "lyricsadapter-compat.js")
                js.evaluate<Any?>(
                    "__la_platform.define(function (exports, require, module, __filename, __dirname) {\n$code\n});",
                    filename = "plugin:${manifest.id}/${manifest.main}",
                )
            } catch (error: Throwable) {
                js.close()
                throw error
            }
            val scope = CoroutineScope(SupervisorJob() + environment.jobDispatcher)
            val plugin = PlatformPlugin(manifest, js, requests, settings, cache, scope)
            plugin.serve()
            try {
                // Activation is a request like any other, served by the runtime's event loop.
                plugin.requests.submit("activate", buildJsonObject { put("manifest", manifest.json.toString()) }.toString(), environment.callTimeoutMillis)
            } catch (error: Throwable) {
                plugin.close()
                throw error
            }
            return plugin
        }
    }
}

/** Desktop's `validateTranslationRequest` and `validateTranslationResult` (src/shared/pluginValidation.ts). */
object TranslationValidation {
    private const val MAX_JSON = 1_000_000
    private const val MAX_LINES = 5000

    fun checkRequest(request: JsonObject) {
        val document = request["document"] as? JsonObject ?: throw IllegalArgumentException("Invalid translation request")
        val language = (request["targetLanguage"] as? JsonPrimitive)?.content
        require(language != null && language.length <= 64 && PlatformManifest.LANGUAGE_TAG.matches(language)) { "Invalid translation request" }
        listOf("id", "revision", "trackId").forEach { key -> require(id((document[key] as? JsonPrimitive)?.content)) { "Invalid lyric document" } }
        val lines = document["lines"] as? JsonArray ?: throw IllegalArgumentException("Invalid lyric document")
        require(lines.size in 1..MAX_LINES) { "Invalid lyric document" }
        val ids = lines.map { line -> ((line as? JsonObject)?.get("id") as? JsonPrimitive)?.content }
        require(ids.all(::id) && ids.toSet().size == ids.size && request.toString().length <= MAX_JSON) { "Invalid lyric document size or duplicate line ids" }
    }

    fun checkResult(value: JsonElement, request: JsonObject): JsonObject {
        val result = value as? JsonObject ?: throw IllegalStateException("Invalid translation result")
        val document = request["document"] as JsonObject
        val text = { key: String, from: JsonObject -> (from[key] as? JsonPrimitive)?.content }
        check(
            text("documentId", result) == text("id", document) && text("documentRevision", result) == text("revision", document) &&
                text("targetLanguage", result) == text("targetLanguage", request) && result.toString().length <= MAX_JSON,
        ) { "Translation document identity mismatch or excessive result size" }
        val known = (document["lines"] as JsonArray).map { ((it as JsonObject)["id"] as JsonPrimitive).content }.toSet()
        val lines = result["lines"] as? JsonArray ?: throw IllegalStateException("Invalid translation result")
        check(lines.size <= MAX_LINES) { "Invalid translation result" }
        val returned = mutableSetOf<String>()
        for (line in lines) {
            val entry = line as? JsonObject ?: throw IllegalStateException("Invalid translation result")
            val lineId = text("lineId", entry)
            val translated = entry["text"] as? JsonPrimitive
            check(id(lineId) && translated != null && translated.isString && translated.content.length <= 16_384) { "Invalid translation result" }
            check(lineId in known && returned.add(lineId!!)) { "Unknown or duplicate translation line id" }
        }
        return result
    }

    private fun id(value: String?): Boolean = value != null && value.length in 1..128
}
