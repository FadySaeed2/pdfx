package com.fadysaeed.pdfx

import com.fadysaeed.pdfx.engine.BoxSpan
import com.fadysaeed.pdfx.engine.BoxStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Words styled on their own inside a text box: written as rich text, read back, and kept in step with edits. */
class BoxSpansTest {
    private val text = "Lithium level due\nCheck renal function"

    @Test
    fun styledWordsGoIntoTheRichTextAndComeBack() {
        val style = BoxStyle(spans = listOf(BoxSpan(0, 7, bold = true), BoxSpan(24, 29, color = 0xFFC62828.toInt(), underline = true)))
        val rc = style.rich(text)
        assertTrue(rc, rc.contains("<span style=\"font-weight:bold;\">Lithium</span>"))
        assertTrue(rc, rc.contains("color:#C62828;"))
        assertEquals(style.spans, BoxStyle.spansIn(rc, text))
    }

    @Test
    fun anEmptyLineAndAPlainBoxReadBackAsNothingSpecial() {
        val t = "First\n\nThird"
        val plain = BoxStyle()
        assertEquals(emptyList<BoxSpan>(), BoxStyle.spansIn(plain.rich(t), t))
        val one = BoxStyle(spans = listOf(BoxSpan(7, 12, italic = true)))
        assertEquals(one.spans, BoxStyle.spansIn(one.rich(t), t))
    }

    @Test
    fun aWholeUnderlinedBoxIsNotReadAsUnderlinedWords() {
        val s = BoxStyle(underline = true)
        val back = s.copy(spans = BoxStyle.spansIn(s.rich(text), text)).tidySpans(text.length)
        assertEquals(emptyList<BoxSpan>(), back.spans)
    }

    @Test
    fun spansFollowEdits() {
        val spans = listOf(BoxSpan(6, 11, bold = true)) // "Hello world" -> "world" bold
        // Typing at the start pushes it along.
        assertEquals(listOf(BoxSpan(9, 14, bold = true)), BoxStyle.shift(spans, 0, 0, 3))
        // Typing straight after it carries the style on.
        assertEquals(listOf(BoxSpan(6, 13, bold = true)), BoxStyle.shift(spans, 11, 11, 13))
        // Deleting part of it shortens it.
        assertEquals(listOf(BoxSpan(6, 9, bold = true)), BoxStyle.shift(spans, 8, 10, 8))
        // Deleting all of it removes it.
        assertEquals(emptyList<BoxSpan>(), BoxStyle.shift(spans, 6, 11, 6))
    }

    @Test
    fun theFewestSpansDescribeTheLetters() {
        val a = BoxStyle.charsOf(listOf(BoxSpan(0, 3, bold = true), BoxSpan(3, 6, bold = true)), 8)
        assertEquals(listOf(BoxSpan(0, 6, bold = true)), BoxStyle.spansOf(a))
    }
}
