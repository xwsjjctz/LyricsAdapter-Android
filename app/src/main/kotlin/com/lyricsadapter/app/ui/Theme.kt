package com.lyricsadapter.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.lyricsadapter.app.data.ColorMode

/** The list follows desktop's Apple Music style: plain surface, grey secondary text, system blue for what plays. */
@Immutable
data class AppColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    /** Menus and sheets floating over the page. */
    val elevated: Color,
    val text: Color,
    val secondaryText: Color,
    val divider: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
    /** Dims the page behind a sheet. */
    val scrim: Color,
)

private val Light = AppColors(
    isDark = false,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF2F2F7),
    elevated = Color(0xFFFFFFFF),
    text = Color(0xFF000000),
    secondaryText = Color(0xFF8E8E93),
    divider = Color(0x1F000000),
    accent = Color(0xFF007AFF),
    onAccent = Color.White,
    danger = Color(0xFFFF3B30),
    scrim = Color(0x33000000),
)

private val Dark = AppColors(
    isDark = true,
    background = Color(0xFF1E1E1E),
    surface = Color(0xFF2C2C2E),
    elevated = Color(0xFF2C2C2E),
    text = Color(0xFFFFFFFF),
    secondaryText = Color(0xFF98989F),
    divider = Color(0x29FFFFFF),
    accent = Color(0xFF0A84FF),
    onAccent = Color.White,
    danger = Color(0xFFFF453A),
    scrim = Color(0x66000000),
)

val LocalAppColors = staticCompositionLocalOf { Light }

@Composable
fun LyricsAdapterTheme(colorMode: ColorMode = ColorMode.System, content: @Composable () -> Unit) {
    val dark = when (colorMode) {
        ColorMode.System -> isSystemInDarkTheme()
        ColorMode.Light -> false
        ColorMode.Dark -> true
    }
    val colors = if (dark) Dark else Light
    CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.accent.copy(alpha = 0.3f)),
        content = content,
    )
}
