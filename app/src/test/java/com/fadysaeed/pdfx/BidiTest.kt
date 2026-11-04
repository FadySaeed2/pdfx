package com.fadysaeed.pdfx

import com.fadysaeed.pdfx.engine.Bidi
import org.junit.Assert.assertEquals
import org.junit.Test

/** Arabic lines put in reading order, whichever order the PDF stored the letters in. */
class BidiTest {
    private fun line(stored: String, xs: List<Float>): String =
        Bidi.logical(stored, BooleanArray(stored.length), BooleanArray(stored.length), xs.toFloatArray()).text

    @Test
    fun aLineStoredAsDrawnLeftToRightReadsRightToLeft() {
        // "عدد 40 مريض" drawn from the left: the last word first, its letters backwards, the number as it is.
        val stored = "ضيرم 40 ددع"
        assertEquals("عدد 40 مريض", line(stored, stored.indices.map { it.toFloat() }))
    }

    @Test
    fun aLineStoredInReadingOrderStaysAsItIs() {
        val stored = "عدد 40 مريض"
        // Drawn right to left, the number's digits left to right inside it.
        val xs = listOf(10f, 9f, 8f, 7f, 5f, 6f, 4f, 3f, 2f, 1f, 0f)
        assertEquals(stored, line(stored, xs))
    }

    @Test
    fun aLatinWordInsideStaysTheRightWayRound() {
        // "ملف PDF جديد" drawn from the left: "ديدج", "PDF", "فلم".
        val stored = "ديدج PDF فلم"
        assertEquals("ملف PDF جديد", line(stored, stored.indices.map { it.toFloat() }))
    }

    @Test
    fun shapedLettersBecomeLetters() {
        // مرحبا as presentation forms, in reading order, drawn right to left.
        val stored = "ﻣﺮﺣﺒﺎ"
        assertEquals("مرحبا", line(stored, listOf(4f, 3f, 2f, 1f, 0f)))
    }

    @Test
    fun anEnglishLineIsLeftAlone() {
        val stored = "Lithium levels, 12 hours"
        assertEquals(stored, line(stored, stored.indices.map { (stored.length - it).toFloat() }))
    }

    @Test
    fun shortVowelsStayWithTheirLetters() {
        // كَتَبَ drawn from the left with each mark straight after its letter.
        val stored = "بَتَكَ"
        val xs = listOf(0f, 0f, 1f, 1f, 2f, 2f)
        assertEquals("كَتَبَ", line(stored, xs))
    }
}

/** Pieces of one printed line, read as separate lines or blocks, joined in reading order. */
class BaselinesTest {
    private fun line(t: String, x0: Float, x1: Float, y0: Float = 100f, y1: Float = 112f) =
        com.fadysaeed.pdfx.engine.RawLine(t, BooleanArray(t.length), BooleanArray(t.length), 12f, y0, y1, x0, x1)
    private fun block(vararg ls: com.fadysaeed.pdfx.engine.RawLine) =
        com.fadysaeed.pdfx.engine.RawBlock(com.fadysaeed.pdfx.engine.Box(ls.minOf { it.x0 }, ls.minOf { it.y0 }, ls.maxOf { it.x1 }, ls.maxOf { it.y1 }), ls.toList(), false)

    @Test
    fun arabicBlocksSideBySideJoinRightToLeft() {
        // Drawn from the left: "مريض" then "عدد", each a block of its own.
        val out = com.fadysaeed.pdfx.engine.joinBaselines(listOf(block(line("مريض", 0f, 40f)), block(line("عدد", 50f, 80f))))
        assertEquals(1, out.size)
        assertEquals("عدد مريض", out[0].lines.single().text)
    }

    @Test
    fun piecesOfALineInOneBlockJoin() {
        val out = com.fadysaeed.pdfx.engine.joinBaselines(listOf(block(line("مريض", 0f, 40f), line("عدد", 50f, 80f), line("سطر تاني", 0f, 80f, 130f, 142f))))
        assertEquals(listOf("عدد مريض", "سطر تاني"), out.single().lines.map { it.text })
    }

    @Test
    fun twoColumnsStayApart() {
        val out = com.fadysaeed.pdfx.engine.joinBaselines(listOf(block(line("العمود الأول", 0f, 200f)), block(line("العمود الثاني", 320f, 520f))))
        assertEquals(2, out.size)
    }

    @Test
    fun englishLinesAreLeftAsTheyAre() {
        val b = block(line("First line", 0f, 200f), line("Second line", 0f, 200f, 130f, 142f))
        val out = com.fadysaeed.pdfx.engine.joinBaselines(listOf(b))
        assertEquals(listOf("First line", "Second line"), out.single().lines.map { it.text })
    }
}
