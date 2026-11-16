package com.lyricsadapter.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FolderSyncTest {
    private fun file(name: String, id: String = "tree/$name", key: String = "key/$name") = FolderFile(id, key, name)

    @Test
    fun `adds every file of a folder seen for the first time`() {
        val listed = listOf(file("a.flac"), file("b.mp3"))
        val changes = FolderSync.changes(listed, libraryKeys = emptySet(), folderTrackIds = emptySet(), excluded = emptySet())
        assertEquals(listed, changes.added)
        assertEquals(emptyList(), changes.removed)
    }

    @Test
    fun `adds only what is new on a later look`() {
        val listed = listOf(file("a.flac"), file("b.mp3"), file("c.flac"))
        val changes = FolderSync.changes(
            listed,
            libraryKeys = setOf("key/a.flac", "key/b.mp3"),
            folderTrackIds = setOf("tree/a.flac", "tree/b.mp3"),
            excluded = emptySet(),
        )
        assertEquals(listOf(file("c.flac")), changes.added)
        assertEquals(emptyList(), changes.removed)
    }

    @Test
    fun `skips a file the library already holds from another import`() {
        // Picked singly before, so its track has another id, but it is the same file.
        val changes = FolderSync.changes(listOf(file("a.flac")), libraryKeys = setOf("key/a.flac"), folderTrackIds = emptySet(), excluded = emptySet())
        assertEquals(emptyList(), changes.added)
    }

    @Test
    fun `removes the folder's tracks whose files are gone`() {
        val changes = FolderSync.changes(
            listOf(file("a.flac")),
            libraryKeys = setOf("key/a.flac", "key/gone.flac"),
            folderTrackIds = setOf("tree/a.flac", "tree/gone.flac"),
            excluded = emptySet(),
        )
        assertEquals(listOf("tree/gone.flac"), changes.removed)
        assertEquals(emptyList(), changes.added)
    }

    @Test
    fun `leaves out what the user removed from the library`() {
        val changes = FolderSync.changes(listOf(file("a.flac"), file("b.mp3")), emptySet(), emptySet(), excluded = setOf("tree/b.mp3"))
        assertEquals(listOf(file("a.flac")), changes.added)
    }

    @Test
    fun `adds a file reached twice only once`() {
        val listed = listOf(file("a.flac"), file("copy/a.flac", key = "key/a.flac"))
        assertEquals(listOf(file("a.flac")), FolderSync.changes(listed, emptySet(), emptySet(), emptySet()).added)
    }

    @Test
    fun `adds in the order a file manager shows, numbers by value and case ignored`() {
        val listed = listOf("b/1.flac", "Track 10.flac", "track 2.flac", "a/2.flac", "Track 1.flac").map { file(it) }
        assertEquals(
            listOf("a/2.flac", "b/1.flac", "Track 1.flac", "track 2.flac", "Track 10.flac"),
            FolderSync.changes(listed, emptySet(), emptySet(), emptySet()).added.map { it.path },
        )
    }

    @Test
    fun `knows audio by extension whatever type the provider reports`() {
        assertTrue(FolderSync.isAudio("周杰伦 - 晴天.flac", "application/octet-stream"))
        assertTrue(FolderSync.isAudio("Song.MP3", null))
        assertTrue(FolderSync.isAudio("a.m4a", "audio/mp4"))
        assertTrue(FolderSync.isAudio("a.ogg", "application/ogg"))
    }

    @Test
    fun `takes an unknown extension by its audio type`() {
        assertTrue(FolderSync.isAudio("recording", "audio/mpeg"))
        assertTrue(FolderSync.isAudio("a.oga", "audio/ogg"))
        assertFalse(FolderSync.isAudio("notes", "text/plain"))
        assertFalse(FolderSync.isAudio("clip.mp4", "video/mp4"))
    }

    @Test
    fun `leaves out covers, lyrics and playlists`() {
        assertFalse(FolderSync.isAudio("cover.jpg", "image/jpeg"))
        assertFalse(FolderSync.isAudio("晴天.lrc", "application/octet-stream"))
        // Android reports playlists as audio.
        assertFalse(FolderSync.isAudio("all.m3u", "audio/x-mpegurl"))
        assertFalse(FolderSync.isAudio("all.m3u8", "audio/mpegurl"))
        assertFalse(FolderSync.isAudio("radio.pls", "audio/x-scpls"))
    }
}
