package com.lyricsadapter.pluginhost.spike

import com.lyricsadapter.pluginhost.InMemoryPluginDataStore
import com.lyricsadapter.pluginhost.LegacyMusicPlugin
import com.lyricsadapter.pluginhost.PluginEnvironment
import com.lyricsadapter.pluginhost.PluginLogLevel
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.system.exitProcess

/**
 * Runs the desktop's installed QQ / NetEase plugins in the QuickJS host.
 *
 *   smoke     automated checks against the live services (no login needed)
 *   qq-login  QR login for QQ Music, then search / stream / lyrics with it
 */
fun main(args: Array<String>) {
    val options = SpikeOptions.parse(args.toList())
    val store = InMemoryPluginDataStore()
    val environment = PluginEnvironment(
        logger = { pluginId, level, message ->
            if (options.verbose || level >= PluginLogLevel.WARN) println("    [$pluginId:${level.name.lowercase()}] $message")
        },
        store = store,
    )
    val ok = runBlocking {
        val loader = PluginLoader(options.pluginsDirectory, environment)
        when (options.command) {
            "smoke" -> SmokeTest(loader, options).run()
            "qq-login" -> QqLogin(loader, store, options).run()
            else -> {
                println("Unknown command: ${options.command}. Use smoke or qq-login.")
                false
            }
        }
    }
    exitProcess(if (ok) 0 else 1)
}

class SpikeOptions(
    val command: String,
    val pluginsDirectory: Path,
    val outputDirectory: Path,
    val query: String,
    val verbose: Boolean,
) {
    companion object {
        fun parse(args: List<String>): SpikeOptions {
            fun value(flag: String): String? = args.indexOf(flag).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
            return SpikeOptions(
                command = args.firstOrNull()?.takeUnless { it.startsWith("--") } ?: "smoke",
                pluginsDirectory = Path(value("--plugins-dir") ?: "${System.getProperty("user.home")}/.la/plugin"),
                outputDirectory = Path(value("--out") ?: "build/spike-output"),
                query = value("--query") ?: "晴天 周杰伦",
                verbose = "--verbose" in args,
            )
        }
    }
}

/** Starts installed legacy plugins from `<plugins-dir>/<id>/index.cjs`. */
class PluginLoader(private val directory: Path, private val environment: PluginEnvironment) {
    suspend fun start(id: String): LegacyMusicPlugin {
        val code = directory.resolve(id).resolve("index.cjs").readText()
        return LegacyMusicPlugin.start(id, code, environment)
    }
}
