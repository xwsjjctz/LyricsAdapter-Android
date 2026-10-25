package com.lyricsadapter.app.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyricsadapter.app.AppContainer
import com.lyricsadapter.app.R
import com.lyricsadapter.app.plugins.InstalledPlugin
import com.lyricsadapter.app.plugins.PluginPackageException
import com.lyricsadapter.app.ui.LabeledField
import com.lyricsadapter.app.ui.LocalAppColors
import com.lyricsadapter.app.ui.PillButton
import com.lyricsadapter.app.ui.TranslationLanguages
import com.lyricsadapter.pluginhost.ConfigurationField
import com.lyricsadapter.pluginhost.ConfigurationType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * A platform plugin's settings, as desktop's plugin settings show them: its
 * declared configuration (switches, choices, text, secret and endpoint
 * fields, each with its help text), the target language of its translations,
 * and its cache.
 */
@Composable
internal fun PlatformPluginSettings(container: AppContainer, plugin: InstalledPlugin) {
    val manifest = plugin.manifest.platform ?: return
    val scope = rememberCoroutineScope()
    val settings = remember(plugin) { container.plugins.platformSettings(plugin.id) } ?: return
    // Bumped after each save, so the shown values are read again.
    var revision by remember { mutableIntStateOf(0) }
    val values = remember(revision) { settings.values() }
    val drafts = remember(plugin.id) { mutableStateMapOf<String, String>() }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val saved = stringResource(R.string.cookie_saved)

    val save: (String, JsonPrimitive) -> Unit = { key, value ->
        scope.launch {
            status = try {
                container.plugins.setConfiguration(plugin.id, key, value)
                revision++
                saved to false
            } catch (error: PluginPackageException) {
                error.message.orEmpty() to true
            }
        }
    }

    if (manifest.configuration.isNotEmpty()) SettingsGroup(title = stringResource(R.string.plugin_settings)) {
        manifest.configuration.forEachIndexed { index, field ->
            if (index > 0) RowDivider()
            val value = values[field.key] ?: field.default
            when {
                field.type == ConfigurationType.Boolean -> {
                    SwitchRow(field.title, value.booleanOrNull == true) { save(field.key, JsonPrimitive(it)) }
                    Help(field)
                }
                field.enum != null -> {
                    Column(Modifier.padding(top = 12.dp)) {
                        BasicText(field.title, style = TextStyle(color = LocalAppColors.current.text, fontSize = 16.sp), modifier = Modifier.padding(horizontal = 16.dp))
                        ChoiceRow(field.enum!!.map { it to it.content }, value) { save(field.key, it) }
                        Help(field)
                    }
                }
                else -> TextFieldSetting(field, value, drafts, onSave = save)
            }
        }
    }

    manifest.translators.firstOrNull()?.let { provider ->
        val preferred by container.settings.translationLanguage.value.collectAsStateWithLifecycle()
        val languages = provider.languages ?: TranslationLanguages.DEFAULT
        val chosen = TranslationLanguages.effective(languages, preferred)
        SettingsGroup(title = stringResource(R.string.translation_language)) {
            languages.forEachIndexed { index, tag ->
                if (index > 0) RowDivider()
                CheckRow(TranslationLanguages.displayName(tag), checked = tag == chosen) { container.settings.translationLanguage.set(tag) }
            }
        }
    }

    val context = LocalContext.current
    var usage by remember { mutableStateOf(container.plugins.cacheUsage(plugin.id)) }
    SettingsGroup(footer = stringResource(R.string.plugin_cache_summary, usage.first, Formatter.formatShortFileSize(context, usage.second))) {
        SettingsRow(stringResource(R.string.plugin_clear_cache), chevron = false, onClick = {
            container.plugins.clearCache(plugin.id)
            usage = container.plugins.cacheUsage(plugin.id)
        })
    }
    status?.let { (text, error) -> StatusText(text, error) }
}

/** A text, number, secret or endpoint field, saved with its own button; a secret is never shown back. */
@Composable
private fun TextFieldSetting(field: ConfigurationField, value: JsonPrimitive, drafts: MutableMap<String, String>, onSave: (String, JsonPrimitive) -> Unit) {
    val secret = field.format == "secret"
    val shown = if (secret) "" else value.content
    val draft = drafts[field.key] ?: shown
    val placeholder = when {
        secret && value.content.isNotEmpty() -> stringResource(R.string.plugin_secret_configured)
        secret -> stringResource(R.string.plugin_secret_empty)
        else -> field.default.content.ifEmpty { null }
    }
    Column(Modifier.padding(16.dp)) {
        LabeledField(
            field.title,
            draft,
            { drafts[field.key] = it },
            placeholder = placeholder,
            password = secret,
            keyboardType = when {
                field.type == ConfigurationType.Number -> KeyboardType.Decimal
                field.format == "endpoint" -> KeyboardType.Uri
                else -> KeyboardType.Text
            },
        )
        field.description?.let {
            Spacer(Modifier.height(6.dp))
            BasicText(it, style = TextStyle(color = LocalAppColors.current.secondaryText, fontSize = 13.sp, lineHeight = 18.sp))
        }
        Spacer(Modifier.height(10.dp))
        val changed = draft != shown
        PillButton(stringResource(R.string.save), enabled = changed && !(secret && draft.isEmpty()), onClick = {
            val typed = if (field.type == ConfigurationType.Number) draft.trim().toDoubleOrNull()?.let(::JsonPrimitive) else JsonPrimitive(draft.trim())
            if (typed != null) {
                onSave(field.key, typed)
                drafts.remove(field.key)
            }
        })
    }
}

@Composable
private fun Help(field: ConfigurationField) {
    field.description?.let {
        BasicText(
            it,
            style = TextStyle(color = LocalAppColors.current.secondaryText, fontSize = 13.sp, lineHeight = 18.sp),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}
