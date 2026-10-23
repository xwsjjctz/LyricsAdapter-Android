package com.lyricsadapter.app.ui

/** `3:07`, as track rows show durations. */
fun formatDuration(milliseconds: Long): String {
    val seconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** `1 小时 12 分钟` or `48 分钟`, for a whole list. */
fun formatTotalDuration(milliseconds: Long): String {
    val minutes = (milliseconds / 60_000).coerceAtLeast(0)
    return if (minutes >= 60) "${minutes / 60} 小时 ${minutes % 60} 分钟" else "$minutes 分钟"
}
