package com.lyricsadapter.app.search

import android.icu.text.Transliterator
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop's `textMatchesQuery`: an accent- and case-insensitive substring
 * match that also accepts pinyin, spelled out ("zhoujielun") or as initials
 * ("zjl"), with spaces in the query ignored. Pinyin comes from the system's
 * ICU transliterator and is cached per text.
 */
object TextMatcher {
    private val toLatin: Transliterator by lazy { Transliterator.getInstance("Han-Latin; Latin-ASCII; Lower") }
    private val pinyinCache = ConcurrentHashMap<String, Pinyin>()

    private class Pinyin(val full: String, val initials: String)

    fun matches(text: String, query: String): Boolean {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || text.isEmpty()) return false
        if (normalize(text).contains(normalize(trimmed))) return true
        val compact = normalize(trimmed).filterNot(Char::isWhitespace)
        if (compact.isEmpty() || !compact.all { it in 'a'..'z' || it.isDigit() }) return false
        val pinyin = pinyinCache.getOrPut(text) { pinyinOf(text) }
        return pinyin.full.contains(compact) || pinyin.initials.contains(compact)
    }

    private fun pinyinOf(text: String): Pinyin {
        val syllables = toLatin.transliterate(text).split(Regex("[^a-z0-9]+")).filter(String::isNotEmpty)
        return Pinyin(full = syllables.joinToString(""), initials = syllables.joinToString("") { it.take(1) })
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase()

    private val DIACRITICS = Regex("\\p{Mn}+")
}
