package com.lyricsadapter.lyrics

/**
 * Editing word-timed (QRC/YRC) lyrics as desktop's `wordLyricsEditing.ts`
 * does: people edit the timed body directly, and a QRC payload keeps its XML
 * envelope, so saving changes nothing but `LyricContent`.
 */
object WordLyricsEditing {
    /** The result of rebuilding a payload from edited text; [Invalid] when the text lost its word timing. */
    sealed interface Result {
        /** [wordLyrics] is the payload to store, [plainText] the lines without timing. */
        data class Valid(val wordLyrics: String, val plainText: String) : Result
        data object Invalid : Result
    }

    /** The text shown in the editor for a stored payload. */
    fun editableText(wordLyrics: String, format: WordLyricsFormat): String =
        if (format == WordLyricsFormat.QRC) LyricsParser.extractQrcContent(wordLyrics) else wordLyrics

    /** Rebuilds the payload; it never silently turns karaoke lyrics into plain ones. */
    fun apply(text: String, format: WordLyricsFormat, original: String?): Result {
        val wordLyrics = if (format == WordLyricsFormat.QRC) wrapQrcContent(text, original) else text
        val parsed = LyricsParser.parse("", wordLyrics, format)
        return if (parsed.hasWordTiming) Result.Valid(wordLyrics, parsed.plainText) else Result.Invalid
    }

    private fun wrapQrcContent(text: String, original: String?): String {
        val encoded = encodeXmlAttribute(text)
        if (original != null && QRC_LYRIC_CONTENT.containsMatchIn(original)) {
            // Rebuilt from groups, so `$` in lyrics stays literal.
            return QRC_LYRIC_CONTENT.replace(original) { it.groupValues[1] + encoded + it.groupValues[2] }
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<QrcInfos>\n" +
            "<QrcHeadInfo SaveTime=\"0\" Version=\"100\"/>\n<LyricInfo LyricCount=\"1\">\n" +
            "<Lyric_1 LyricType=\"1\" LyricContent=\"$encoded\"/>\n</LyricInfo>\n</QrcInfos>"
    }

    private fun encodeXmlAttribute(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private val QRC_LYRIC_CONTENT = Regex("(LyricContent\\s*=\\s*\")[\\s\\S]*?(\"\\s*/>)", RegexOption.IGNORE_CASE)
}
