package com.fadysaeed.pdfx

import com.fadysaeed.pdfx.engine.Splits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Cutting a file into parts: page ranges as people write them, every few pages, and by size. */
class SplitsTest {
    @Test
    fun rangesAsPeopleWriteThem() {
        val (parts, why) = Splits.ranges("1-3, 4–10; 11-", 12)
        assertNull(why)
        assertEquals(listOf((0..2).toList(), (3..9).toList(), listOf(10, 11)), parts)
        assertEquals(listOf(listOf(4)), Splits.ranges(" 5 ", 12).first)
        assertEquals(listOf((0..2).toList()), Splits.ranges("-3", 12).first)
        assertNull(Splits.ranges("1-20", 12).first)
        assertTrue(Splits.ranges("1-20", 12).second!!.contains("12 pages"))
        assertNull(Splits.ranges("5-2", 12).first)
        assertNull(Splits.ranges("one", 12).first)
        assertNull(Splits.ranges("", 12).first)
    }

    @Test
    fun everyFewPages() {
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4, 5), listOf(6)), Splits.every(3, 7))
        assertEquals(7, Splits.every(1, 7).size)
        assertTrue(Splits.every(0, 7).isEmpty())
    }

    @Test
    fun labels() {
        assertEquals("page 5", Splits.label(listOf(4)))
        assertEquals("pages 1–3", Splits.label(listOf(0, 1, 2)))
        assertEquals("pages 1–2, 5", Splits.label(listOf(0, 1, 4)))
    }

    @Test
    fun bySizeKeepsEachPartUnderTheLimit() {
        // Each page "weighs" its number in kilobytes, plus 5 kB for the file itself; page 9 is too big alone.
        val weight = IntArray(12) { (it + 1) * 1000 }.also { it[8] = 90_000 }
        val tmp = File.createTempFile("size", ".pdf")
        var writes = 0
        val parts = Splits.bySize(12, 30_000, tmp) { pages, f ->
            writes++
            f.writeBytes(ByteArray(5000 + pages.sumOf { weight[it] }))
        }
        assertEquals((0 until 12).toList(), parts.flatten())
        for (p in parts) {
            val size = 5000 + p.sumOf { weight[it] }
            assertTrue("part $p is ${size}B", size <= 30_000 || p.size == 1)
        }
        assertTrue("page 9 on its own", listOf(8) in parts)
        // Greedy: a part could not take the next page as well.
        for ((a, b) in parts.zipWithNext()) assertTrue(5000 + (a + b.first()).sumOf { weight[it] } > 30_000)
        assertTrue("a few writes per part, not one per page squared: $writes", writes < 12 * 4)
    }
}
