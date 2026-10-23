package com.lyricsadapter.library

import com.lyricsadapter.audiotags.AudioTags
import com.lyricsadapter.lyrics.LyricsParser
import com.lyricsadapter.lyrics.ParsedLyrics
import com.lyricsadapter.lyrics.WordLyricsFormat
import com.lyricsadapter.audiotags.WordLyricsFormat as TagWordLyricsFormat

/** A track in the local library. */
data class Track(
    /** Stable identity: the document URI the user imported. */
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    /** The embedded cover, copied into app storage on import. */
    val coverPath: String?,
    /** Plain or LRC lyrics as stored in the file. */
    val lyrics: String?,
    /** Raw QRC/YRC payload, which carries word timing. */
    val wordLyrics: String?,
    val wordLyricsFormat: WordLyricsFormat?,
    val addedAtMs: Long,
) {
    fun parsedLyrics(): ParsedLyrics = LyricsParser.parse(lyrics.orEmpty(), wordLyrics, wordLyricsFormat)
}

object TrackFactory {
    /**
     * A track from the tags read on import, following desktop's `useImport`: a
     * missing or empty title falls back to the file name without extension.
     */
    fun fromTags(id: String, fileName: String, tags: AudioTags, coverPath: String?, addedAtMs: Long): Track = Track(
        id = id,
        title = tags.title?.takeIf { it.isNotEmpty() } ?: fileName.substringBeforeLast('.').ifEmpty { fileName },
        artist = tags.artist?.takeIf { it.isNotEmpty() },
        album = tags.album?.takeIf { it.isNotEmpty() },
        durationMs = tags.durationMs,
        coverPath = coverPath,
        lyrics = tags.lyrics?.takeIf { it.isNotEmpty() },
        wordLyrics = tags.wordLyrics?.text,
        wordLyricsFormat = when (tags.wordLyrics?.format) {
            TagWordLyricsFormat.QRC -> WordLyricsFormat.QRC
            TagWordLyricsFormat.YRC -> WordLyricsFormat.YRC
            null -> null
        },
        addedAtMs = addedAtMs,
    )
}
