package com.lyricsadapter.app.ui.focus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lyricsadapter.app.AppContainer
import com.lyricsadapter.app.ui.TranslationLanguages
import com.lyricsadapter.library.Track
import com.lyricsadapter.lyrics.LyricLine
import kotlinx.coroutines.CancellationException

/** What focus mode shows of a translation: the button's state, the translated lines by index, and the button's action. */
class FocusTranslation(val status: TranslationStatus, val lines: Map<Int, String>, val toggle: () -> Unit)

private val NONE = FocusTranslation(TranslationStatus.Unavailable, emptyMap()) {}

/**
 * Desktop's `usePluginTranslation`: the button appears while an enabled
 * plugin can translate and the song has lyrics; pressing it translates (or
 * retries after an error), and pressing it again returns to the original
 * lines while keeping the result for later.
 */
@Composable
fun rememberFocusTranslation(container: AppContainer, track: Track, lines: List<LyricLine>): FocusTranslation {
    val plugins by container.plugins.plugins.collectAsStateWithLifecycle()
    val preferred by container.settings.translationLanguage.value.collectAsStateWithLifecycle()
    val requests by container.translation.requests.collectAsStateWithLifecycle()
    val translator = remember(plugins) { container.translation.translator() }
    if (translator == null || lines.none { it.text.isNotBlank() }) return NONE

    val language = TranslationLanguages.effective(translator.languages, preferred)
    val key = container.translation.requestKey(track, translator, language)
    val requested = key in requests
    var attempt by remember { mutableIntStateOf(0) }
    var outcome by remember(key) { mutableStateOf<Result<Map<Int, String>>?>(null) }
    LaunchedEffect(key, requested, attempt) {
        if (!requested) return@LaunchedEffect
        outcome = null
        outcome = try {
            Result.success(container.translation.translate(track, lines, translator, language))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            android.util.Log.w("FocusTranslation", "Lyrics translation failed", error)
            Result.failure(error)
        }
    }
    val status = when {
        !requested -> TranslationStatus.Idle
        outcome == null -> TranslationStatus.Translating
        outcome!!.isSuccess -> TranslationStatus.Translated
        else -> TranslationStatus.Error
    }
    return FocusTranslation(status, if (status == TranslationStatus.Translated) outcome!!.getOrThrow() else emptyMap()) {
        when (status) {
            TranslationStatus.Translating, TranslationStatus.Unavailable -> Unit
            TranslationStatus.Error -> attempt++
            else -> container.translation.setRequested(key, status == TranslationStatus.Idle)
        }
    }
}
