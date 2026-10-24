package com.lyricsadapter.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyricsadapter.app.AppContainer
import com.lyricsadapter.app.R
import com.lyricsadapter.app.plugins.CatalogEntry
import com.lyricsadapter.app.plugins.InstalledPlugin
import com.lyricsadapter.app.plugins.PluginPackageException
import com.lyricsadapter.app.ui.ConfirmDialog
import com.lyricsadapter.app.ui.LabeledField
import com.lyricsadapter.app.ui.LocalAppColors
import com.lyricsadapter.app.ui.PillButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.add
import java.io.IOException

/** Sources whose official plugins log in with a QR code, as desktop's settings know them. */
internal val QR_LOGIN_SOURCES = setOf("qq", "netease")

/**
 * Desktop's plugin manager: installed plugins (tap one for its settings),
 * the official catalog to download from, and installing a local `.laplugin`.
 */
@Composable
internal fun PluginsPage(container: AppContainer, onOpenPlugin: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed by container.plugins.plugins.collectAsStateWithLifecycle()
    var catalog by remember { mutableStateOf<List<CatalogEntry>?>(null) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val texts = PluginTexts(
        installed = stringResource(R.string.plugin_installed_ok),
        unsupported = stringResource(R.string.plugin_unsupported),
    )
    LaunchedEffect(Unit) {
        catalog = try {
            container.catalog.entries()
        } catch (error: IOException) {
            catalogError = error.message
            emptyList()
        }
    }
    val pickPackage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            status = try {
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                    ?: throw IOException("无法读取文件")
                val manifest = container.plugins.installPackage(bytes)
                texts.installed.format(manifest.name) to false
            } catch (error: PluginPackageException) {
                error.message.orEmpty() to true
            } catch (error: IOException) {
                error.message.orEmpty() to true
            }
        }
    }

    BasicText(
        stringResource(R.string.plugins_description),
        style = TextStyle(color = LocalAppColors.current.secondaryText, fontSize = 14.sp, lineHeight = 20.sp),
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
    )
    if (installed.isNotEmpty()) {
        SettingsGroup(title = stringResource(R.string.plugins_installed)) {
            installed.forEachIndexed { index, plugin ->
                if (index > 0) RowDivider()
                SettingsRow(plugin.manifest.name, detail = pluginState(plugin), onClick = { onOpenPlugin(plugin.id) })
            }
        }
    }
    val available = catalog.orEmpty().filter { entry -> installed.none { it.id == entry.id && it.manifest.version == entry.version } }
    SettingsGroup(title = stringResource(R.string.plugins_official), footer = catalogError?.let { stringResource(R.string.plugins_catalog_failed, it) }) {
        when {
            catalog == null -> SettingsRow(stringResource(R.string.plugins_catalog_loading), chevron = false)
            available.isEmpty() && catalogError == null -> SettingsRow(stringResource(R.string.plugins_all_installed), chevron = false)
            else -> available.forEachIndexed { index, entry ->
                if (index > 0) RowDivider()
                val update = installed.any { it.id == entry.id }
                SettingsRow(
                    title = entry.name,
                    detail = when {
                        busy == entry.id -> stringResource(R.string.plugins_downloading)
                        !entry.isSupported -> stringResource(R.string.plugins_not_on_android)
                        update -> stringResource(R.string.plugins_update_to, entry.version)
                        else -> stringResource(R.string.plugins_download)
                    },
                    chevron = false,
                    onClick = if (!entry.isSupported || busy != null) null else {
                        {
                            busy = entry.id
                            scope.launch {
                                status = try {
                                    texts.installed.format(container.catalog.install(entry).name) to false
                                } catch (error: PluginPackageException) {
                                    error.message.orEmpty() to true
                                } catch (error: IOException) {
                                    error.message.orEmpty() to true
                                }
                                busy = null
                            }
                        }
                    },
                )
            }
        }
    }
    SettingsGroup {
        SettingsRow(stringResource(R.string.plugins_install_local), R.drawable.ui_library_add, chevron = false, onClick = {
            pickPackage.launch(arrayOf("*/*"))
        })
    }
    status?.let { (text, error) -> StatusText(text, error) }
}

