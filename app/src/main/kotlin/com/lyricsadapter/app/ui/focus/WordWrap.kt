package com.lyricsadapter.app.ui.focus

import android.icu.text.BreakIterator
import java.util.Locale

/** U+2060 WORD JOINER: invisible, and forbids a line break where it stands. */
private const val WORD_JOINER = '⁠'

/**
 * Text prepared so a line only wraps between words, as desktop's lyrics do.
 * Latin text already breaks only at spaces; Chinese and Japanese could break
 * between any two characters, so a word joiner goes between the characters of
 * each word that ICU's dictionary finds. [offsets] maps an index in the
 * original text to the same place in [text].
 */
internal class WordWrapped(val text: String, val offsets: IntArray) {
    companion object {
        fun of(original: String): WordWrapped {
            val words = BreakIterator.getWordInstance(Locale.CHINESE).apply { setText(original) }
            val boundaries = HashSet<Int>()
            var boundary = words.first()
            while (boundary != BreakIterator.DONE) {
                boundaries += boundary
                boundary = words.next()
            }
            val text = StringBuilder(original.length + 8)
            val offsets = IntArray(original.length + 1)
            original.forEachIndexed { index, char ->
                if (index > 0 && index !in boundaries && isIdeographic(original[index - 1]) && isIdeographic(char)) text.append(WORD_JOINER)
                offsets[index] = text.length
                text.append(char)
            }
            offsets[original.length] = text.length
            return WordWrapped(text.toString(), offsets)
        }

        private fun isIdeographic(char: Char): Boolean {
            val script = Character.UnicodeScript.of(char.code)
            return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
        }
    }
}
