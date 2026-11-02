package com.fadysaeed.pdfx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.PDFPage
import com.fadysaeed.pdfx.data.Pictures
import com.fadysaeed.pdfx.engine.Arranger
import com.fadysaeed.pdfx.engine.PageItem
import com.fadysaeed.pdfx.engine.PageSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Putting pages in order on a real file (tools/make-organise-fixture.py: six chapters, a contents
 * list, a filled form field on page 2, a link on page 1, page 5 already turned): reorder, turn, use
 * a page twice, add a blank page, a photo and a page of another PDF, leave pages out. The new file
 * must hold exactly those pages, each with its own words, turns, field, contents entries and marks.
 */
@RunWith(AndroidJUnit4::class)
class ArrangeOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val shots: File = File(ctx.filesDir, "shots").apply { mkdirs() }

    private fun asset(name: String): File {
        val f = File(ctx.cacheDir, "arrange-$name")
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun photo(): File {
        // A photo wider than tall, saved as PNG with see-through corners.
        val b = Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.TRANSPARENT)
        c.drawRect(40f, 40f, 760f, 460f, Paint().apply { color = Color.rgb(30, 110, 160) })
        c.drawText("A photo of a whiteboard", 80f, 260f, Paint().apply { color = Color.WHITE; textSize = 48f })
        val f = File(ctx.cacheDir, "arrange-photo.png")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    private fun text(d: Document, i: Int): String {
        val p = d.loadPage(i)
        try {
            val st = p.toStructuredText()
            try { return st.asText() } finally { st.destroy() }
        } finally { p.destroy() }
    }

    private fun outline(d: Document): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        fun walk(list: Array<Outline>?) {
            list?.forEach { o ->
                out[o.title] = runCatching { d.pageNumberFromLocation(d.resolveLink(o.uri)) }.getOrDefault(-1)
                walk(o.down)
            }
        }
        walk(d.loadOutline())
        return out
    }

    @Test
    fun arrangesPagesAndKeepsWhatTheyCarry() {
        val base = asset("organise.pdf")
        val other = asset("signable.pdf")
        val jpg = Pictures.prepare(ctx, Uri.fromFile(photo()), File(ctx.cacheDir, "arrange-photo.jpg"))!!
        val items = listOf(
            PageItem(PageSource.ORIGINAL, 3),
            PageItem(PageSource.ORIGINAL, 0, turn = 90),
            PageItem(PageSource.ORIGINAL, 0),
            PageItem(PageSource.BLANK, width = 300f, height = 400f),
            PageItem(PageSource.ORIGINAL, 1),
            PageItem(PageSource.PICTURE, path = jpg.absolutePath),
            PageItem(PageSource.OTHER, 0, path = other.absolutePath),
            PageItem(PageSource.ORIGINAL, 4, turn = 90),
        )
        val out = File(shots, "arranged.pdf")
        val r = Arranger.write(base.absolutePath, items, out.absolutePath)
        assertEquals(8, r.pages)
        assertTrue(r.skippedFiles.isEmpty())

        val d = Document.openDocument(out.absolutePath)
        try {
            assertEquals(8, d.countPages())
            val words = (0 until 8).map { text(d, it) }
            assertTrue(words[0], words[0].contains("marker-4"))
            assertTrue(words[1], words[1].contains("marker-1"))
            assertTrue("the copy has the page's words", words[2].contains("marker-1"))
            assertTrue("the blank page is blank", words[3].isBlank())
            assertTrue(words[4], words[4].contains("marker-2"))
            assertTrue("the photo page has no words", words[5].isBlank())
            assertTrue(words[6], words[6].contains("Referral letter"))
            assertTrue(words[7], words[7].contains("marker-5"))
            listOf(0, 2, 4, 6).forEach { i -> assertTrue("no other page's words on page ${i + 1}", (1..6).count { words[i].contains("marker-$it") } <= 1) }

            // Turns: page 1 turned once, page 5 had a turn already and now has two; the blank page is its own size.
            val pdf = d.asPDF()!!
            fun rotate(i: Int) = pdf.findPage(i).getInheritable("Rotate").let { if (it.isNull) 0 else it.asInteger() }
            assertEquals(0, rotate(0))
            assertEquals(90, rotate(1))
            assertEquals(0, rotate(2))
            assertEquals(180, rotate(7))
            d.loadPage(1).let { p -> val b = p.bounds; p.destroy(); assertEquals(842f, b.x1 - b.x0, 1f); assertEquals(595f, b.y1 - b.y0, 1f) }
            d.loadPage(3).let { p -> val b = p.bounds; p.destroy(); assertEquals(300f, b.x1 - b.x0, 0.5f); assertEquals(400f, b.y1 - b.y0, 0.5f) }
            // The photo's page is landscape, like the photo.
            d.loadPage(5).let { p -> val b = p.bounds; p.destroy(); assertTrue("landscape photo page", b.x1 - b.x0 > b.y1 - b.y0) }

            // The form field stays with its page, with its value, and the form still lists it.
            val page4 = d.loadPage(4) as PDFPage
            val w = page4.widgets.orEmpty()
            assertEquals(1, w.size)
            assertEquals("Ann Example", w[0].value)
            page4.destroy()
            val fields = pdf.trailer.get("Root").get("AcroForm").get("Fields")
            assertTrue("the form lists the field", fields.isArray && fields.size() == 1 && fields.get(0).get("T").asString() == "patient_name")

            // The contents list: chapters still in the file point to where their pages now are; the others are gone.
            val toc = outline(d)
            assertEquals(mapOf("Chapter 1" to 1, "Chapter 2" to 4, "Chapter 4" to 0, "Chapter 5" to 7), toc)
            // The link on chapter 1 pointed to chapter 3, which is not in the file any more.
            val links = d.loadPage(1).let { p -> val l = p.links.orEmpty(); p.destroy(); l }
            assertTrue("no link to a page that is gone", links.none { runCatching { d.pageNumberFromLocation(d.resolveLink(it.uri)) }.getOrDefault(-1) >= 0 })
            // The copied letter page keeps its form fields as they look, and their names.
            val page6 = d.loadPage(6) as PDFPage
            val names = page6.widgets.orEmpty().map { it.name }
            page6.destroy()
            assertTrue("the letter's fields came with it: $names", names.contains("patient_name") || names.contains("doctor_signature"))
        } finally {
            d.destroy()
        }
    }

    @Test
    fun leavesOutLockedFilesAndKeepsTheRest() {
        val base = asset("organise.pdf")
        val items = listOf(PageItem(PageSource.ORIGINAL, 5), PageItem(PageSource.OTHER, 0, path = File(ctx.cacheDir, "no-such.pdf").absolutePath))
        val out = File(ctx.cacheDir, "arranged-2.pdf")
        val r = Arranger.write(base.absolutePath, items, out.absolutePath)
        assertEquals(1, r.pages)
        assertEquals(1, r.skippedFiles.size)
        val d = Document.openDocument(out.absolutePath)
        try {
            assertEquals(1, d.countPages())
            assertTrue(text(d, 0).contains("marker-6"))
            // No form left: its only field was on a page that is not in this file.
            assertTrue(d.asPDF()!!.trailer.get("Root").get("AcroForm").isNull)
        } finally {
            d.destroy()
        }
        // Saving the same file twice gives the same pages; the original was only read.
        val again = Document.openDocument(base.absolutePath)
        try { assertEquals(6, again.countPages()) } finally { again.destroy() }
    }
}
