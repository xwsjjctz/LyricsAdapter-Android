package com.lyricsadapter.library

import com.lyricsadapter.audiotags.AudioTags
import com.lyricsadapter.audiotags.WordLyrics
import com.lyricsadapter.lyrics.WordLyricsFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.lyricsadapter.audiotags.WordLyricsFormat as TagWordLyricsFormat

class TrackFactoryTest {
    private fun tags(title: String? = null, wordLyrics: WordLyrics? = null) = AudioTags(
        title = title,
        artist = "",
        album = "叶惠美",
        lyrics = "[00:01.00]故事的小黄花",
        wordLyrics = wordLyrics,
        durationMs = 269_000,
        bitRate = 320,
        sampleRate = 44_100,
        cover = null,
    )

    @Test
    fun `a missing or empty title falls back to the file name`() {
        assertEquals("晴天", TrackFactory.fromTags("content://a", "晴天.flac", tags(title = null), null, 0).title)
        assertEquals("晴天", TrackFactory.fromTags("content://a", "晴天.flac", tags(title = ""), null, 0).title)
        assertEquals("七里香", TrackFactory.fromTags("content://a", "x.flac", tags(title = "七里香"), null, 0).title)
    }

    @Test
    fun `empty fields are stored as missing`() {
        val track = TrackFactory.fromTags("content://a", "a.mp3", tags(), null, 0)
        assertNull(track.artist)
        assertEquals("叶惠美", track.album)
    }

    @Test
    fun `word lyrics keep their format and win when parsed`() {
        val qrc = "<QrcInfos><Lyric_1 LyricContent=\"[1000,500]晴(1000,500)\n\"/></QrcInfos>"
        val track = TrackFactory.fromTags("content://a", "a.flac", tags(wordLyrics = WordLyrics(TagWordLyricsFormat.QRC, qrc)), null, 0)

        assertEquals(WordLyricsFormat.QRC, track.wordLyricsFormat)
        val parsed = track.parsedLyrics()
        assertTrue(parsed.hasWordTiming)
        assertEquals("晴", parsed.plainText)
    }
}
