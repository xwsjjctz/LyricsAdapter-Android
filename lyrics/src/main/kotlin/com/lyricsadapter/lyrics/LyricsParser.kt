package com.lyricsadapter.lyrics

/**
 * Parses LRC, QRC and YRC into [ParsedLyrics] the way desktop's
 * `src/shared/lrcParser.ts` does. Desktop delegates to AMLL, which is
 * AGPL-licensed, so this is written from the formats and checked against
 * desktop's output (lyrics/fixtures/expected.json) rather than ported.
 */
object LyricsParser {

    /** Word lyrics win when they carry word timing; otherwise [lyrics] is read as LRC or plain text. */
    fun parse(lyrics: String, wordLyrics: String? = null, format: WordLyricsFormat? = null): ParsedLyrics {
        if (!wordLyrics.isNullOrEmpty() && format != null) {
            val words = when (format) {
                WordLyricsFormat.QRC -> parseQrc(extractQrcContent(wordLyrics))
                WordLyricsFormat.YRC -> parseYrc(wordLyrics)
            }
            val parsed = toParsed(words, wordLyrics)
            if (parsed.hasWordTiming) return parsed
        }
        return parseLrc(lyrics)
    }

    fun parseLrc(lrc: String): ParsedLyrics {
        val lines = lrc.lineSequence().flatMap(::lrcLines).toList().sortedBy { it.startMs }
        return toParsed(lines, lrc)
    }

    /** The `LyricContent` payload of a decrypted QRC document, or the input itself. */
    fun extractQrcContent(raw: String): String {
        // Apostrophes are lyric content, so the value ends at the closing `"/>`.
        val content = QRC_CONTENT.find(raw)?.groupValues?.get(1) ?: raw
        return decodeXml(content)
    }

    private fun lrcLines(line: String): Sequence<LyricLine> {
        val tags = mutableListOf<Long>()
        var rest = line
        while (true) {
            val tag = LRC_TIME_TAG.matchAt(rest, 0) ?: break
            tags += tagMillis(tag)
            rest = rest.substring(tag.range.last + 1)
        }
        val text = unwrapParentheses(rest.trim())
        if (tags.isEmpty() || text.isEmpty()) return emptySequence()
        return tags.asSequence().map { LyricLine(it, text) }
    }

    /** `[m:s]`, `[m:s.fff]` and the three-part `[h:m:s]`; the fraction counts to milliseconds. */
    private fun tagMillis(tag: MatchResult): Long {
        val (first, second, third, fraction) = tag.destructured
        val (hours, minutes, seconds) = if (third.isEmpty()) Triple(0L, first.toLong(), second.toLong()) else Triple(first.toLong(), second.toLong(), third.toLong())
        val millis = fraction.take(3).padEnd(3, '0').toLong()
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + millis
    }

    /** A line wholly wrapped in one pair of parentheses (a backing vocal) loses that pair. */
    private fun unwrapParentheses(text: String): String {
        if (text.length <= 2) return text
        val opens = text.first() == '(' || text.first() == '（'
        val closes = text.last() == ')' || text.last() == '）'
        return if (opens && closes) text.substring(1, text.length - 1) else text
    }

    private fun parseQrc(content: String): List<LyricLine> =
        content.lineSequence().mapNotNull { line ->
            val header = WORD_LINE_HEADER.matchAt(line, 0) ?: return@mapNotNull null
            val body = line.substring(header.range.last + 1)
            val tokens = QRC_WORD.findAll(body).map { match ->
                val (text, start, duration) = match.destructured
                LyricWord(start.toLong(), duration.toLong(), text)
            }.toList()
            wordLine(header.groupValues[1].toLong(), tokens)
        }.toList()

    private fun parseYrc(content: String): List<LyricLine> =
        content.lineSequence().mapNotNull { line ->
            val header = WORD_LINE_HEADER.matchAt(line, 0) ?: return@mapNotNull null
            val body = line.substring(header.range.last + 1)
            val tokens = YRC_WORD.findAll(body).map { match ->
                val (start, duration, text) = match.destructured
                LyricWord(start.toLong(), duration.toLong(), text)
            }.toList()
            wordLine(header.groupValues[1].toLong(), tokens)
        }.toList()

    private fun wordLine(startMs: Long, tokens: List<LyricWord>): LyricLine? {
        if (tokens.isEmpty()) return null
        return LyricLine(startMs, tokens.joinToString("") { it.text }, foldSeparators(tokens))
    }

    /**
     * QQ stores the spaces between English words as zero-length tokens; they
     * join the previous timed word, or the next one at the start of a line.
     */
    private fun foldSeparators(tokens: List<LyricWord>): List<LyricWord> {
        val words = mutableListOf<LyricWord>()
        var prefix = ""
        for (token in tokens) {
            if (token.text.isEmpty()) continue
            if (token.durationMs <= 0) {
                val previous = words.lastOrNull()
                if (previous != null) words[words.lastIndex] = previous.copy(text = previous.text + token.text) else prefix += token.text
                continue
            }
            words += token.copy(text = prefix + token.text)
            prefix = ""
        }
        if (prefix.isNotEmpty() && words.isNotEmpty()) {
            words[words.lastIndex] = words.last().let { it.copy(text = it.text + prefix) }
        }
        return words
    }

    /** Desktop's `fromAmlLines`: drop blank lines; without timed lines, fall back to cleaned text. */
    private fun toParsed(lines: List<LyricLine>, source: String): ParsedLyrics {
        val synced = lines.filter { line -> line.text.isNotEmpty() && line.text.isNotBlank() }
        val plainText = if (synced.isNotEmpty()) synced.joinToString("\n") { it.text } else plainText(source)
        return ParsedLyrics(plainText, synced)
    }

    private fun plainText(source: String): String =
        source.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !LRC_HEADER_TAG.containsMatchIn(it) }
            .joinToString("\n")

    private fun decodeXml(value: String): String = value
        .replace(Regex("&#(\\d+);")) { String(Character.toChars(it.groupValues[1].toInt())) }
        .replace(Regex("&#x([\\da-f]+);", RegexOption.IGNORE_CASE)) { String(Character.toChars(it.groupValues[1].toInt(16))) }
        .replace(Regex("&quot;", RegexOption.IGNORE_CASE), "\"")
        .replace(Regex("&apos;", RegexOption.IGNORE_CASE), "'")
        .replace(Regex("&lt;", RegexOption.IGNORE_CASE), "<")
        .replace(Regex("&gt;", RegexOption.IGNORE_CASE), ">")
        .replace(Regex("&amp;", RegexOption.IGNORE_CASE), "&")

    private val QRC_CONTENT = Regex("LyricContent\\s*=\\s*\"([\\s\\S]*?)\"\\s*/>", RegexOption.IGNORE_CASE)
    private val LRC_TIME_TAG = Regex("\\[(\\d+):(\\d+)(?::(\\d+))?(?:\\.(\\d+))?]")
    private val LRC_HEADER_TAG = Regex("^\\[(ti|ar|al|by|offset|re|ve|length|sign):", RegexOption.IGNORE_CASE)
    private val WORD_LINE_HEADER = Regex("\\[(\\d+),(\\d+)]")
    private val QRC_WORD = Regex("(.*?)\\((\\d+),(\\d+)\\)")
    private val YRC_WORD = Regex("\\((\\d+),(\\d+),\\d+\\)((?:(?!\\(\\d+,\\d+,\\d+\\)).)*)")
}
