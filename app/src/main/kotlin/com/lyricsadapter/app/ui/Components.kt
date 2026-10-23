package com.lyricsadapter.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import com.lyricsadapter.app.R
import java.io.File

/** A tinted vector icon, decorative. */
@Composable
fun Icon(icon: Int, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Image(painterResource(icon), contentDescription = null, colorFilter = ColorFilter.tint(tint), modifier = modifier.size(size))
}

/** An icon control: named for accessibility, no tooltip. */
@Composable
fun IconButton(icon: Int, label: String, tint: Color, size: Dp = 24.dp, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(size + 20.dp)
            .clip(RoundedCornerShape(50))
            .semantics {
                contentDescription = label
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, tint, size = size)
    }
}

@Composable
fun Cover(path: String?, size: Dp, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    Box(modifier.size(size).clip(RoundedCornerShape(6.dp)).background(colors.surface)) {
        if (path != null) {
            // Local and cloud covers are files; online covers are the source's URLs.
            val model: Any = if (path.startsWith("http")) path else File(path)
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A filled capsule for the one main action of a page. */
@Composable
fun PillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = LocalAppColors.current
    BasicText(
        label,
        style = TextStyle(color = colors.onAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (enabled) colors.accent else colors.accent.copy(alpha = 0.4f))
            .semantics { role = Role.Button }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
    )
}

/** A plain text action in the accent colour, as in a navigation bar. */
@Composable
fun TextAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, bold: Boolean = false, color: Color? = null) {
    val colors = LocalAppColors.current
    val tint = color ?: colors.accent
    BasicText(
        label,
        style = TextStyle(
            color = if (enabled) tint else colors.secondaryText,
            fontSize = 17.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        ),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .semantics { role = Role.Button }
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    )
}

/** A labelled text input on a grouped surface. */
@Composable
fun LabeledField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    placeholder: String? = null,
    trailingLabel: String? = null,
    minHeight: Dp = 0.dp,
    monospace: Boolean = false,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val colors = LocalAppColors.current
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(label, style = TextStyle(color = colors.secondaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium))
            if (trailingLabel != null) {
                Spacer(Modifier.width(8.dp))
                BasicText(trailingLabel, style = TextStyle(color = colors.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium))
            }
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = TextStyle(
                color = colors.text,
                fontSize = if (monospace) 14.sp else 16.sp,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                lineHeight = if (monospace) 20.sp else TextStyle.Default.lineHeight,
            ),
            cursorBrush = SolidColor(colors.accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = when {
                password -> KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
                keyboardType != KeyboardType.Text -> KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false)
                else -> KeyboardOptions.Default
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .then(if (minHeight > 0.dp) Modifier.height(minHeight) else Modifier),
            decorationBox = { field ->
                Box {
                    if (value.isEmpty() && placeholder != null) {
                        BasicText(placeholder, style = TextStyle(color = colors.secondaryText, fontSize = 15.sp))
                    }
                    field()
                }
            },
        )
    }
}

/** One row of a [ContextMenu]; a null [icon] leaves the trailing space empty, [detail] is a count or state. */
sealed interface MenuEntry {
    data class Item(
        val label: String,
        val icon: Int? = null,
        val danger: Boolean = false,
        val detail: String? = null,
        val checked: Boolean = false,
        val onClick: () -> Unit,
    ) : MenuEntry

    data object Separator : MenuEntry
}

/**
 * A floating menu at [anchor] (window coordinates), kept inside the window,
 * in the style of a phone context menu: rounded panel, label on the left,
 * icon on the right, destructive actions in red.
 */
@Composable
fun ContextMenu(anchor: IntOffset, entries: List<MenuEntry>, onDismiss: () -> Unit) {
    val colors = LocalAppColors.current
    val margin = with(LocalDensity.current) { MENU_SHADOW_ROOM.roundToPx() }
    // Room for a checkmark only in menus that pick one of several.
    val checkable = entries.any { it is MenuEntry.Item && it.checked }
    Popup(
        popupPositionProvider = remember(anchor, margin) { AnchorPosition(anchor, margin) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                // The popup window clips what it draws, so the shadow needs room inside it.
                .padding(MENU_SHADOW_ROOM)
                .widthIn(min = 220.dp, max = 280.dp)
                .shadow(12.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(colors.elevated)
                .border(0.5.dp, colors.divider, RoundedCornerShape(14.dp)),
        ) {
            entries.forEach { entry ->
                when (entry) {
                    MenuEntry.Separator -> Box(Modifier.fillMaxWidth().height(6.dp).background(colors.surface))
                    is MenuEntry.Item -> {
                        val tint = if (entry.danger) colors.danger else colors.text
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .semantics { role = Role.Button }
                                .clickable {
                                    onDismiss()
                                    entry.onClick()
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (checkable) {
                                Box(Modifier.width(22.dp)) {
                                    if (entry.checked) Icon(R.drawable.ui_check, colors.accent, size = 18.dp)
                                }
                            }
                            BasicText(entry.label, style = TextStyle(color = tint, fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (entry.detail != null) {
                                Spacer(Modifier.width(8.dp))
                                BasicText(entry.detail, style = TextStyle(color = colors.secondaryText, fontSize = 15.sp))
                            }
                            if (entry.icon != null) {
                                Spacer(Modifier.width(12.dp))
                                Icon(entry.icon, tint, size = 20.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

private val MENU_SHADOW_ROOM = 16.dp

/** Puts the menu's top-left corner at [anchor]; [margin] is the transparent room around the panel. */
private class AnchorPosition(private val anchor: IntOffset, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchor.x - margin).coerceIn(-margin, (windowSize.width - popupContentSize.width + margin).coerceAtLeast(-margin))
        // Below the finger when it fits, otherwise above it.
        val below = anchor.y - margin
        val y = if (below + popupContentSize.height - margin <= windowSize.height) below else (anchor.y - popupContentSize.height + margin).coerceAtLeast(-margin)
        return IntOffset(x, y)
    }
}

/** A centred confirmation with a cancel and a confirm action, as desktop's `ConfirmDialog`. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    danger: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalAppColors.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.elevated)
                .padding(top = 20.dp, start = 20.dp, end = 20.dp, bottom = 8.dp),
        ) {
            BasicText(title, style = TextStyle(color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.height(8.dp))
            BasicText(message, style = TextStyle(color = colors.secondaryText, fontSize = 14.sp, lineHeight = 20.sp))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextAction(cancelLabel, onClick = onDismiss)
                TextAction(confirmLabel, onClick = {
                    onDismiss()
                    onConfirm()
                }, bold = true, color = if (danger) colors.danger else null)
            }
        }
    }
}
