package com.lyricsadapter.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsadapter.app.R
import com.lyricsadapter.app.ui.Icon
import com.lyricsadapter.app.ui.LocalAppColors
import kotlin.math.roundToInt

/** Rows on one rounded surface, with an optional title above and note below, as a grouped settings list. */
@Composable
fun SettingsGroup(title: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalAppColors.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (title != null) {
            BasicText(
                title,
                style = TextStyle(color = colors.secondaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp).semantics { heading() },
            )
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surface), content = content)
        if (footer != null) {
            BasicText(
                footer,
                style = TextStyle(color = colors.secondaryText, fontSize = 13.sp, lineHeight = 18.sp),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }
    }
}

/** A row that opens a page or runs an action; [detail] shows the current value. */
@Composable
fun SettingsRow(
    title: String,
    icon: Int? = null,
    detail: String? = null,
    chevron: Boolean = true,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalAppColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.semantics { role = Role.Button }.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, colors.accent, size = 22.dp)
            Spacer(Modifier.width(12.dp))
        }
        BasicText(
            title,
            style = TextStyle(color = if (danger) colors.danger else colors.text, fontSize = 16.sp),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail != null) {
            BasicText(detail, style = TextStyle(color = colors.secondaryText, fontSize = 15.sp), maxLines = 1)
        }
        if (chevron && onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(R.drawable.ui_chevron_right, colors.secondaryText, size = 20.dp)
        }
    }
}

/** A hairline between rows of one group, indented past the icons. */
@Composable
fun RowDivider(indent: Boolean = false) {
    val colors = LocalAppColors.current
    Box(Modifier.fillMaxWidth().padding(start = if (indent) 50.dp else 16.dp).height(0.5.dp).background(colors.divider))
}

/** A setting picked from a few options, as a segmented control. */
@Composable
fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val colors = LocalAppColors.current
    Row(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(9.dp)).background(colors.background).padding(2.dp)) {
        options.forEach { (value, label) ->
            val chosen = value == selected
            BasicText(
                label,
                style = TextStyle(
                    color = if (chosen) colors.onAccent else colors.text,
                    fontSize = 14.sp,
                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (chosen) colors.accent else colors.background)
                    .semantics {
                        role = Role.RadioButton
                        this.selected = chosen
                    }
                    .clickable { onSelect(value) }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

/** A numeric setting with its value shown, dragged in whole steps. */
@Composable
fun SliderRow(title: String, value: Int, range: IntRange, unit: String, onChange: (Int) -> Unit) {
    val colors = LocalAppColors.current
    var dragging by remember { mutableStateOf<Int?>(null) }
    val shown = dragging ?: value
    val change by rememberUpdatedState(onChange)
    val span = (range.last - range.first).coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(title, style = TextStyle(color = colors.text, fontSize = 16.sp), modifier = Modifier.weight(1f))
            BasicText("$shown$unit", style = TextStyle(color = colors.secondaryText, fontSize = 15.sp, fontFeatureSettings = "tnum"))
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .semantics {
                    contentDescription = title
                    progressBarRangeInfo = ProgressBarRangeInfo(shown.toFloat(), range.first.toFloat()..range.last.toFloat(), steps = span - 1)
                    setProgress { target ->
                        change(target.roundToInt().coerceIn(range))
                        true
                    }
                }
                .pointerInput(range) {
                    fun valueAt(x: Float) = range.first + ((x / size.width).coerceIn(0f, 1f) * span).roundToInt()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        dragging = valueAt(down.position.x)
                        while (true) {
                            val pointer = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!pointer.pressed) break
                            dragging = valueAt(pointer.position.x)
                            pointer.consume()
                        }
                        dragging?.let(change)
                        dragging = null
                    }
                }
                .drawBehind {
                    val trackHeight = 4.dp.toPx()
                    val thumb = 11.dp.toPx()
                    val usable = size.width - 2 * thumb
                    val x = thumb + usable * (shown - range.first) / span
                    val y = size.height / 2
                    drawRoundRect(colors.divider, Offset(thumb, y - trackHeight / 2), Size(usable, trackHeight), CornerRadius(trackHeight / 2))
                    drawRoundRect(colors.accent, Offset(thumb, y - trackHeight / 2), Size(x - thumb, trackHeight), CornerRadius(trackHeight / 2))
                    drawCircle(colors.elevated, thumb, Offset(x, y))
                    drawCircle(colors.divider, thumb, Offset(x, y), style = androidx.compose.ui.graphics.drawscope.Stroke(0.5.dp.toPx()))
                },
        )
    }
}

/** A short status line under a form, in red for errors. */
@Composable
fun StatusText(text: String, error: Boolean) {
    val colors = LocalAppColors.current
    BasicText(
        text,
        style = TextStyle(color = if (error) colors.danger else colors.secondaryText, fontSize = 14.sp, lineHeight = 20.sp),
        modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
    )
}

@Composable
internal fun SpacerRow() = Spacer(Modifier.size(8.dp))
