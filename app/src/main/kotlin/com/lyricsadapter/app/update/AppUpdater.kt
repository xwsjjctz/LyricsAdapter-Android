package com.lyricsadapter.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.lyricsadapter.app.R
import com.lyricsadapter.app.container
import com.lyricsadapter.update.AppRelease
import com.lyricsadapter.update.AppVersion
import com.lyricsadapter.update.ReleaseFeed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Where updating stands, as desktop's `UpdaterState` (`src/shared/updater.ts`). */
sealed interface UpdateState {
    /** This build does not update itself: it has no feed, as development builds do not. */
    data object Unavailable : UpdateState
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState

    /** Found, and waiting to be asked for: the network is metered. */
    data class Available(val release: AppRelease) : UpdateState

    /** [fraction] of the APK is here, from 0 to 1. */
    data class Downloading(val release: AppRelease, val fraction: Float) : UpdateState

    /** Downloaded and checked; installing it is next. */
    data class Downloaded(val release: AppRelease) : UpdateState

    /** Handed to the system, which replaces the app and ends this process when it is done. */
    data class Installing(val release: AppRelease) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Updates the app from its GitHub releases, the way desktop's updater does
 * (`electron/updater.ts`): a check a few seconds after launch and on request,
 * the newer version downloaded at once, then installed when asked or when the
 * app is put away.
 *
 * The system installs the APK and only accepts one signed with the key of
 * the installed app, so the checks here (size, SHA-256, package and version)
 * guard against a broken or wrong download, not against a forged one.
 */
class AppUpdater(
    private val context: Context,
    private val feedUrl: String,
    private val scope: CoroutineScope,
    http: OkHttpClient = OkHttpClient(),
) {
    private val http = http.newBuilder().callTimeout(10, TimeUnit.MINUTES).build()
    private val directory = File(context.cacheDir, "updates")
    private val installed: PackageInfo? = try {
        context.packageManager.getPackageInfo(context.packageName, 0)
    } catch (error: Exception) {
        Log.w(TAG, "Could not read the installed version", error)
        null
    }
    private val installedVersion = installed?.versionName?.let(AppVersion::parse)

    private val _state = MutableStateFlow(if (feedUrl.isEmpty() || installedVersion == null) UpdateState.Unavailable else UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var work: Job? = null
    private var startupCheckDone = false

    /** The version a quiet install was last tried for, so each is tried once. */
    private var quietlyTried: AppVersion? = null

    /** The version waiting to be installed by hand, once the app has been put away with it and come back. */
    private var noticeDue: AppVersion? = null
    private var noticed: AppVersion? = null

    /** The check a few seconds after launch, once per process. */
    fun checkAtStartup() {
        if (startupCheckDone || _state.value == UpdateState.Unavailable) return
        startupCheckDone = true
        scope.launch(Dispatchers.Main) {
            delay(STARTUP_DELAY_MS)
            if (_state.value == UpdateState.Idle) check()
        }
    }

    /** Looks for a newer version and, off a metered network, downloads it. */
    fun check() {
        if (!mayStart()) return
        _state.value = UpdateState.Checking
        work = scope.launch(Dispatchers.IO) {
            attempt {
                abandonSessions()
                val release = ReleaseFeed.update(ReleaseFeed.parse(fetchFeed()), checkNotNull(installedVersion))
                clearDownloads(keep = release?.let(::fileOf))
                when {
                    release == null -> _state.value = UpdateState.UpToDate
                    !ReleaseFeed.trusts(feedUrl, release.apk.url) -> throw IOException("Refusing to download from ${release.apk.url}")
                    release.apk.size > MAX_APK_BYTES -> throw IOException("The update is ${release.apk.size} bytes, more than an APK of this app could be")
                    isVerified(fileOf(release), release) -> _state.value = UpdateState.Downloaded(release)
                    isMetered() -> _state.value = UpdateState.Available(release)
                    else -> fetch(release)
                }
            }
        }
    }

    /** Downloads the version found on a metered network, now that the user asked. */
    fun download() {
        val release = (_state.value as? UpdateState.Available)?.release ?: return
        _state.value = UpdateState.Downloading(release, 0f)
        work = scope.launch(Dispatchers.IO) { attempt { fetch(release) } }
    }

    /** Installs the downloaded version; the system asks the user first where it does not let an app update itself unasked. */
    fun install() = install(quiet = false)

    /**
     * The app left the screen. A downloaded version is installed now if that
     * interrupts nothing: nothing plays, and the screen was not merely
     * turned off over the app. Where the system would ask the user, the
     * attempt is withdrawn and [takeNotice] tells them once they are back.
     */
    fun onBackground(playing: Boolean) {
        val release = (_state.value as? UpdateState.Downloaded)?.release ?: return
        noticeDue = release.version
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        if (playing || !interactive || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || quietlyTried == release.version) return
        quietlyTried = release.version
        install(quiet = true)
    }

    /** The version to tell the user is ready to install, once: it was not installed while the app was away. */
    fun takeNotice(): String? {
        val release = (_state.value as? UpdateState.Downloaded)?.release ?: return null
        if (noticeDue != release.version || noticed == release.version) return null
        noticed = release.version
        return release.version.toString()
    }

    private fun mayStart(): Boolean = when (_state.value) {
        UpdateState.Unavailable, UpdateState.Checking, is UpdateState.Downloading, is UpdateState.Downloaded, is UpdateState.Installing -> false
        UpdateState.Idle, UpdateState.UpToDate, is UpdateState.Available, is UpdateState.Failed -> work?.isActive != true
    }

    /**
     * Runs a step, turning whatever goes wrong into [UpdateState.Failed]: the
     * network, the feed and the file, and also what the feed can provoke
     * elsewhere, such as an address OkHttp will not take or a session the
     * system will not open. Updating must never take the app down.
     */
    private suspend fun attempt(step: suspend () -> Unit) {
        try {
            step()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            fail(error)
        }
    }

    private fun fail(error: Exception) {
        Log.w(TAG, "Update failed", error)
        // One readable line for the settings page, as desktop's summarizeUpdaterError; the rest is in the log.
        val line = (error.message ?: error.javaClass.simpleName).lineSequence().first().trim()
        _state.value = UpdateState.Failed(if (line.length > MAX_MESSAGE_LENGTH) line.take(MAX_MESSAGE_LENGTH - 1) + "…" else line)
    }

    private fun fetchFeed(): String {
        val request = Request.Builder().url(feedUrl).header("Accept", "application/vnd.github+json").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} from the release list")
            val source = response.body.source()
            // One byte over the limit tells a long answer from one that just fits.
            if (source.request(MAX_FEED_BYTES + 1)) throw IOException("The release list is longer than $MAX_FEED_BYTES bytes")
            return source.readUtf8()
        }
    }

