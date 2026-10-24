package com.lyricsadapter.app.ui

import java.util.Locale

/** Desktop's target-language handling for lyric translation (`pluginTranslation.ts`). */
object TranslationLanguages {
    /** Offered when a provider does not declare its languages. */
    val DEFAULT = listOf("zh", "en", "ja", "ko")

    /** The preferred language if offered, else one with the same primary subtag (zh → zh-Hans), else the first. */
    fun effective(languages: List<String>, preferred: String): String {
        val wanted = preferred.lowercase()
        val primary = wanted.substringBefore('-')
        return languages.firstOrNull { it.lowercase() == wanted }
            ?: languages.firstOrNull { it.lowercase().substringBefore('-') == primary }
            ?: languages.first()
    }

    /** A language named in itself, such as 日本語 for `ja`. */
    fun displayName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }.ifEmpty { tag }
    }
}
