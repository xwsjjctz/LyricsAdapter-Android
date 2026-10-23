package com.lyricsadapter.library.wall

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WallLayoutTest {
    @Test
    fun `gives one slot per song`() {
        for (count in 0..40) assertEquals(count, WallLayout.slots(count).size)
    }

    @Test
    fun `tiles full blocks exactly, without overlaps or gaps`() {
        val slots = WallLayout.slots(14)
        val cells = slots.flatMap { slot -> (slot.x until slot.x + slot.cols).flatMap { x -> (slot.y until slot.y + slot.rows).map { y -> x to y } } }
        assertEquals(cells.size, cells.toSet().size, "no cell is covered twice")
        assertEquals(WallLayout.COLUMNS * WallLayout.BLOCK_ROWS * 2, cells.size, "two full blocks")
        assertTrue(slots.all { it.x >= 0 && it.x + it.cols <= WallLayout.COLUMNS })
    }

    @Test
    fun `mirrors every second block, as desktop does`() {
        val slots = WallLayout.slots(14)
        assertEquals(WallSlot(0, 0, 2, 2), slots[0])
        assertEquals(WallSlot(0, 8, 2, 4), slots[7])
    }

    @Test
    fun `keeps earlier songs in place when songs are added`() {
        assertEquals(WallLayout.slots(7), WallLayout.slots(10).take(7))
        assertEquals(listOf(WallSlot(0, 0, 2, 2)), WallLayout.slots(1))
        assertEquals(listOf(WallSlot(0, 0, 4, 4), WallSlot(4, 0, 2, 4)), WallLayout.slots(2))
    }
}
