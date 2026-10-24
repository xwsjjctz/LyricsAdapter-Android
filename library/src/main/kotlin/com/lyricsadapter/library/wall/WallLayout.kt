package com.lyricsadapter.library.wall

/** A cover's place on the wall, in grid cells. */
data class WallSlot(val x: Int, val y: Int, val cols: Int, val rows: Int)

/**
 * Desktop's poster wall (`wallLayout.ts`). Below 720 px it has 6 columns
 * filled from 8-row blocks whose seven covers tile the block exactly, every
 * second block mirrored. A last block with fewer songs uses a tail shaped
 * for its count, and a single song gets one small square. Slots follow list
 * order, so playing a song never moves it and adding songs only reshapes the end.
 * Wider, it has as many columns as fit about one small cover per 200 px and
 * is packed in bands by [WallPacker].
 */
object WallLayout {
    const val COLUMNS = 6
    const val BLOCK_ROWS = 8

    /** Desktop's `WALL_COMPACT_BREAKPOINT` and `WALL_TARGET_UNIT`, in dp. */
    private const val COMPACT_BREAKPOINT = 720f
    private const val TARGET_UNIT = 200f

    /** Desktop's `wallColumnsFor`: 6 when narrow, otherwise two per small cover that fits. */
    fun columnsFor(width: Float): Int =
        if (width < COMPACT_BREAKPOINT) COLUMNS else Math.round(width / TARGET_UNIT) * WallPacker.CELLS_PER_UNIT

    /** Rows per block (narrow) or band (wide): the wall is laid out, and scrolled, in these. */
    fun blockRows(columns: Int): Int = if (columns == COLUMNS) BLOCK_ROWS else WallPacker.BAND_ROWS

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

    /** One slot per song, in list order, for a wall [columns] wide. */
    fun slots(count: Int, columns: Int = COLUMNS): List<WallSlot> {
        if (count <= 0) return emptyList()
        if (columns != COLUMNS) return WallPacker.pack(count, columns / WallPacker.CELLS_PER_UNIT)
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
