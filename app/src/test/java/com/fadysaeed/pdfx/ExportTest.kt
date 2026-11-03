package com.fadysaeed.pdfx

import com.fadysaeed.pdfx.engine.Block
import com.fadysaeed.pdfx.engine.Box
import com.fadysaeed.pdfx.engine.DocxExport
import com.fadysaeed.pdfx.engine.Span
import com.fadysaeed.pdfx.engine.TableKind
import com.fadysaeed.pdfx.engine.TableModel
import com.fadysaeed.pdfx.engine.TableRow
import com.fadysaeed.pdfx.engine.XlsxExport
import com.fadysaeed.pdfx.engine.plainCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class ExportTest {
    @Test fun numbersBecomeNumbersAndCodesStayWords() {
        assertEquals(1234.5 to false, XlsxExport.numberOf("1,234.5"))
        assertEquals(-3.0 to false, XlsxExport.numberOf("−3"))
        assertEquals(0.45 to true, XlsxExport.numberOf("45%"))
        assertEquals(0.6 to false, XlsxExport.numberOf("0.6"))
        assertNull("a range", XlsxExport.numberOf("0.6-0.8"))
        assertNull("a p value", XlsxExport.numberOf("<0.001"))
        assertNull("a code", XlsxExport.numberOf("007"))
        assertNull("words", XlsxExport.numberOf("10 mg"))
    }

    @Test fun sheetNamesAreLegalAndUnique() {
        val t = TableModel(46, "TABLE 5-1. Risk", "5-1", Box(0f, 0f, 1f, 1f), emptyList(), emptyList(), emptyList(), TableKind.GRID)
        val used = HashSet<String>()
        assertEquals("p47 Table 5-1", XlsxExport.sheetName(t, 0, used))
        assertEquals("p47 Table 5-1 (2)", XlsxExport.sheetName(t, 0, used))
        val odd = TableModel(0, "", "1/2", Box(0f, 0f, 1f, 1f), emptyList(), emptyList(), emptyList(), TableKind.GRID)
        assertFalse(XlsxExport.sheetName(odd, 0, used).contains('/'))
    }

    @Test fun cellsLoseTheirMarks() {
        assertEquals("mg/L 2", plainCell("mg/L ^2^"))
        assertEquals("≥ 5", plainCell("⟦≥|0⟧ 5"))
    }

    @Test fun wordFileHoldsStylesRunsAndTables() {
        val t = TableModel(
            0, "TABLE 1. Doses", "1", Box(0f, 0f, 1f, 1f), listOf("Drug", "Dose"),
            listOf(TableRow(true, listOf("Mood stabilisers")), TableRow(false, listOf("Lithium & co", "0.6^a^"))), listOf("a. Serum level."), TableKind.GRID,
        )
        val blocks = listOf(
            Block.Text("Treatment", 1),
            Block.Text("Bold and italic.", 0, listOf(Span(0, 4, true, false), Span(9, 15, false, true))),
            Block.Text("الليثيوم"),
            Block.Table(t),
        )
        val bytes = ByteArrayOutputStream().also { DocxExport.write(blocks, it, "T") }.toByteArray()
        val names = HashMap<String, String>()
        ZipInputStream(bytes.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; names[e.name] = z.readBytes().toString(Charsets.UTF_8) } }
        assertTrue(names.keys.containsAll(listOf("[Content_Types].xml", "_rels/.rels", "word/document.xml", "word/styles.xml", "word/_rels/document.xml.rels")))
        val doc = names.getValue("word/document.xml")
        assertTrue(doc.contains("<w:pStyle w:val=\"Heading1\"/>"))
        assertTrue(doc.contains("<w:b/><w:bCs/></w:rPr><w:t xml:space=\"preserve\">Bold</w:t>"))
        assertTrue("Arabic runs right to left", doc.contains("<w:bidi/>"))
        assertTrue("& escaped", doc.contains("Lithium &amp; co"))
        assertTrue("superscript", doc.contains("superscript"))
        assertTrue("group row across", doc.contains("<w:gridSpan w:val=\"2\"/>"))
        assertTrue("header repeats", doc.contains("<w:tblHeader/>"))
    }
}
