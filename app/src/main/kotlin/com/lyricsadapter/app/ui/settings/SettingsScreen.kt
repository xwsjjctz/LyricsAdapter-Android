package com.lyricsadapter.app.ui.settings

import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyricsadapter.app.AppContainer
import com.lyricsadapter.app.R
import com.lyricsadapter.app.data.AppLanguage
import com.lyricsadapter.app.ui.focus.flowingBackgroundSupported
import com.lyricsadapter.app.data.AppSettings
import com.lyricsadapter.app.data.ColorMode
import com.lyricsadapter.app.ui.IconButton
import com.lyricsadapter.app.ui.LabeledField
import com.lyricsadapter.app.ui.LocalAppColors
import com.lyricsadapter.app.ui.PillButton
import com.lyricsadapter.webdav.WebDavClient
import com.lyricsadapter.webdav.WebDavConfig
import com.lyricsadapter.webdav.WebDavException
import kotlinx.coroutines.launch
import com.lyricsadapter.app.ui.blocksTouches

/** The settings pages, as desktop's settings navigation lists them. */
enum class SettingsPage(val titleRes: Int) {
    Root(R.string.settings_title),
    General(R.string.settings_general),
    Focus(R.string.settings_focus),
    Cloud(R.string.settings_cloud),
    Plugins(R.string.settings_plugins),
    About(R.string.settings_about),
}

/** Where settings are open: a page, and within the plugins page one plugin and its login. */
private data class SettingsLocation(val page: SettingsPage, val plugin: String? = null, val login: Boolean = false)

