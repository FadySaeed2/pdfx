package com.fadysaeed.pdfx

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import com.fadysaeed.pdfx.engine.Stamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Page numbers and watermarks go on the pages themselves, where asked and the right way up: on a
 * plain page, on a page turned a quarter, on a cropped page, and starting where asked.
 */
@RunWith(AndroidJUnit4::class)
class StampOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val shots: File = File(ctx.filesDir, "shots").apply { mkdirs() }

    /** Four pages: plain, turned 90°, cropped, plain; each with a line of words. */
    private fun sample(): File {
        val f = File(ctx.cacheDir, "stamp-sample.pdf")
        val d = PDFDocument()
        try {
            val font = d.addSimpleFont(Font("Helvetica"), 0)
            for (i in 0 until 4) {
                val res = d.newDictionary().apply { put("Font", d.newDictionary().apply { put("F", font) }) }
                val page = d.addPage(Rect(0f, 0f, 595f, 842f), if (i == 1) 90 else 0, res, "BT /F 18 Tf 72 760 Td (Body text on page ${i + 1}) Tj ET")
                d.insertPage(-1, page)
            }
            // The third page shows only a part of itself.
            d.findPage(2).put("CropBox", d.newArray().apply { append(d.newReal(50f)); append(d.newReal(100f)); append(d.newReal(545f)); append(d.newReal(800f)) })
            d.save(f.absolutePath, "")
        } finally {
            d.destroy()
        }
        return f
    }

    private fun com.artifex.mupdf.fitz.PDFObject.append(o: com.artifex.mupdf.fitz.PDFObject) = put(size(), o)

    /** Where [text] sits on page [i], as the page is shown. */
    private fun find(d: Document, i: Int, text: String): Pair<Rect, Rect>? {
        val p = d.loadPage(i)
        try {
            val hits = p.search(text) ?: return null
            if (hits.isEmpty()) return null
            return hits[0][0].toRect() to p.bounds
        } finally {
            p.destroy()
        }
    }

    @Test
    fun numbersSitWhereAskedOnEveryKindOfPage() {
        val src = sample()
        val out = File(shots, "numbered.pdf")
        Stamp.pageNumbers(src.absolutePath, out.absolutePath, Stamp.Numbers(Stamp.Place.BOTTOM_RIGHT, Stamp.Style.OF, from = 1, startAt = 1))
        val d = Document.openDocument(out.absolutePath)
        try {
            assertEquals(4, d.countPages())
            assertTrue("the cover has no number", find(d, 0, "Page") == null)
            for ((i, n) in listOf(1 to 1, 2 to 2, 3 to 3)) {
                val (r, b) = find(d, i, "Page $n of 3") ?: error("no number on page ${i + 1}")
                // Bottom right of the page as shown, the right way up (wider than tall).
                assertTrue("page ${i + 1}: $r in $b", r.x1 > b.x1 - 120 && r.x1 <= b.x1 && r.y1 > b.y1 - 60 && r.y1 <= b.y1)
                assertTrue("page ${i + 1} reads across: $r", r.x1 - r.x0 > r.y1 - r.y0)
            }
            // The page's own words are still there and where they were.
            assertTrue(find(d, 3, "Body text on page 4") != null)
        } finally {
            d.destroy()
        }
    }

    @Test
    fun aWatermarkCrossesEveryPageAndTheWordsUnderItStay() {
        val src = sample()
        val out = File(shots, "watermarked.pdf")
        Stamp.watermark(src.absolutePath, out.absolutePath, Stamp.Mark("CONFIDENTIAL", 0xC0392B, 0.25f, diagonal = true))
        val d = Document.openDocument(out.absolutePath)
        try {
            for (i in 0 until 4) {
                val p = d.loadPage(i)
                val text = p.toStructuredText().let { st -> st.asText().also { st.destroy() } }
                val b = p.bounds
                p.destroy()
                assertTrue("page ${i + 1}: $text", text.contains("CONFIDENTIAL"))
                assertTrue(text.contains("Body text on page ${i + 1}"))
                val (r, _) = find(d, i, "CONFIDENTIAL")!!
                // Across the middle of the page.
                val cx = (r.x0 + r.x1) / 2
                val cy = (r.y0 + r.y1) / 2
                assertTrue("page ${i + 1} centre: $r in $b", kotlin.math.abs(cx - (b.x0 + b.x1) / 2) < 60 && kotlin.math.abs(cy - (b.y0 + b.y1) / 2) < 60)
            }
        } finally {
            d.destroy()
        }
        val first = File(shots, "watermarked-first.pdf")
        Stamp.watermark(src.absolutePath, first.absolutePath, Stamp.Mark("DRAFT", diagonal = false, firstOnly = true))
        val e = Document.openDocument(first.absolutePath)
        try {
            assertTrue(find(e, 0, "DRAFT") != null)
            assertTrue(find(e, 1, "DRAFT") == null)
        } finally {
            e.destroy()
        }
        assertFalse("not Latin letters", Stamp.writable("سري"))
        assertTrue(Stamp.writable("Not for circulation – v2"))
    }
}
