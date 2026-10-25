package com.lyricsadapter.app.ui.focus

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsadapter.app.R
import com.lyricsadapter.app.playback.PlaybackMode
import com.lyricsadapter.app.ui.formatDuration

/*
 * Desktop's portrait controls (`FocusPortraitControls.tsx` and the
 * `.focus-portrait-*` rules in `FocusModeLayout.css`): progress, transport
 * and a volume row in a 210dp block, with Material Symbols Rounded glyphs.
 */
internal val FOCUS_CONTROLS_HEIGHT = 210.dp
internal val FOCUS_CONTROLS_BOTTOM = 24.dp
private val MAX_WIDTH = 480.dp
private val SIDE_MARGIN = 20.dp
private val ROW_GAP = 12.dp
private val PROGRESS_ROW = 62.dp
private val VOLUME_ROW = 44.dp
private val HIT_SIZE = 44.dp

private val TRACK_COLOR = Color.White.copy(alpha = 0.24f)
private val FILL_COLOR = Color.White.copy(alpha = 0.86f)
private val TRANSLATE_COLOR = Color.White.copy(alpha = 0.72f)

/** macOS system blue (dark appearance), which desktop uses for a translated lyric. */
private val TRANSLATED_COLOR = Color(0xFF0A84FF)

private val TIME_STYLE = TextStyle(
    color = Color.White.copy(alpha = 0.55f),
    fontSize = 12.sp,
    lineHeight = 18.sp,
    fontWeight = FontWeight.SemiBold,
    fontFamily = FocusFonts.text,
    fontFeatureSettings = "tnum",
)

/** Where a lyric translation by a plugin stands; the button only shows once a plugin can translate. */
enum class TranslationStatus { Unavailable, Idle, Translating, Translated, Error }

@Composable
internal fun FocusControls(
    position: () -> Long,
    durationMs: Long,
    isPlaying: Boolean,
    mode: PlaybackMode,
    volume: Float,
    translation: TranslationStatus,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onCyclePlaybackMode: () -> Unit,
    onToggleTranslation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .widthIn(max = MAX_WIDTH + SIDE_MARGIN * 2)
            .fillMaxWidth()
            .padding(horizontal = SIDE_MARGIN)
            .height(FOCUS_CONTROLS_HEIGHT),
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        ProgressRow(position, durationMs, onSeek, Modifier.height(PROGRESS_ROW))
        Row(
            Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconControl(R.drawable.tp_backward, stringResource(R.string.action_previous), 42.dp, onClick = onPrevious)
            IconControl(
                icon = if (isPlaying) R.drawable.tp_pause else R.drawable.tp_play,
                label = stringResource(if (isPlaying) R.string.action_pause else R.string.action_play),
                glyph = 58.dp,
                onClick = onTogglePlay,
            )
            IconControl(R.drawable.tp_forward, stringResource(R.string.action_next), 42.dp, onClick = onNext)
        }
        VolumeRow(volume, mode, translation, onVolumeChange, onToggleMute, onCyclePlaybackMode, onToggleTranslation, Modifier.height(VOLUME_ROW))
    }
}

@Composable
private fun ProgressRow(position: () -> Long, durationMs: Long, onSeek: (Long) -> Unit, modifier: Modifier) {
    // While dragging, the bar and the times preview the drag; the seek happens on release.
    var preview by remember { mutableStateOf<Float?>(null) }
    val fraction = { preview ?: if (durationMs > 0) (position().toFloat() / durationMs).coerceIn(0f, 1f) else 0f }
    val shownSeconds by remember(durationMs) { derivedStateOf { (fraction() * durationMs).toLong() / 1000 } }
    Column(modifier.fillMaxWidth()) {
        PlayerSlider(
            value = fraction,
            label = stringResource(R.string.playback_progress),
            valueText = "${formatDuration(shownSeconds * 1000)} / ${formatDuration(durationMs)}",
            onChange = { preview = it },
            onCommit = {
                preview = null
                onSeek((it * durationMs).toLong())
            },
        )
        Row(Modifier.fillMaxWidth()) {
            BasicText(formatDuration(shownSeconds * 1000), style = TIME_STYLE)
            Spacer(Modifier.weight(1f))
            BasicText("−" + formatDuration((durationMs - shownSeconds * 1000).coerceAtLeast(0)), style = TIME_STYLE)
        }
    }
}

