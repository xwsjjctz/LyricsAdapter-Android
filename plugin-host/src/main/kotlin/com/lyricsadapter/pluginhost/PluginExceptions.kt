package com.lyricsadapter.pluginhost

/** The plugin rejected the call; the message is the plugin's error message. */
class PluginCallException(message: String) : Exception(message)

class PluginTimeoutException(pluginId: String, operation: String, timeoutMillis: Long) :
    Exception("Plugin $pluginId did not answer $operation within $timeoutMillis ms")

/** The runtime stopped serving requests: it was closed, or a script error escaped its event loop. */
class PluginUnavailableException(pluginId: String, cause: Throwable?) :
    IllegalStateException("Plugin $pluginId is not running", cause)
