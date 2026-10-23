package com.lyricsadapter.library.wall

/** A cover's place on the wall, in grid cells. */
data class WallSlot(val x: Int, val y: Int, val cols: Int, val rows: Int)

/**
 * Desktop's narrow poster wall (`wallLayout.ts`, below 720 px): 6 columns
 * filled from 8-row blocks whose seven covers tile the block exactly, every
 * second block mirrored. A last block with fewer songs uses a tail shaped
 * for its count, and a single song gets one small square. Slots follow list
 * order, so playing a song never moves it and adding songs only reshapes the end.
 */
object WallLayout {
    const val COLUMNS = 6
    const val BLOCK_ROWS = 8

    private val TEMPLATE = listOf(
        WallSlot(0, 0, 2, 2), WallSlot(2, 0, 2, 2), WallSlot(4, 0, 2, 4),
        WallSlot(0, 2, 4, 2), WallSlot(0, 4, 4, 4), WallSlot(4, 4, 2, 2), WallSlot(4, 6, 2, 2),
    )

    private val TAILS = listOf(
        listOf(WallSlot(0, 0, 6, 3)),
        listOf(WallSlot(0, 0, 4, 4), WallSlot(4, 0, 2, 4)),
        listOf(WallSlot(0, 0, 4, 2), WallSlot(4, 0, 2, 4), WallSlot(0, 2, 4, 2)),
        listOf(WallSlot(0, 0, 4, 2), WallSlot(4, 0, 2, 2), WallSlot(0, 2, 2, 2), WallSlot(2, 2, 4, 2)),
        listOf(WallSlot(0, 0, 4, 4), WallSlot(4, 0, 2, 4), WallSlot(0, 4, 2, 2), WallSlot(2, 4, 2, 2), WallSlot(4, 4, 2, 2)),
        listOf(
            WallSlot(0, 0, 4, 2), WallSlot(4, 0, 2, 2), WallSlot(0, 2, 2, 4),
            WallSlot(2, 2, 4, 2), WallSlot(2, 4, 2, 2), WallSlot(4, 4, 2, 2),
        ),
    )

    private val READING_ORDER = compareBy<WallSlot>({ it.y }, { it.x })

    /** One slot per song, in list order. */
    fun slots(count: Int): List<WallSlot> {
        if (count <= 0) return emptyList()
        if (count == 1) return listOf(WallSlot(0, 0, 2, 2))
        val slots = ArrayList<WallSlot>(count)
        var block = 0
        while (slots.size < count) {
            val remaining = count - slots.size
            val base = if (remaining < TEMPLATE.size) TAILS[remaining - 1] else TEMPLATE
            val oriented = if (block % 2 == 1) base.map { it.copy(x = COLUMNS - it.x - it.cols) } else base
            oriented.sortedWith(READING_ORDER).forEach { slots += it.copy(y = it.y + block * BLOCK_ROWS) }
            block++
        }
        return slots
    }
}
