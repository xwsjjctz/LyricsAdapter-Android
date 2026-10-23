package com.lyricsadapter.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The list follows desktop's Apple Music style: plain surface, grey secondary text, system blue for what plays. */
@Immutable
data class AppColors(
    val background: Color,
    val surface: Color,
    val text: Color,
    val secondaryText: Color,
    val divider: Color,
    val accent: Color,
    val onAccent: Color,
)

private val Light = AppColors(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF2F2F7),
    text = Color(0xFF000000),
    secondaryText = Color(0xFF8E8E93),
    divider = Color(0x1F000000),
    accent = Color(0xFF007AFF),
    onAccent = Color.White,
)

private val Dark = AppColors(
    background = Color(0xFF1E1E1E),
    surface = Color(0xFF2C2C2E),
    text = Color(0xFFFFFFFF),
    secondaryText = Color(0xFF98989F),
    divider = Color(0x29FFFFFF),
    accent = Color(0xFF0A84FF),
    onAccent = Color.White,
)

val LocalAppColors = staticCompositionLocalOf { Light }

@Composable
fun LyricsAdapterTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) Dark else Light
    CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.accent.copy(alpha = 0.3f)),
        content = content,
    )
}
