package com.lyricsadapter.app.ui.focus

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.lyricsadapter.app.R

/**
 * Focus mode's Latin type. Desktop draws it in Apple's SF Pro, which may not
 * be shipped in an app; Inter (SIL Open Font License, `assets/licenses`) is
 * drawn in the same style. Like SF, it has a display cut for large text and
 * a text cut for small text, chosen here through its optical-size axis.
 * Characters Inter lacks, such as Chinese, fall back to the system font.
 */
internal object FocusFonts {
    /** Lyrics and titles of 20sp and up (SF Pro Display). */
    val display: FontFamily = inter(opticalSize = 32f)

    /** Smaller text: artist, album, translations, times (SF Pro Text). */
    val text: FontFamily = inter(opticalSize = 14f)

    @OptIn(ExperimentalTextApi::class)
    private fun inter(opticalSize: Float) = FontFamily(
        listOf(400, 500, 600, 700, 800).map { weight ->
            Font(
                R.font.inter_variable,
                FontWeight(weight),
                variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", opticalSize)),
            )
        },
    )
}
