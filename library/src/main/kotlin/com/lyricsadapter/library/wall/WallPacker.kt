package com.lyricsadapter.library.wall

/**
 * Desktop's wide poster wall (`wallPacker.ts`), for windows from 720 px.
 *
 * The wall is a grid of cells half a small cover wide, read in bands six
 * cells tall. Each band lines up designed blocks: one or more hero blocks (a
 * large 4x4 cover with medium 3x3 or small 2x2 covers around it) and filler
 * blocks of medium or small covers, now and then a pair of 4:3 covers. Blocks
 * are mirrored and flipped by a hash of the band, so the rhythm varies without
 * gaps. A band depends only on its index and the column count, so full bands
 * never change as songs are added; songs short of the next band form a tail of
 * one large cover with small covers beside and below it.
 */
object WallPacker {
    /** Grid cells per small cover. */
    const val CELLS_PER_UNIT = 2
    const val BAND_ROWS = 6

    private const val SMALL = 2
    private const val LARGE = 4
    private const val WIDE_PAIR_SHARE = 0.3
    private const val CELLS_PER_HERO = 11
    private const val NARROW_COLUMNS = 12

    private class Block(val width: Int, val slots: List<WallSlot>)

    private val HERO_MEDIUM = Block(
        7,
        listOf(WallSlot(0, 0, 4, 4), WallSlot(4, 0, 3, 3), WallSlot(4, 3, 3, 3), WallSlot(0, 4, 2, 2), WallSlot(2, 4, 2, 2)),
    )
    private val HERO_SMALL = Block(
        6,
        listOf(
            WallSlot(0, 0, 4, 4), WallSlot(4, 0, 2, 2), WallSlot(4, 2, 2, 2),
            WallSlot(0, 4, 2, 2), WallSlot(2, 4, 2, 2), WallSlot(4, 4, 2, 2),
        ),
    )
    private val FILLERS = mapOf(
        6 to Block(6, listOf(WallSlot(0, 0, 3, 3), WallSlot(3, 0, 3, 3), WallSlot(0, 3, 3, 3), WallSlot(3, 3, 3, 3))),
        4 to Block(4, listOf(WallSlot(0, 0, 4, 3), WallSlot(0, 3, 4, 3))),
        3 to Block(3, listOf(WallSlot(0, 0, 3, 3), WallSlot(0, 3, 3, 3))),
        2 to Block(2, listOf(WallSlot(0, 0, 2, 2), WallSlot(0, 2, 2, 2), WallSlot(0, 4, 2, 2))),
    )
    private val SQUARE_FILLER_WIDTHS = listOf(6, 3, 2)

    /** Packs [count] covers into [units] small-cover columns; slots are in cells, in list order. */
    fun pack(count: Int, units: Int): List<WallSlot> {
        val columns = units * CELLS_PER_UNIT
        val slots = ArrayList<WallSlot>(count)
        var band = 0
        while (slots.size < count) {
            val next = bandSlots(band, columns)
            val remaining = count - slots.size
            if (remaining < next.size) {
                slots += tailSlots(remaining, band * BAND_ROWS, columns)
                break
            }
            slots += next
            band++
        }
        return slots
    }

    fun heroesForBand(band: Int, columns: Int): Int =
        if (columns < NARROW_COLUMNS && band % 2 == 1) 0 else maxOf(1, Math.round(columns.toDouble() / CELLS_PER_HERO).toInt())

    /** Desktop's stable pseudo-random value in [0, 1) (murmur3 finaliser on 32-bit ints). */
    internal fun hash(value: Int): Double {
        var x = (value + 1) * 0x9e3779b1.toInt()
        x = x xor (x ushr 16)
        x *= 0x85ebca6b.toInt()
        x = x xor (x ushr 13)
        x *= 0xc2b2ae35.toInt()
        x = x xor (x ushr 16)
        return x.toUInt().toDouble() / 4_294_967_296.0
    }

    private fun Block.mirrored() = Block(width, slots.map { it.copy(x = width - it.x - it.cols) })

    private fun Block.flipped() = Block(width, slots.map { it.copy(y = BAND_ROWS - it.y - it.rows) })

    /** Filler widths summing to [rest], largest first; null when impossible. */
    private fun fillerWidths(rest: Int, withWidePair: Boolean): List<Int>? {
        if (rest < 0 || rest == 1) return null
        if (withWidePair && rest >= 4) {
            fillerWidths(rest - 4, false)?.let { return listOf(4) + it }
        }
        val widths = mutableListOf<Int>()
        var left = rest
        for (width in SQUARE_FILLER_WIDTHS) {
            // Keep a remainder the smaller fillers can still cover (never exactly 1).
            while (left >= width && left - width != 1) {
                widths += width
                left -= width
            }
        }
        return if (left == 0) widths else null
    }

    private fun bandBlocks(band: Int, columns: Int): List<Block> {
        val withWidePair = hash(band * 17 + 3) < WIDE_PAIR_SHARE
        val wanted = heroesForBand(band, columns)
        for (heroes in wanted downTo minOf(wanted, 1)) {
            val picks = List(heroes) { i -> if (hash(band * 31 + i) < 0.5) HERO_MEDIUM else HERO_SMALL }
            // Swapping one hero's width shifts the remainder by one, so some choice always fits.
            for (swap in -1 until heroes) {
                val chosen = picks.mapIndexed { i, hero -> if (i != swap) hero else if (hero === HERO_MEDIUM) HERO_SMALL else HERO_MEDIUM }
                val widths = fillerWidths(columns - chosen.sumOf { it.width }, withWidePair) ?: continue
                val rotate = (hash(band * 7) * widths.size).toInt()
                val blocks = (widths.drop(rotate) + widths.take(rotate)).map { FILLERS.getValue(it) }.toMutableList()
                // Spread heroes across the band, shifting between neighbouring bands.
                val offset = if (band % 2 == 0) 0.2 else 0.7
                chosen.forEachIndexed { i, hero ->
                    val at = Math.round((i + offset) / maxOf(heroes, 1) * blocks.size).toInt()
                    blocks.add(minOf(at + i, blocks.size), hero)
                }
                return blocks.mapIndexed { i, block ->
                    var shaped = if (hash(band * 101 + i) < 0.5) block.mirrored() else block
                    if (hash(band * 211 + i) < 0.35) shaped = shaped.flipped()
                    shaped
                }
            }
        }
        error("Poster wall cannot fill $columns columns")
    }

    private fun bandSlots(band: Int, columns: Int): List<WallSlot> {
        val slots = ArrayList<WallSlot>()
        var x = 0
        for (block in bandBlocks(band, columns)) {
            block.slots.forEach { slots += it.copy(x = it.x + x, y = it.y + band * BAND_ROWS) }
            x += block.width
        }
        return slots.sortedWith(compareBy({ it.y }, { it.x }))
    }

    /** Tail songs: a large cover, then small covers to its right and on rows below. */
    private fun tailSlots(count: Int, top: Int, columns: Int): List<WallSlot> {
        val slots = ArrayList<WallSlot>(count)
        val withLarge = count >= 3 && columns >= LARGE + SMALL
        if (withLarge) slots += WallSlot(0, top, LARGE, LARGE)
        var x = if (withLarge) LARGE else 0
        var y = top
        while (slots.size < count) {
            if (x + SMALL > columns) {
                y += SMALL
                x = if (withLarge && y < top + LARGE) LARGE else 0
            }
            slots += WallSlot(x, y, SMALL, SMALL)
            x += SMALL
        }
        return slots
    }
}