private class PluginTexts(val installed: String, val unsupported: String)

@Composable
private fun pluginState(plugin: InstalledPlugin): String = stringResource(
    when {
        !plugin.enabled -> R.string.plugin_disabled
        plugin.manifest.platform != null -> R.string.plugin_kind_translation
        plugin.signedIn -> R.string.plugin_signed_in
        plugin.manifest.requiresCookie -> R.string.plugin_signed_out
        else -> R.string.plugin_ready
    },
)

/** One plugin's settings: on or off, its login, and uninstalling it. */
@Composable
internal fun PluginDetailPage(container: AppContainer, id: String, onLogin: () -> Unit, onRemoved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val plugins by container.plugins.plugins.collectAsStateWithLifecycle()
    val plugin = plugins.firstOrNull { it.id == id } ?: return
    var cookie by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var confirmUninstall by remember { mutableStateOf(false) }
    val texts = CookieTexts(saved = stringResource(R.string.cookie_saved), invalid = stringResource(R.string.cookie_invalid))

    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp)) {
        BasicText(
            stringResource(R.string.plugin_version, plugin.manifest.version) + " · " + pluginState(plugin),
            style = TextStyle(color = LocalAppColors.current.secondaryText, fontSize = 14.sp),
        )
    }
    SettingsGroup {
        SwitchRow(stringResource(R.string.plugin_enable, plugin.manifest.name), plugin.enabled) { enabled ->
            scope.launch { container.plugins.setEnabled(id, enabled) }
        }
    }
    if (plugin.manifest.platform != null) {
        PlatformPluginSettings(container, plugin)
    } else if (plugin.enabled) {
        SettingsGroup(title = stringResource(R.string.plugin_account), footer = stringResource(if (id in QR_LOGIN_SOURCES) R.string.qr_login_footer else R.string.cookie_login_footer)) {
            if (id in QR_LOGIN_SOURCES) {
                SettingsRow(stringResource(if (plugin.signedIn) R.string.qr_relogin else R.string.qr_login), onClick = onLogin)
                RowDivider()
            }
            Column(Modifier.padding(16.dp)) {
                LabeledField("Cookie", cookie, { cookie = it }, singleLine = false, placeholder = stringResource(R.string.cookie_paste))
                Spacer(Modifier.height(12.dp))
                PillButton(stringResource(R.string.cookie_validate_save), enabled = cookie.isNotBlank(), onClick = {
                    scope.launch {
                        status = try {
                            val result = container.plugins.runtime(id).call("validateCookie", buildJsonArray { add(cookie.trim()) }) as? JsonObject
                            val valid = (result?.get("valid") as? JsonPrimitive)?.booleanOrNull == true
                            if (valid) {
                                container.plugins.saveLogin(id, cookie.trim())
                                cookie = ""
                                texts.saved to false
                            } else {
                                ((result?.get("message") as? JsonPrimitive)?.contentOrNull ?: texts.invalid) to true
                            }
                        } catch (error: Exception) {
                            (error.message ?: texts.invalid) to true
                        }
                    }
                })
            }
            if (plugin.signedIn) {
                RowDivider()
                SettingsRow(stringResource(R.string.qr_logout), danger = true, chevron = false, onClick = { container.plugins.logout(id) })
            }
        }
    }
    status?.let { (text, error) -> StatusText(text, error) }
    SettingsGroup {
        SettingsRow(stringResource(R.string.plugin_uninstall), danger = true, chevron = false, onClick = { confirmUninstall = true })
    }
    if (confirmUninstall) {
        ConfirmDialog(
            title = stringResource(R.string.plugin_uninstall_title, plugin.manifest.name),
            message = stringResource(R.string.plugin_uninstall_message),
            confirmLabel = stringResource(R.string.plugin_uninstall),
            cancelLabel = stringResource(R.string.cancel),
            danger = true,
            onConfirm = {
                scope.launch {
                    container.plugins.uninstall(id)
                    onRemoved()
                }
            },
            onDismiss = { confirmUninstall = false },
        )
    }
}

private class CookieTexts(val saved: String, val invalid: String)