@Composable
private fun VolumeRow(
    volume: Float,
    mode: PlaybackMode,
    translation: TranslationStatus,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onCyclePlaybackMode: () -> Unit,
    onToggleTranslation: () -> Unit,
    modifier: Modifier,
) {
    val translatable = translation != TranslationStatus.Unavailable
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (translatable) TranslateButton(translation, onToggleTranslation)
        val muted = volume == 0f
        IconControl(
            icon = if (muted) R.drawable.tp_volume_off else R.drawable.tp_volume_down,
            label = stringResource(if (muted) R.string.action_unmute else R.string.action_mute),
            glyph = 22.dp,
            onClick = onToggleMute,
        )
        PlayerSlider(
            value = { volume },
            label = stringResource(R.string.volume),
            valueText = "${(volume * 100).toInt()}%",
            onChange = onVolumeChange,
            onCommit = onVolumeChange,
            modifier = Modifier.weight(1f),
        )
        // With the translate button on the left, the speaker takes a button's width so the slider stays centred.
        Box(if (translatable) Modifier.size(HIT_SIZE) else Modifier, contentAlignment = Alignment.Center) {
            Glyph(R.drawable.tp_volume_up, 24.dp, Color.White)
        }
        IconControl(
            icon = when (mode) {
                PlaybackMode.Order -> R.drawable.tp_repeat
                PlaybackMode.Shuffle -> R.drawable.tp_shuffle
                PlaybackMode.RepeatOne -> R.drawable.tp_repeat_one
            },
            label = stringResource(
                when (mode) {
                    PlaybackMode.Order -> R.string.mode_order
                    PlaybackMode.Shuffle -> R.string.mode_shuffle
                    PlaybackMode.RepeatOne -> R.string.mode_repeat_one
                },
            ),
            glyph = 22.dp,
            onClick = onCyclePlaybackMode,
        )
    }
}

/** Desktop's translate toggle: a plain glyph that turns blue once translated and spins while working. */
@Composable
private fun TranslateButton(status: TranslationStatus, onToggle: () -> Unit) {
    val label = stringResource(
        when (status) {
            TranslationStatus.Translating -> R.string.translating
            TranslationStatus.Translated -> R.string.translated
            TranslationStatus.Error -> R.string.translate_retry
            else -> R.string.translate
        },
    )
    val busy = status == TranslationStatus.Translating
    Box(
        Modifier
            .size(HIT_SIZE)
            .semantics { contentDescription = label }
            .clickable(enabled = !busy, role = Role.Button, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) Spinner(18.dp, TRANSLATE_COLOR)
        else Glyph(R.drawable.ms_translate, 18.dp, if (status == TranslationStatus.Translated) TRANSLATED_COLOR else TRANSLATE_COLOR)
    }
}

/**
 * A knobless bar in a 44dp hit area: 5dp tall, growing to 10dp and turning
 * white while held. While held it shows the finger's position rather than
 * [value], which may lag or move in steps; [onChange] follows the finger and
 * [onCommit] gets the value on release. [value] is read only while drawing.
 */
@Composable
private fun PlayerSlider(
    value: () -> Float,
    label: String,
    valueText: String,
    onChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var held by remember { mutableStateOf<Float?>(null) }
    val grow by animateFloatAsState(if (held != null) 1f else 0f, tween(160), label = "slider")
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    Box(
        modifier
            .fillMaxWidth()
            .height(HIT_SIZE)
            .semantics {
                contentDescription = label
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(value(), 0f..1f)
                setProgress { target ->
                    commit(target)
                    true
                }
            }
            .pointerInput(Unit) {
                fun fractionAt(x: Float) = (x / size.width).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var fraction = fractionAt(down.position.x)
                    held = fraction
                    change(fraction)
                    while (true) {
                        val pointer = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) break
                        if (pointer.positionChanged()) {
                            fraction = fractionAt(pointer.position.x)
                            held = fraction
                            change(fraction)
                            pointer.consume()
                        }
                    }
                    held = null
                    commit(fraction)
                }
            }
            .drawBehind {
                val barHeight = (5 + 5 * grow).dp.toPx()
                val top = (size.height - barHeight) / 2
                val radius = CornerRadius(minOf(8.dp.toPx(), barHeight / 2))
                drawRoundRect(TRACK_COLOR, Offset(0f, top), Size(size.width, barHeight), radius)
                clipRect(right = size.width * (held ?: value())) {
                    drawRoundRect(lerp(FILL_COLOR, Color.White, grow), Offset(0f, top), Size(size.width, barHeight), radius)
                }
            },
    )
}

/** A white glyph in a button of at least 44dp that shrinks a little while pressed. */
@Composable
private fun IconControl(icon: Int, label: String, glyph: Dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 1f else 0f, spring(stiffness = 700f), label = "press")
    Box(
        Modifier
            .defaultMinSize(HIT_SIZE, HIT_SIZE)
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .graphicsLayer {
                val scale = 1f - 0.1f * press
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center,
    ) {
        Glyph(icon, glyph, Color.White)
    }
}

@Composable
private fun Glyph(icon: Int, size: Dp, tint: Color) {
    Image(painterResource(icon), contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = Modifier.size(size))
}

@Composable
private fun Spinner(size: Dp, color: Color) {
    val turn by rememberInfiniteTransition(label = "spinner").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "turn",
    )
    Box(
        Modifier.size(size).drawBehind {
            val stroke = 2.dp.toPx()
            rotate(turn) {
                drawArc(color, 0f, 270f, useCenter = false, topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(this.size.width - stroke, this.size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
            }
        },
    )
}
