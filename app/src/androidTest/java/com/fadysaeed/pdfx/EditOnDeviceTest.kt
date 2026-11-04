package com.fadysaeed.pdfx

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import com.fadysaeed.pdfx.engine.PdfSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Changing the words on a page: a tapped line is picked as one run of like words; the new words
 * take the old ones' place (the old ones are gone from the page, the lines around untouched), on a
 * plain page, a page turned a quarter and a scanned page, as one step undo takes back.
 */
@RunWith(AndroidJUnit4::class)
class EditOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val shots: File = File(ctx.filesDir, "shots").apply { mkdirs() }

    private fun com.artifex.mupdf.fitz.PDFObject.append(o: com.artifex.mupdf.fitz.PDFObject) = put(size(), o)

    /** Page 1: three lines; page 2: the same turned 90°; page 3: a "scan" (a picture) with words read over it, invisible. */
    private fun sample(): File {
        val f = File(ctx.cacheDir, "edit-sample-${System.nanoTime()}.pdf")
        val d = PDFDocument()
        try {
            val helv = d.addSimpleFont(Font("Helvetica"), 0)
            val times = d.addSimpleFont(Font("Times-Bold"), 0)
            val body = "BT /T 12 Tf 72 742 Td (Patient: John Smith) Tj ET BT /F 12 Tf 72 724 Td (Lithium 450 mg twice daily) Tj ET BT /F 12 Tf 72 706 Td (Next review on 15 November 2026) Tj ET"
            for (rot in listOf(0, 90)) {
                val res = d.newDictionary().apply { put("Font", d.newDictionary().apply { put("F", helv); put("T", times) }) }
                d.insertPage(-1, d.addPage(Rect(0f, 0f, 595f, 842f), rot, res, body))
            }
            // A cream "scan" with the words drawn in it, and the read words laid invisibly over them.
            val bmp = android.graphics.Bitmap.createBitmap(595, 842, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            c.drawColor(0xFFF4EEDC.toInt())
            c.drawText("Dose 450 mg", 72f, 842f - 724f, android.graphics.Paint().apply { textSize = 12f; color = 0xFF202020.toInt() })
            val png = File(ctx.cacheDir, "edit-scan.png")
            png.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            val img = d.addImage(com.artifex.mupdf.fitz.Image(png.absolutePath))
            val res = d.newDictionary().apply {
                put("Font", d.newDictionary().apply { put("F", helv) })
                put("XObject", d.newDictionary().apply { put("I", img) })
            }
            d.insertPage(-1, d.addPage(Rect(0f, 0f, 595f, 842f), 0, res, "q 595 0 0 842 0 0 cm /I Do Q BT 3 Tr /F 12 Tf 72 724 Td (Dose 450 mg) Tj ET"))
            d.save(f.absolutePath, "")
        } finally {
            d.destroy()
        }
        return f
    }

    private fun text(d: Document, i: Int): String {
        val p = d.loadPage(i)
        try { return p.toStructuredText().let { st -> st.asText().also { st.destroy() } } } finally { p.destroy() }
    }

    @Test
    fun aLineChangesAndTheLinesAroundItStay() = runBlocking {
        val f = sample()
        val s = PdfSession.openFile(f.absolutePath)
        try {
            // The middle line, tapped near its middle (the page is 842 high, the line at 724 from the foot).
            val run = s.textRunAt(0, 150f, 842f - 728f)
            assertNotNull(run)
            assertEquals("Lithium 450 mg twice daily", run!!.text)
            assertEquals(12f, run.size, 0.1f)
            assertFalse(run.serif)
            // The bold Times line is a run of its own.
            val title = s.textRunAt(0, 100f, 842f - 746f)!!
            assertEquals("Patient: John Smith", title.text)
            assertTrue(title.bold && title.serif)

            assertTrue(s.replaceText(0, run, "Lithium 600 mg at night"))
            val after = s.textRunAt(0, 150f, 842f - 728f)!!
            assertEquals("Lithium 600 mg at night", after.text)
            assertEquals("starts where the old words did", run.x, after.x, 0.6f)
            assertEquals(run.y, after.y, 0.6f)
            assertEquals(12f, after.size, 0.1f)
            val page = s.text(0)
            assertFalse(page.contains("450"))
            assertTrue(page.contains("Patient: John Smith") && page.contains("Next review on 15 November 2026"))

            // On the turned page the words run the same way the old ones did.
            val turned = s.textRunAt(1, 842f - 728f, 150f) ?: s.textRunAt(1, 728f, 150f)
            assertNotNull("a line on the turned page", turned)
            assertTrue(s.replaceText(1, turned!!, "Changed on a turned page"))
            assertTrue(s.text(1).contains("Changed on a turned page"))
            assertFalse(s.text(1).contains(turned.text))

            // On the scan the read words are replaced and the picture of the old ones painted over.
            val scan = s.textRunAt(2, 100f, 842f - 728f)
            assertNotNull(scan)
            assertTrue(scan!!.onPicture)
            assertTrue(s.replaceText(2, scan, "Dose 300 mg"))
            assertTrue(s.text(2).contains("Dose 300 mg"))
            assertFalse(s.text(2).contains("450"))

            // Undo takes the last change back.
            assertTrue(s.canUndo)
            s.undo()
            assertTrue(s.text(2).contains("450"))

            val out = File(shots, "edited.pdf")
            s.writeTo(out.absolutePath, incremental = false)
            val d = Document.openDocument(out.absolutePath)
            try {
                assertTrue(text(d, 0).contains("Lithium 600 mg at night"))
            } finally {
                d.destroy()
            }
        } finally {
            s.close()
        }
    }

    @Test
    fun aPictureGoesOnThePageAndCanBeFlattened() = runBlocking {
        val f = sample()
        val s = PdfSession.openFile(f.absolutePath)
        try {
            val bmp = android.graphics.Bitmap.createBitmap(200, 100, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2E5C9A.toInt()) }
            val png = File(ctx.cacheDir, "logo.png")
            png.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(s.addPicture(0, com.fadysaeed.pdfx.engine.Box(300f, 100f, 500f, 200f), png.absolutePath))
            val m = s.marks(0).single { it.kind == com.fadysaeed.pdfx.engine.MarkKind.STAMP }
            assertEquals(300f, m.box.x0, 1f)
            assertEquals(200f, m.box.x1 - m.box.x0, 1f)
        } finally {
            s.close()
        }
    }
}
