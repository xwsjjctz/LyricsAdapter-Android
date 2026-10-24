package com.lyricsadapter.library.wall

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WallPackerTest {
    /** `wall-packer-desktop.json` is desktop's `packWall` output for these cases, made with esbuild and node. */
    private val desktop: List<Triple<Int, Int, List<WallSlot>>> by lazy {
        val text = checkNotNull(javaClass.getResource("/wall-packer-desktop.json")).readText()
        Regex("""\{"count":(\d+),"units":(\d+),"slots":\[(.*?)]}""").findAll(text).map { case ->
            val slots = Regex("""\[(\d+),(\d+),(\d+),(\d+)]""").findAll(case.groupValues[3]).map { slot ->
                val (x, y, cols, rows) = slot.destructured
                WallSlot(x.toInt(), y.toInt(), cols.toInt(), rows.toInt())
            }.toList()
            Triple(case.groupValues[1].toInt(), case.groupValues[2].toInt(), slots)
        }.toList()
    }

    @Test
    fun `packs exactly as desktop does`() {
        assertTrue(desktop.isNotEmpty())
        for ((count, units, slots) in desktop) assertEquals(slots, WallPacker.pack(count, units), "$count covers in $units units")
    }

    @Test
    fun `fills full bands without overlaps or gaps`() {
        for (units in 4..10) {
            val columns = units * WallPacker.CELLS_PER_UNIT
            val slots = WallPacker.pack(200, units)
            val bands = slots.filter { it.y + it.rows <= WallPacker.BAND_ROWS * 3 }
            val cells = bands.flatMap { slot -> (slot.x until slot.x + slot.cols).flatMap { x -> (slot.y until slot.y + slot.rows).map { y -> x to y } } }
            assertEquals(cells.size, cells.toSet().size, "no cell is covered twice at $units units")
            assertEquals(columns * WallPacker.BAND_ROWS * 3, cells.size, "three full bands at $units units")
        }
    }

    @Test
    fun `keeps earlier covers in place when covers are added`() {
        // 60 covers fill several bands; those bands stay the same however many covers follow.
        assertEquals(WallPacker.pack(60, 4).take(30), WallPacker.pack(90, 4).take(30))
    }
}