/** Settings as a grouped list; each section opens as its own page and back returns one level. */
@Composable
fun SettingsScreen(container: AppContainer, initialPage: SettingsPage, onClose: () -> Unit) {
    val colors = LocalAppColors.current
    val settings = container.settings
    var location by remember { mutableStateOf(SettingsLocation(initialPage)) }
    val page = location.page
    val plugins by container.plugins.plugins.collectAsStateWithLifecycle()
    val back = {
        location = when {
            location.login -> location.copy(login = false)
            location.plugin != null -> location.copy(plugin = null)
            page == SettingsPage.Root || page == initialPage -> {
                onClose()
                location
            }
            else -> SettingsLocation(SettingsPage.Root)
        }
    }
    BackHandler(onBack = back)
    val title = when {
        location.login -> stringResource(R.string.qr_title)
        location.plugin != null -> plugins.firstOrNull { it.id == location.plugin }?.manifest?.name ?: stringResource(page.titleRes)
        else -> stringResource(page.titleRes)
    }

    Column(Modifier.fillMaxSize().blocksTouches().background(colors.background).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(R.drawable.ui_arrow_back, stringResource(R.string.back), tint = colors.accent, onClick = back)
            BasicText(
                title,
                style = TextStyle(color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(44.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            when {
                location.login && location.plugin != null -> LoginPage(container, location.plugin!!, onDone = { location = location.copy(login = false) })
                location.plugin != null -> PluginDetailPage(
                    container,
                    location.plugin!!,
                    onLogin = { location = location.copy(login = true) },
                    onRemoved = { location = location.copy(plugin = null) },
                )
                else -> when (page) {
                SettingsPage.Root -> RootPage(container, onOpen = { location = SettingsLocation(it) })
                SettingsPage.General -> GeneralPage(settings)
                SettingsPage.Focus -> FocusPage(settings)
                SettingsPage.Cloud -> CloudPage(settings)
                SettingsPage.Plugins -> PluginsPage(container, onOpenPlugin = { location = location.copy(plugin = it) })
                SettingsPage.About -> AboutPage()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RootPage(container: AppContainer, onOpen: (SettingsPage) -> Unit) {
    val webDav by container.settings.webDavConfig.collectAsStateWithLifecycle()
    val plugins by container.plugins.plugins.collectAsStateWithLifecycle()
    SettingsGroup {
        SettingsRow(stringResource(R.string.settings_general), R.drawable.ui_settings, onClick = { onOpen(SettingsPage.General) })
        RowDivider(indent = true)
        SettingsRow(stringResource(R.string.settings_focus), R.drawable.ui_tune, onClick = { onOpen(SettingsPage.Focus) })
        RowDivider(indent = true)
        SettingsRow(
            stringResource(R.string.settings_cloud),
            R.drawable.ui_cloud,
            detail = stringResource(if (webDav != null) R.string.cloud_connected else R.string.cloud_not_connected),
            onClick = { onOpen(SettingsPage.Cloud) },
        )
        RowDivider(indent = true)
        SettingsRow(
            stringResource(R.string.settings_plugins),
            R.drawable.ui_extension,
            detail = plugins.count { it.enabled }.takeIf { it > 0 }?.toString(),
            onClick = { onOpen(SettingsPage.Plugins) },
        )
    }
    SettingsGroup {
        SettingsRow(stringResource(R.string.settings_about), R.drawable.ui_info, onClick = { onOpen(SettingsPage.About) })
    }
}

@Composable
private fun GeneralPage(settings: AppSettings) {
    val mode by settings.colorMode.value.collectAsStateWithLifecycle()
    if (AppLanguage.supported) LanguageGroup()
    SettingsGroup(title = stringResource(R.string.settings_appearance)) {
        ChoiceRow(
            options = listOf(
                ColorMode.System to stringResource(R.string.appearance_system),
                ColorMode.Light to stringResource(R.string.state_light),
                ColorMode.Dark to stringResource(R.string.state_dark),
            ),
            selected = mode,
            onSelect = settings.colorMode::set,
        )
    }
}

/** Desktop's language choice, plus following the system; picking one restarts the screen in that language. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun LanguageGroup() {
    val context = LocalContext.current
    val current = remember { AppLanguage.current(context) }
    val options = listOf(null to stringResource(R.string.appearance_system)) + AppLanguage.options
    SettingsGroup(title = stringResource(R.string.settings_language)) {
        options.forEachIndexed { index, (tag, label) ->
            if (index > 0) RowDivider()
            CheckRow(label, checked = tag == current) { if (tag != current) AppLanguage.set(context, tag) }
        }
    }
}

@Composable
private fun FocusPage(settings: AppSettings) {
    val blur by settings.focusBackgroundBlur.value.collectAsStateWithLifecycle()
    val fontSize by settings.focusFontSize.value.collectAsStateWithLifecycle()
    val spacing by settings.focusLineSpacing.value.collectAsStateWithLifecycle()
    val inactiveBlur by settings.focusInactiveBlur.value.collectAsStateWithLifecycle()
    val flowing by settings.focusFlowingBackground.value.collectAsStateWithLifecycle()
    SettingsGroup(
        title = stringResource(R.string.focus_background),
        footer = stringResource(if (flowingBackgroundSupported) R.string.focus_flowing_background_description else R.string.focus_flowing_background_unsupported),
    ) {
        if (flowingBackgroundSupported) {
            SwitchRow(stringResource(R.string.focus_flowing_background), flowing, settings.focusFlowingBackground::set)
            RowDivider()
        }
        SliderRow(stringResource(R.string.focus_background_blur), blur, settings.focusBackgroundBlur.range, "px", settings.focusBackgroundBlur::set)
    }
    SettingsGroup(title = stringResource(R.string.focus_lyrics), footer = stringResource(R.string.focus_lyrics_footer)) {
        SliderRow(stringResource(R.string.focus_font_size), fontSize, settings.focusFontSize.range, "", settings.focusFontSize::set)
        RowDivider()
        SliderRow(stringResource(R.string.focus_line_spacing), spacing, settings.focusLineSpacing.range, "", settings.focusLineSpacing::set)
        RowDivider()
        SliderRow(stringResource(R.string.focus_inactive_blur), inactiveBlur, settings.focusInactiveBlur.range, "px", settings.focusInactiveBlur::set)
    }
}

/** Desktop's WebDAV section: test the connection, and save only once it works. */
@Composable
private fun CloudPage(settings: AppSettings) {
    val saved by settings.webDavConfig.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(saved?.serverUrl.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(saved?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(saved?.password.orEmpty()) }
    var testing by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf<Pair<String, Boolean>?>(null) }
    val texts = CloudTexts(
        fillAll = stringResource(R.string.webdav_fill_all),
        connected = stringResource(R.string.webdav_connected_saved),
        invalidUrl = stringResource(R.string.webdav_invalid_url),
    )

    SettingsGroup(footer = stringResource(R.string.webdav_footer)) {
        Column(Modifier.padding(16.dp)) {
            LabeledField(stringResource(R.string.webdav_url), url, { url = it.trim() }, placeholder = "https://webdav.123pan.cn/webdav", keyboardType = KeyboardType.Uri)
            Spacer(Modifier.height(12.dp))
            LabeledField(stringResource(R.string.webdav_username), username, { username = it }, keyboardType = KeyboardType.Email)
            Spacer(Modifier.height(12.dp))
            LabeledField(stringResource(R.string.webdav_password), password, { password = it }, password = true)
        }
    }
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        PillButton(
            stringResource(if (testing) R.string.webdav_testing else R.string.webdav_test_and_save),
            enabled = !testing,
            onClick = {
                val config = WebDavConfig(url, username, password)
                if (!config.isComplete) {
                    status = texts.fillAll to true
                    return@PillButton
                }
                testing = true
                status = null
                scope.launch {
                    status = try {
                        WebDavClient(config).testConnection()
                        settings.saveWebDav(config)
                        texts.connected to false
                    } catch (error: WebDavException) {
                        error.message.orEmpty() to true
                    } catch (_: IllegalArgumentException) {
                        texts.invalidUrl to true
                    }
                    testing = false
                }
            },
        )
    }
    status?.let { (text, error) -> StatusText(text, error) }
    if (saved != null) {
        SettingsGroup {
            SettingsRow(stringResource(R.string.webdav_disconnect), danger = true, chevron = false, onClick = {
                settings.saveWebDav(null)
                password = ""
                status = null
            })
        }
    }
}

private class CloudTexts(val fillAll: String, val connected: String, val invalidUrl: String)

@Composable
private fun AboutPage() {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(stringResource(R.string.app_name), style = TextStyle(color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.Bold))
        BasicText(stringResource(R.string.about_version, version), style = TextStyle(color = colors.secondaryText, fontSize = 14.sp))
    }
    SettingsGroup {
        SettingsRow(stringResource(R.string.about_desktop), onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/xwsjjctz/LyricsAdapter")))
        })
    }
    SettingsGroup(title = stringResource(R.string.about_licenses)) {
        LICENSES.forEachIndexed { index, (name, license) ->
            if (index > 0) RowDivider()
            SettingsRow(name, detail = license, chevron = false)
        }
    }
}

/** Third-party parts shipped in the app and the licences they come under. */
private val LICENSES = listOf(
    "LyricsAdapter 应用图标" to "CC BY 4.0",
    "Material Symbols" to "Apache-2.0",
    "Jetpack Compose · Media3 · Room" to "Apache-2.0",
    "OkHttp" to "Apache-2.0",
    "Coil" to "Apache-2.0",
    "lofty" to "MIT / Apache-2.0",
    "UniFFI" to "MPL-2.0",
    "JNA" to "Apache-2.0 / LGPL-2.1",
    "QuickJS · quickjs-kt" to "MIT / Apache-2.0",
)
