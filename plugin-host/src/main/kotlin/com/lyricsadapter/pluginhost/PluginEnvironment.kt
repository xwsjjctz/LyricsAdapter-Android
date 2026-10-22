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

        fun loadCompatScript(): String =
            PluginEnvironment::class.java.getResourceAsStream("compat.js")?.bufferedReader()?.use { it.readText() }
                ?: error("compat.js is missing; run `npm run build` in android/compat-js")
    }
}
