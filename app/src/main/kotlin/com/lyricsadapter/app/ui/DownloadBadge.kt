package com.lyricsadapter.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsadapter.app.R
import kotlin.math.roundToInt

private val BADGE_COLOR = Color(0xDB0F0D18)
private val BADGE_BORDER = Color.White.copy(alpha = 0.2f)
private val RING_TRACK = Color.White.copy(alpha = 0.2f)

/**
 * Desktop's `PosterDownloadProgress`: a small dark badge over a cover with a
 * ring that fills as the song arrives and what is happening under it. [share]
 * is 0 while the stream is being resolved, then the share downloaded, and 1
 * while the file is being tagged and saved. [compact] lays it out in a row,
 * for small covers and list rows.
 */
@Composable
fun DownloadBadge(share: Float, compact: Boolean, modifier: Modifier = Modifier) {
    val label = stringResource(
        when {
            share <= 0f -> R.string.download_preparing
            share >= 1f -> R.string.download_saving
            else -> R.string.download_downloading
        },
    )
    val percent = (share * 100).roundToInt().coerceIn(0, 100)
    val shape = RoundedCornerShape(if (compact) 12.dp else 14.dp)
    val content: @Composable () -> Unit = {
        Ring(share, if (compact) 32.dp else 44.dp, percent, transferring = share > 0f && share < 1f, compact = compact)
        BasicText(label, style = TextStyle(Color.White, fontSize = 10.sp, lineHeight = 13.sp))
    }
    Box(
        modifier
            .shadow(4.dp, shape)
            .clip(shape)
            .background(BADGE_COLOR)
            .border(1.dp, BADGE_BORDER, shape)
            .semantics {
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(share.coerceIn(0f, 1f), 0f..1f)
            }
            .then(if (compact) Modifier.padding(horizontal = 8.dp, vertical = 5.dp) else Modifier.padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 8.dp)),
    ) {
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) { content() }
        }
    }
}

@Composable
private fun Ring(share: Float, size: Dp, percent: Int, transferring: Boolean, compact: Boolean) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx() * size.toPx() / 44.dp.toPx()
            val inset = stroke / 2 + this.size.minDimension * (3f / 44f) / 2
            val arc = androidx.compose.ui.geometry.Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(RING_TRACK, 0f, 360f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(stroke))
            val sweep = if (transferring) 360f * share else 360f
            drawArc(Color.White, -90f, sweep, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        if (transferring) {
            BasicText("$percent%", style = TextStyle(Color.White, fontSize = if (compact) 10.sp else 11.sp, fontWeight = FontWeight.Bold))
        } else {
            Icon(if (share >= 1f) R.drawable.ui_save else R.drawable.ui_download, Color.White, size = 21.dp * size.value / 44f)
        }
    }
}
