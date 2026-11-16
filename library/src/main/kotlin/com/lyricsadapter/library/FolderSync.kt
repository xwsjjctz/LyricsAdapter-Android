package com.lyricsadapter.library

/**
 * An audio file found in a folder the library follows. [id] is how the
 * library addresses it; [key] names the file itself, the same however it was
 * reached, so a file picked singly earlier is known again; [path] is where it
 * sits under the folder.
 */
data class FolderFile(val id: String, val key: String, val path: String)

/** What a look through a folder changes: files to read into the library, and ids of tracks to drop from it. */
data class FolderChanges(val added: List<FolderFile>, val removed: List<String>)

/** Keeps the library in step with a folder of music: what is new in it comes in, what is gone goes. */
object FolderSync {
    /** What lofty reads; a provider's type for these is often just "a file". */
    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "aiff", "aif", "ape", "wv")

    /** Files that sit beside music, playlists among them, which Android reports as audio. */
    private val OTHER_EXTENSIONS = setOf("lrc", "m3u", "m3u8", "pls", "cue", "jpg", "jpeg", "png", "txt", "nfo")
    private val AUDIO_TYPES = setOf("application/ogg", "application/flac", "application/x-flac")

    /** Whether a file called [name], of the type its provider reports, is a song to import. */
    fun isAudio(name: String, mimeType: String?): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return when {
            extension in AUDIO_EXTENSIONS -> true
            extension in OTHER_EXTENSIONS || mimeType == null -> false
            else -> mimeType.startsWith("audio/") || mimeType in AUDIO_TYPES
        }
    }

    /**
     * The changes that bring the library in step with a folder now holding
     * [listed]. [libraryKeys] are the keys of every track in the library,
     * [folderTrackIds] the ids of those that came from this folder, and
     * [excluded] ids the user removed from the library, which stay out.
     * New files come in the order a file manager lists them.
     */
    fun changes(listed: List<FolderFile>, libraryKeys: Set<String>, folderTrackIds: Set<String>, excluded: Set<String>): FolderChanges {
        val present = listed.mapTo(HashSet()) { it.id }
        val added = listed
            .filter { it.key !in libraryKeys && it.id !in folderTrackIds && it.id !in excluded }
            .distinctBy { it.key }
            .sortedWith(compareBy(NATURAL_ORDER) { it.path })
        return FolderChanges(added = added, removed = folderTrackIds.filter { it !in present })
    }

    /** Case is ignored and runs of digits compare by value, so "Track 2" comes before "Track 10". */
    private val NATURAL_ORDER = Comparator<String> { left, right ->
        var a = 0
        var b = 0
        while (a < left.length && b < right.length) {
            if (left[a].isDigit() && right[b].isDigit()) {
                val endA = left.endOfDigits(a)
                val endB = right.endOfDigits(b)
                val numberA = left.substring(a, endA).trimStart('0')
                val numberB = right.substring(b, endB).trimStart('0')
                val order = compareValuesBy(numberA, numberB, { it.length }, { it })
                if (order != 0) return@Comparator order
                a = endA
                b = endB
            } else {
                val order = left[a].lowercaseChar().compareTo(right[b].lowercaseChar())
                if (order != 0) return@Comparator order
                a++
                b++
            }
        }
        (left.length - a).compareTo(right.length - b)
    }

    private fun String.endOfDigits(start: Int): Int {
        var end = start
        while (end < length && this[end].isDigit()) end++
        return end
    }
}
