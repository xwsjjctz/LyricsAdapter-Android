package com.lyricsadapter.pluginhost

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * The `__la_host` object the compat prelude builds on. Every binding is
 * bound to one plugin, so a plugin can reach only its own secrets and settings.
 * Binary data crosses as Int8Array <-> ByteArray, structured data as JSON.
 */
internal class HostBindings(
    private val pluginId: String,
    private val environment: PluginEnvironment,
    private val requests: PluginRequestQueue,
    /** Only for platform plugins: their configuration, network permission and cache. */
    private val platform: PlatformBindings? = null,
) {
    /** What a platform plugin's context reads from the host. */
    class PlatformBindings(val settings: PlatformPluginSettings, val cache: PluginCache)

    private val fetchBridge = FetchBridge(environment.transport)
    private val sleeps = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()
    private val aborts = ConcurrentHashMap<Long, CompletableDeferred<Unit>>()

    fun install(js: QuickJs) {
        js.define(OBJECT_NAME) {
            function("pluginId") { pluginId }
            function("log") { args ->
                environment.logger.log(pluginId, PluginLogLevel.parse(args.string(0)), args.string(1))
            }

            asyncFunction("nextRequest") { requests.next() }
            function("complete") { args -> requests.complete(args.long(0), args.boolean(1), args.string(2)) }

            asyncFunction("fetch") { args -> fetch(args.string(0), args.bytesOrNull(1), args.long(2)) }
            function("cancelFetch") { args ->
                aborts[args.long(0)]?.complete(Unit)
                Unit
            }
            asyncFunction("sleep") { args -> sleep(args.long(0), args.long(1)) }
            function("cancelSleep") { args ->
                sleeps.remove(args.long(0))?.complete(Unit)
                Unit
            }

            function("randomBytes") { args -> environment.crypto.randomBytes(args.long(0).toInt()) }
            function("randomUUID") { environment.crypto.randomUUID() }
            function("cipher") { args ->
                environment.crypto.cipher(
                    algorithm = args.string(0),
                    encrypt = args.boolean(1),
                    key = args.bytes(2),
                    iv = args.bytesOrNull(3),
                    data = args.bytes(4),
                    autoPadding = args.boolean(5),
                )
            }
            function("digest") { args -> environment.crypto.digest(args.string(0), args.bytes(1)) }
            function("hmac") { args -> environment.crypto.hmac(args.string(0), args.bytes(1), args.bytes(2)) }

            function("readSecret") { args -> environment.store.readSecret(pluginId, secretName(args.string(0))) }
            function("writeSecrets") { args ->
                val entries = JSON.decodeFromString<Map<String, String>>(args.string(0))
                entries.keys.forEach(::secretName)
                environment.store.writeSecrets(pluginId, entries)
            }
            function("configuration") { JsonObject(platformBindings().settings.values()).toString() }
            function("allowedOrigins") { JsonArray(platformBindings().settings.allowedOrigins().map(::JsonPrimitive)).toString() }
            function("cacheGet") { args -> platformBindings().cache.get(args.string(0)) }
            function("cacheSet") { args -> platformBindings().cache.set(args.string(0), args.stringOrNull(1)) }
            function("cacheClear") { platformBindings().cache.clear() }

            function("readSetting") { args -> environment.store.readSetting(settingKey(args.string(0))) }
            function("writeSetting") { args -> environment.store.writeSetting(settingKey(args.string(0)), args.stringOrNull(1)) }
        }
    }

    private suspend fun fetch(metaJson: String, body: ByteArray?, requestId: Long): List<Any?> {
        val meta = JSON.decodeFromString<FetchRequestMeta>(metaJson)
        val abort = CompletableDeferred<Unit>()
        aborts[requestId] = abort
        try {
            // An abort must reject the promise, not cancel the binding's job:
            // the runtime waits for the job to settle the promise it returned.
            val result = coroutineScope {
                val exchange = async { fetchBridge.fetch(meta, body) }
                select {
                    exchange.onAwait { it }
                    abort.onAwait {
                        exchange.cancel()
                        throw FetchException("The operation was aborted")
                    }
                }
            }
            return listOf(JSON.encodeToString(result.meta), result.body)
        } catch (error: CancellationException) {
            throw error
        } catch (error: FetchException) {
            throw error
        } catch (error: Exception) {
            // Many I/O exceptions carry no message, which would reach the plugin as "fetch failed: ".
            val reason = listOfNotNull(error.javaClass.simpleName, error.message?.takeIf(String::isNotBlank)).joinToString(": ")
            environment.logger.log(pluginId, PluginLogLevel.WARN, "fetch ${meta.method} ${meta.url.substringBefore('?')} failed: $reason")
            throw FetchException(reason)
        } finally {
            aborts.remove(requestId)
        }
    }

    private suspend fun sleep(id: Long, milliseconds: Long) {
        val wake = CompletableDeferred<Unit>()
        sleeps[id] = wake
        try {
            withTimeoutOrNull(milliseconds.coerceAtLeast(0)) { wake.await() }
        } finally {
            sleeps.remove(id, wake)
        }
    }

    private fun platformBindings(): PlatformBindings = platform ?: throw IllegalStateException("Not a platform plugin")

    private fun secretName(name: String): String {
        require(NAME_PATTERN.matches(name)) { "Invalid plugin secret name" }
        return name
    }

    /** Desktop gives a plugin only the settings under its own prefix. */
    private fun settingKey(key: String): String {
        require(key.startsWith("plugin:$pluginId:")) { "Plugins may only use settings under plugin:$pluginId:" }
        return key
    }

    private companion object {
        const val OBJECT_NAME = "__la_host"
        val NAME_PATTERN = Regex("^[a-z][a-z0-9-]{0,63}$")
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

private fun Array<Any?>.string(index: Int): String =
    getOrNull(index) as? String ?: throw IllegalArgumentException("Argument $index must be a string")

private fun Array<Any?>.stringOrNull(index: Int): String? = getOrNull(index) as? String

private fun Array<Any?>.long(index: Int): Long =
    (getOrNull(index) as? Number)?.toLong() ?: throw IllegalArgumentException("Argument $index must be a number")

private fun Array<Any?>.boolean(index: Int): Boolean =
    getOrNull(index) as? Boolean ?: throw IllegalArgumentException("Argument $index must be a boolean")

private fun Array<Any?>.bytes(index: Int): ByteArray =
    bytesOrNull(index) ?: throw IllegalArgumentException("Argument $index must be an Int8Array")

private fun Array<Any?>.bytesOrNull(index: Int): ByteArray? = getOrNull(index) as? ByteArray