    /** Downloads [release]'s APK beside its final name, checks it and only then gives it that name. */
    private fun fetch(release: AppRelease) {
        _state.value = UpdateState.Downloading(release, 0f)
        directory.mkdirs()
        val target = fileOf(release)
        val partial = File(directory, target.name + ".part")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            http.newCall(Request.Builder().url(release.apk.url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} downloading ${release.apk.name}")
                response.body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var shown = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > release.apk.size) throw IOException("${release.apk.name} is longer than the ${release.apk.size} bytes announced")
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            val percent = (received * 100 / release.apk.size).toInt()
                            if (percent != shown) {
                                shown = percent
                                _state.value = UpdateState.Downloading(release, percent / 100f)
                            }
                        }
                    }
                }
            }
            if (received != release.apk.size) throw IOException("${release.apk.name} came as $received bytes, not ${release.apk.size}")
            if (release.apk.sha256 != null && digest.digest().toHex() != release.apk.sha256) throw IOException(context.getString(R.string.update_error_checksum))
            if (!isThisApp(partial, release)) throw IOException(context.getString(R.string.update_error_mismatch))
            if (!partial.renameTo(target)) throw IOException("Could not keep ${target.name}")
        } finally {
            partial.delete()
        }
        _state.value = UpdateState.Downloaded(release)
    }

    private fun fileOf(release: AppRelease) = File(directory, "LyricsAdapter-${release.version}.apk")

    /** Whether [file] is [release]'s APK, whole: left by an earlier launch, it need not be fetched again. */
    private fun isVerified(file: File, release: AppRelease): Boolean {
        if (!file.isFile || file.length() != release.apk.size) return false
        val expected = release.apk.sha256
        if (expected != null) {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            if (digest.digest().toHex() != expected) return false
        }
        return isThisApp(file, release)
    }

    /** Whether the APK in [file] is this app, at [release]'s version and not older than the one installed. */
    private fun isThisApp(file: File, release: AppRelease): Boolean {
        val archive = context.packageManager.getPackageArchiveInfo(file.path, 0) ?: return false
        return archive.packageName == context.packageName &&
            archive.versionName?.let(AppVersion::parse) == release.version &&
            archive.longVersionCode >= (installed?.longVersionCode ?: Long.MAX_VALUE)
    }

    private fun clearDownloads(keep: File?) {
        directory.listFiles()?.filter { it != keep }?.forEach { it.delete() }
    }

    private fun isMetered(): Boolean = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

    private fun install(quiet: Boolean) {
        val release = (_state.value as? UpdateState.Downloaded)?.release ?: return
        _state.value = UpdateState.Installing(release)
        work = scope.launch(Dispatchers.IO) {
            attempt {
                val file = fileOf(release)
                if (!isVerified(file, release)) {
                    file.delete()
                    throw IOException(context.getString(R.string.update_error_checksum))
                }
                val installer = context.packageManager.packageInstaller
                val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(context.packageName)
                    setSize(file.length())
                    // An app may update itself without the user confirming it, from Android 12 on; the system still asks where it does not agree.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(parameters)
                try {
                    installer.openSession(id).use { session ->
                        session.openWrite("base.apk", 0, file.length()).use { output ->
                            file.inputStream().use { it.copyTo(output) }
                            session.fsync(output)
                        }
                        session.commit(statusIntent(id, quiet).intentSender)
                    }
                } catch (error: Exception) {
                    abandon(id)
                    throw error
                }
            }
        }
    }

    private fun statusIntent(session: Int, quiet: Boolean): PendingIntent {
        val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_STATUS).putExtra(EXTRA_QUIET, quiet)
        // Mutable, which has to be said from Android 12 on: the system adds the outcome to it.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, session, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    /** What the system says about an install handed to it; called by [UpdateInstallReceiver]. */
    internal fun onInstallStatus(intent: Intent) {
        val release = (_state.value as? UpdateState.Installing)?.release
        val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        Log.i(TAG, "Install status $status for session $session: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmation(intent)
                if (release == null || confirm == null || intent.getBooleanExtra(EXTRA_QUIET, false)) {
                    // Nobody is there to ask, or this process is not the one that asked: withdraw, the APK stays for later.
                    abandon(session)
                    if (release != null) _state.value = UpdateState.Downloaded(release)
                } else {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            // Success is heard by the new version; see UpdateInstallReceiver.
            PackageInstaller.STATUS_SUCCESS -> Unit
            // The user said no; the download is still good.
            PackageInstaller.STATUS_FAILURE_ABORTED -> if (release != null) _state.value = UpdateState.Downloaded(release)
            else -> if (release != null) {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                fail(IOException(message?.takeIf(String::isNotBlank) ?: "The system refused the update (status $status)"))
            }
        }
    }

    private fun confirmation(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    /** Sessions left open by an earlier process hold a copy of an APK each. */
    private fun abandonSessions() {
        context.packageManager.packageInstaller.mySessions.forEach { abandon(it.sessionId) }
    }

    private fun abandon(session: Int) {
        try {
            context.packageManager.packageInstaller.abandonSession(session)
        } catch (error: SecurityException) {
            // Already gone, or not this app's.
            Log.w(TAG, "Could not abandon install session $session", error)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    internal companion object {
        const val ACTION_INSTALL_STATUS = "com.lyricsadapter.app.update.INSTALL_STATUS"
        private const val EXTRA_QUIET = "quiet"
        private const val TAG = "AppUpdater"

        /** Desktop waits as long after its window is ready (`scheduleStartupCheck`). */
        private const val STARTUP_DELAY_MS = 5_000L
        private const val MAX_FEED_BYTES = 2L * 1024 * 1024
        private const val MAX_APK_BYTES = 300L * 1024 * 1024
        private const val MAX_MESSAGE_LENGTH = 200
    }
}

/** Passes the system's word on an install to the app's [AppUpdater]. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppUpdater.ACTION_INSTALL_STATUS) return
        // Success is heard by the new version, in a process the system started for this alone: there is no updater
        // to tell, and the app cannot bring itself back to the screen from here (the system blocks that).
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE) == PackageInstaller.STATUS_SUCCESS) return
        context.container.updater.onInstallStatus(intent)
    }
}
