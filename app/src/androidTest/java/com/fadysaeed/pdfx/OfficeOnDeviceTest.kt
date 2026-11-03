package com.fadysaeed.pdfx

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.fadysaeed.pdfx.engine.Office
import com.fadysaeed.pdfx.engine.OfficePdf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Word files, spreadsheets and CSV become PDFs on the phone: a report with a title, headings, lists,
 * a shaded table with a merged cell, a picture, a link, a page break, a landscape section and
 * "Page X of Y" in its footer; a sheet of dates, percentages, money and a merged row; a CSV with a
 * quoted comma.
 */
@RunWith(AndroidJUnit4::class)
class OfficeOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val shots: File = File(ctx.filesDir, "shots").apply { mkdirs() }

    private fun asset(name: String): File {
        val f = File(ctx.cacheDir, name)
        assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun text(d: Document, page: Int): String {
        val p = d.loadPage(page)
        try { return p.toStructuredText().let { st -> st.asText().also { st.destroy() } } } finally { p.destroy() }
    }

    private fun convert(name: String): Pair<File, OfficePdf.Result> {
        val src = asset(name)
        val out = File(shots, name.substringBeforeLast('.') + ".pdf")
        val heard = ArrayList<Int>()
        val r = OfficePdf.toPdf(src.absolutePath, name, out.absolutePath, File(ctx.cacheDir, "office-work")) { k, _ -> heard += k }
        assertTrue("it tells how far it is", heard.isNotEmpty())
        return out to r
    }

    @Test
    fun aWordReportKeepsItsLookPagesAndNumbers() {
        val (out, r) = convert("office-report.docx")
        assertEquals("Lithium monitoring review", r.title)
        val d = Document.openDocument(out.absolutePath) as PDFDocument
        try {
            assertEquals("the page break and the landscape section make three pages", 3, d.countPages())
            val one = text(d, 0)
            assertTrue(one.contains("Clinical Review of Lithium Monitoring"))
            assertTrue(one.contains("Baseline renal and thyroid function"))
            assertTrue("the list keeps its numbers", one.contains("2.") && one.contains("Give the patient a booklet"))
            assertTrue(one.contains("Merged down: check both"))
            assertTrue("the first page has its own footer", one.contains("First page footer") && !one.contains("Page 1 of"))
            assertTrue(one.contains("Text before a page break."))
            val two = text(d, 1)
            assertTrue(two.contains("Text after the page break, on page two."))
            assertTrue("page numbers are filled in: $two", two.contains("Page 2 of 3"))
            assertTrue("the header", two.contains("PDFx test report"))
            assertTrue(two.contains("مرحبا"))
            val third = d.loadPage(2)
            val b = third.bounds
            third.destroy()
            assertTrue("the last section is landscape", b.x1 - b.x0 > b.y1 - b.y0)
            assertTrue(text(d, 2).contains("R2C7"))
            // The link and the picture came along.
            val p0 = d.loadPage(0)
            val links = p0.links
            p0.destroy()
            assertTrue(links != null && links.any { it.uri == "https://www.ranzcp.org" })
            val pageObj = d.findPage(0)
            val xobj = pageObj.get("Resources").get("XObject")
            assertTrue("the picture is on the first page", !xobj.isNull && xobj.size() > 0)
            // Headings make the contents list.
            val outline = d.loadOutline().orEmpty().flatMap { listOf(it) + it.down.orEmpty() }.map { it.title }
            assertTrue(outline.toString(), outline.contains("1. Background") && outline.contains("3. Landscape section"))
        } finally {
            d.destroy()
        }
    }

    @Test
    fun aSpreadsheetShowsItsNumbersAsExcelDoes() {
        val (out, _) = convert("office-clinic.xlsx")
        val d = Document.openDocument(out.absolutePath)
        try {
            assertEquals(2, d.countPages())
            val t = text(d, 0)
            for (want in listOf("Clinic", "15/03/2023", "12.5%", "-5.0%", "\$1,250.00", "\$380.50", "\$1,630.50", "Merged note across all columns", "Stable on lithium")) {
                assertTrue("\"$want\" in: $t", t.contains(want))
            }
            assertTrue(text(d, 1).contains("Col30"))
        } finally {
            d.destroy()
        }
    }

    @Test
    fun aCsvBecomesATable() {
        val (out, _) = convert("office-list.csv")
        val d = Document.openDocument(out.absolutePath)
        try {
            val t = text(d, 0)
            assertTrue(t, t.contains("Smith, Anna") && t.contains("ICU") && t.contains("2026-10-03"))
        } finally {
            d.destroy()
        }
    }

    @Test
    fun olderFilesSayWhatToDo() {
        val f = File(ctx.cacheDir, "old.doc").apply { writeBytes(ByteArray(100)) }
        try { Office.read(f.absolutePath, "old.doc"); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains(".docx")) }
    }
}
