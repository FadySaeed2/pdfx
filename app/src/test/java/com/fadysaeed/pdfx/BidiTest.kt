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
