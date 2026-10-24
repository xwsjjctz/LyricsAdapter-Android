package com.lyricsadapter.pluginhost

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Platform services and limits shared by the plugin runtimes of one app. */
class PluginEnvironment(
    val logger: PluginLogger,
    val store: PluginDataStore,
    val transport: HttpTransport = OkHttpTransport(),
    val crypto: HostCrypto = JcaHostCrypto(),
    val jobDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val callTimeoutMillis: Long = DEFAULT_CALL_TIMEOUT_MILLIS,
    val memoryLimitBytes: Long = DEFAULT_MEMORY_LIMIT_BYTES,
    val compatScript: String = loadCompatScript(),
) {
    companion object {
        /** Desktop gives a provider request 20 seconds. */
        const val DEFAULT_CALL_TIMEOUT_MILLIS = 20_000L
        const val DEFAULT_MEMORY_LIMIT_BYTES = 128L * 1024 * 1024
        private const val COMPAT_SCRIPT = "/com/lyricsadapter/pluginhost/compat.js"

        fun loadCompatScript(): String =
            // An absolute path: shrinkers may move this class to another package, but not the resource.
            PluginEnvironment::class.java.getResourceAsStream(COMPAT_SCRIPT)?.bufferedReader()?.use { it.readText() }
                ?: error("compat.js is missing; run `npm run build` in compat-js")
    }
}
