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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * One legacy (`apiVersion: 1`, `createPlugin(host)`) music plugin running in
 * its own QuickJS runtime. Calls mirror desktop's plugin handle: provider
 * methods and validateCookie through [call], login actions through [invoke].
 */
class LegacyMusicPlugin private constructor(
    override val id: String,
    private val js: QuickJs,
    private val requests: PluginRequestQueue,
    private val callTimeoutMillis: Long,
    private val scope: CoroutineScope,
) : MusicSourcePlugin {

    override suspend fun call(method: String, args: JsonArray): JsonElement = request(
        "call",
        buildJsonObject {
            put("method", method)
            put("args", args)
        },
    )

    override suspend fun invoke(action: String, args: JsonArray): JsonElement = request(
        "invoke",
        buildJsonObject {
            put("action", action)
            put("args", args)
        },
    )

    override suspend fun streamHeaders(cookie: String): Map<String, String> =
        request("streamHeaders", buildJsonObject { put("cookie", cookie) })
            .jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }

    override suspend fun requiresCookie(): Boolean = request("requiresCookie", JsonObject(emptyMap())).jsonPrimitive.boolean

    private suspend fun request(operation: String, payload: JsonObject): JsonElement =
        Json.parseToJsonElement(requests.submit(operation, payload.toString(), callTimeoutMillis))

    private fun serve() {
        scope.launch {
            val failure = try {
                js.evaluate<Any?>("await __la.serve()", filename = "plugin:$id/serve")
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
        private val PLUGIN_ID = Regex("^[a-z][a-z0-9-]{0,63}$")

        /** Loads [code] (the package's `index.cjs`), creates the plugin and starts serving calls. */
        suspend fun start(id: String, code: String, environment: PluginEnvironment): LegacyMusicPlugin {
            require(PLUGIN_ID.matches(id)) { "Invalid plugin id: $id" }
            val requests = PluginRequestQueue(id)
            val js = QuickJs.create(environment.jobDispatcher)
            try {
                js.memoryLimit = environment.memoryLimitBytes
                HostBindings(id, environment, requests).install(js)
                js.evaluate<Any?>(environment.compatScript, filename = "lyricsadapter-compat.js")
                js.evaluate<Any?>(commonJsModule(code), filename = "plugin:$id/index.cjs")
                js.evaluate<Any?>("__la.create()", filename = "plugin:$id/create")
            } catch (error: Throwable) {
                js.close()
                throw error
            }
            val scope = CoroutineScope(SupervisorJob() + environment.jobDispatcher)
            return LegacyMusicPlugin(id, js, requests, environment.callTimeoutMillis, scope).also { it.serve() }
        }

        /** The CommonJS wrapper Node puts around a module, keeping the code's first line at line 2. */
        private fun commonJsModule(code: String): String =
            "__la.define(function (exports, require, module, __filename, __dirname) {\n$code\n});"
    }
}
