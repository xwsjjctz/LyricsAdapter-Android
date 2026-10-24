package com.lyricsadapter.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MusicFileNameTest {
    @Test
    fun `names a song artist - title`() {
        assertEquals("周杰伦 - 晴天.flac", MusicFileName.of("周杰伦", "晴天", "flac"))
    }

    @Test
    fun `replaces characters no file system accepts`() {
        assertEquals("AC DC - Who Made Who.mp3", MusicFileName.of("AC/DC", "Who Made Who?", "mp3"))
    }

    @Test
    fun `collapses dots and trims dots and spaces at the ends`() {
        assertEquals("A.B - x.mp3", MusicFileName.of("  A...B ", ".x. ", "mp3"))
    }

    @Test
    fun `falls back when a part is empty`() {
        assertEquals("Unknown - Unknown Track.m4a", MusicFileName.of("", "???", "M4A"))
    }

    @Test
    fun `prefixes Windows reserved names`() {
        assertEquals("_CON - _nul.txt.mp3", MusicFileName.of("CON", "nul.txt", "mp3"))
    }

    @Test
    fun `keeps names to 180 characters a part`() {
        assertEquals(180, MusicFileName.part("a".repeat(300), "x").length)
    }

    @Test
    fun `rejects odd extensions`() {
        assertFailsWith<IllegalArgumentException> { MusicFileName.of("a", "b", "mp3/../x") }
    }
}
