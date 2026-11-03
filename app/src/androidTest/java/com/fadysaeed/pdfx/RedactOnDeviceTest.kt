package com.fadysaeed.pdfx

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.fadysaeed.pdfx.engine.Arranger
import com.fadysaeed.pdfx.engine.Box
import com.fadysaeed.pdfx.engine.MarkKind
import com.fadysaeed.pdfx.engine.PdfSession
import com.fadysaeed.pdfx.engine.Secure
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Blacking out on organise.pdf (a word on page 3, a filled form field on page 2) and on a page that
 * is a photo: afterwards the words are gone from the file, not just covered, the field is empty, a
 * highlight over the word is gone, the photo's own pixels under the box are gone, and the rest of
 * each page is untouched.
 */
@RunWith(AndroidJUnit4::class)
class RedactOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val shots: File = File(ctx.filesDir, "shots").apply { mkdirs() }

    private fun text(d: Document, i: Int): String {
        val p = d.loadPage(i)
        val st = p.toStructuredText()
        try { return st.asText() } finally { st.destroy(); p.destroy() }
    }

    @Test
    fun wordsFieldsAndMarksUnderTheBoxesLeaveTheFile() = runBlocking {
        val f = File(ctx.cacheDir, "redact-${System.nanoTime()}.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("organise.pdf").use { i -> f.outputStream().use { i.copyTo(it) } }
        val s = PdfSession.openFile(f.absolutePath)
        val out = File(shots, "blacked-out.pdf")
        try {
            // A highlight over the word that will go, as someone might have left.
            val word = s.search(2, "marker-3").first()
            assertTrue(s.addTextMark(2, MarkKind.HIGHLIGHT, word, 0xFFFFD54A.toInt()))
            assertEquals("found once, on one page", 1 to 1, s.markAllToRedact("MARKER-3"))
            val field = s.fields(1).first { it.name == "patient_name" }
            assertEquals("Ann Example", field.value)
            assertTrue(s.addRedaction(1, listOf(field.box), words = false))
            assertEquals(2, s.redactionCount())
            assertTrue("marks show as such", s.marks(2).any { it.kind == MarkKind.REDACT })

            assertEquals(2, s.applyRedactions())
            assertEquals(0, s.redactionCount())
            assertFalse("never appended: the old words would stay in an earlier revision", s.canSaveIncrementally())
            s.scrubDetails()
            s.writeTo(out.absolutePath, incremental = s.canSaveIncrementally())
        } finally {
            s.close()
        }

        val d = Document.openDocument(out.absolutePath) as PDFDocument
        try {
            val t3 = text(d, 2)
            assertFalse("the word is gone: $t3", t3.contains("marker-3"))
            assertTrue("the rest of the line stays: $t3", t3.contains("on this page"))
            assertTrue("other lines stay", t3.contains("Line 1 of chapter 3"))
            val p3 = d.loadPage(2) as PDFPage
            val kinds = p3.annotations?.map { it.type }.orEmpty()
            assertFalse("the highlight over it went too", PDFAnnotation.TYPE_HIGHLIGHT in kinds)
            assertFalse("no marks are left behind", PDFAnnotation.TYPE_REDACT in kinds)
            p3.destroy()
            val p2 = d.loadPage(1) as PDFPage
            val w = p2.widgets?.firstOrNull()
            assertTrue("the field is emptied", w == null || w.value.isNullOrEmpty())
            p2.destroy()
            assertEquals(6, d.countPages())
        } finally {
            d.destroy()
        }
        val raw = String(out.readBytes(), Charsets.ISO_8859_1)
        assertFalse("the name is nowhere in the file", raw.contains("Ann Example"))
        assertEquals(emptyList<Pair<String, String>>(), Secure.details(out.absolutePath))
    }

    @Test
    fun aPhotosPixelsUnderTheBoxAreGone() = runBlocking {
        val png = File(ctx.cacheDir, "redact-red.png")
        val b = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        png.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val pdf = File(ctx.cacheDir, "redact-photo.pdf")
        Arranger.fromPictures(listOf(png.absolutePath), pdf.absolutePath)
        val s = PdfSession.openFile(pdf.absolutePath)
        val out = File(ctx.cacheDir, "redact-photo-out.pdf")
        try {
            val size = s.pageSize(0)
            // The left half of the photo.
            s.addRedaction(0, listOf(Box(0f, 0f, size.width / 2, size.height)), words = false)
            assertEquals(1, s.applyRedactions())
            s.writeTo(out.absolutePath, incremental = false)
        } finally {
            s.close()
        }
        val d = Document.openDocument(out.absolutePath) as PDFDocument
        try {
            var checked = false
            for (i in 1 until d.countObjects()) {
                val o = d.newIndirect(i, 0)
                if (!runCatching { o.isStream && o.get("Subtype").asName() == "Image" }.getOrDefault(false)) continue
                val img = d.loadImage(o)
                val pix = img.toPixmap()
                val n = pix.numberOfComponents
                // The PNG's see-through mask is a picture of its own, in grey: only the colour one counts.
                if (n < 3) { pix.destroy(); img.destroy(); continue }
                fun at(x: Int, y: Int): Triple<Int, Int, Int> {
                    val k = y * pix.stride + x * n
                    val sm = pix.samples
                    return Triple(sm[k].toInt() and 0xFF, sm[k + 1].toInt() and 0xFF, sm[k + 2].toInt() and 0xFF)
                }
                val left = at(pix.width / 4, pix.height / 2)
                val right = at(pix.width * 3 / 4, pix.height / 2)
                assertFalse("the left half's own pixels are no longer red: $left", left.first > 200 && left.second < 60)
                assertTrue("the right half is untouched: $right", right.first > 200 && right.second < 60)
                pix.destroy(); img.destroy()
                checked = true
            }
            assertTrue("the photo is still in the file", checked)
        } finally {
            d.destroy()
        }
    }
}
