package com.lyricsadapter.app.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsadapter.app.R
import com.lyricsadapter.app.data.EditResult
import com.lyricsadapter.app.data.PickedCover
import com.lyricsadapter.app.data.TrackEdit
import com.lyricsadapter.app.ui.Cover
import com.lyricsadapter.app.ui.Icon
import com.lyricsadapter.app.ui.LabeledField
import com.lyricsadapter.app.ui.LocalAppColors
import com.lyricsadapter.app.ui.TextAction
import com.lyricsadapter.library.Track
import com.lyricsadapter.lyrics.WordLyricsEditing
import com.lyricsadapter.lyrics.WordLyricsFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LRC_TIMESTAMP = Regex("\\[\\d+:\\d+")

/**
 * Desktop's metadata editor for a local file: cover, title, artist, album and
 * lyrics. A track with word lyrics shows their timed source instead of plain
 * lyrics, and saving refuses text that lost its word timing rather than
 * quietly turning karaoke lyrics into plain ones. Only changed fields are
 * written.
 */
@Composable
fun MetadataEditor(track: Track, onSave: (TrackEdit, (EditResult) -> Unit) -> Unit, onClose: () -> Unit) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wordFormat = track.wordLyricsFormat.takeIf { track.wordLyrics != null }
    val originalLyrics = remember(track.id) {
        if (wordFormat != null) WordLyricsEditing.editableText(track.wordLyrics.orEmpty(), wordFormat) else track.lyrics.orEmpty()
    }
    var title by rememberSaveable(track.id) { mutableStateOf(track.title) }
    var artist by rememberSaveable(track.id) { mutableStateOf(track.artist.orEmpty()) }
    var album by rememberSaveable(track.id) { mutableStateOf(track.album.orEmpty()) }
    var lyrics by rememberSaveable(track.id) { mutableStateOf(originalLyrics) }
    var cover by remember(track.id) { mutableStateOf<PickedCover?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch {
            cover = withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                resolver.openInputStream(uri)?.use { PickedCover(resolver.getType(uri) ?: "image/jpeg", it.readBytes()) }
            }
        }
    }

    val changed = title != track.title || artist != track.artist.orEmpty() || album != track.album.orEmpty() ||
        lyrics != originalLyrics || cover != null
    val messages = EditorMessages(
        invalidWordLyrics = stringResource(R.string.word_lyrics_invalid),
        noWriteAccess = stringResource(R.string.edit_no_write_access),
        failed = stringResource(R.string.edit_failed),
    )

    fun save() {
        val wordEdit = if (wordFormat != null && lyrics != originalLyrics) {
            when (val result = WordLyricsEditing.apply(lyrics, wordFormat, track.wordLyrics)) {
                is WordLyricsEditing.Result.Valid -> result.wordLyrics
                WordLyricsEditing.Result.Invalid -> {
                    error = messages.invalidWordLyrics
                    return
                }
            }
        } else null
        val edit = TrackEdit(
            title = title.takeIf { it != track.title },
            artist = artist.takeIf { it != track.artist.orEmpty() },
            album = album.takeIf { it != track.album.orEmpty() },
            lyrics = lyrics.takeIf { wordFormat == null && it != originalLyrics },
            wordLyrics = wordEdit,
            cover = cover,
        )
        saving = true
        error = null
        onSave(edit) { result ->
            saving = false
            when (result) {
                EditResult.Saved, is EditResult.SavedWithoutWordLyrics -> onClose()
                EditResult.NoWriteAccess -> error = messages.noWriteAccess
                is EditResult.Failed -> error = messages.failed + result.message.takeIf(String::isNotBlank)?.let { "（$it）" }.orEmpty()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction(stringResource(R.string.cancel), onClick = onClose)
            BasicText(
                stringResource(R.string.metadata_title),
                style = TextStyle(color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                modifier = Modifier.weight(1f),
            )
            TextAction(
                stringResource(if (saving) R.string.saving else R.string.save),
                onClick = ::save,
                enabled = changed && !saving,
                bold = true,
            )
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                CoverPicker(track, cover, onPick = { pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LabeledField(stringResource(R.string.field_title), title, { title = it }, trailingLabel = edited(title != track.title))
                    LabeledField(stringResource(R.string.field_artist), artist, { artist = it }, trailingLabel = edited(artist != track.artist.orEmpty()))
                }
            }
            LabeledField(stringResource(R.string.field_album), album, { album = it }, trailingLabel = edited(album != track.album.orEmpty()))
            val badge = when {
                wordFormat == WordLyricsFormat.QRC -> "QRC"
                wordFormat == WordLyricsFormat.YRC -> "YRC"
                LRC_TIMESTAMP.containsMatchIn(lyrics) -> "LRC"
                else -> null
            }
            Column {
                LabeledField(
                    label = stringResource(R.string.field_lyrics) + badge?.let { " · $it" }.orEmpty(),
                    value = lyrics,
                    onValueChange = {
                        lyrics = it
                        error = null
                    },
                    singleLine = false,
                    placeholder = if (wordFormat == null) stringResource(R.string.lyrics_placeholder) else null,
                    trailingLabel = edited(lyrics != originalLyrics),
                    monospace = badge != null,
                )
                if (wordFormat != null) {
                    BasicText(
                        stringResource(R.string.word_lyrics_hint),
                        style = TextStyle(color = colors.secondaryText, fontSize = 12.sp, lineHeight = 17.sp),
                        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                    )
                }
            }
            error?.let {
                BasicText(it, style = TextStyle(color = colors.danger, fontSize = 14.sp, lineHeight = 20.sp), modifier = Modifier.semantics { contentDescription = it })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private class EditorMessages(val invalidWordLyrics: String, val noWriteAccess: String, val failed: String)

@Composable
private fun edited(changed: Boolean): String? = if (changed) stringResource(R.string.edited) else null

@Composable
private fun CoverPicker(track: Track, picked: PickedCover?, onPick: () -> Unit) {
    val colors = LocalAppColors.current
    val label = stringResource(R.string.import_cover)
    Box(
        Modifier
            .size(112.dp)
            .clip(RoundedCornerShape(10.dp))
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .clickable(onClick = onPick),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = remember(picked) {
            picked?.let { android.graphics.BitmapFactory.decodeByteArray(it.data, 0, it.data.size)?.asImageBitmap() }
        }
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Cover(track.coverPath, size = 112.dp)
        }
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color.Black.copy(alpha = 0.45f)).padding(vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(R.drawable.ui_add_photo_alternate, Color.White, size = 18.dp)
            BasicText(label, style = TextStyle(color = Color.White, fontSize = 11.sp))
        }
    }
}
