package com.fadysaeed.pdfx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Page
import com.fadysaeed.pdfx.engine.Arranger
import com.fadysaeed.pdfx.engine.ReadWord
import com.fadysaeed.pdfx.engine.TextLayer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Words read off a scan are laid over the page as invisible text: afterwards the page looks the same
 * to the pixel, and searching finds each word exactly where it is in the picture, on an upright
 * page, a wide one and a page that is turned.
 */
@RunWith(AndroidJUnit4::class)
class TextLayerOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val shots: File = File(ctx.filesDir, "shots").apply { mkdirs() }

    private fun picture(w: Int, h: Int, name: String): String {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).apply {
            drawColor(Color.WHITE)
            drawText("Lithium levels", w * 0.1f, h * 0.2f, Paint().apply { color = Color.BLACK; textSize = w / 14f })
        }
        val f = File(ctx.cacheDir, "layer-$name.jpg")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f.absolutePath
    }

    private fun pixels(p: Page): ByteArray {
        val pix = p.toPixmap(Matrix.Identity(), ColorSpace.DeviceRGB, false, true)
        try { return pix.samples } finally { pix.destroy() }
    }

    private fun near(want: ReadWord, p: Page, text: String) {
        val hits = p.search(text)
        assertTrue("\"$text\" is found", hits != null && hits.isNotEmpty())
        val q = hits!![0]
        val x0 = q.minOf { minOf(it.ul_x, it.ll_x) }
        val x1 = q.maxOf { maxOf(it.ur_x, it.lr_x) }
        val y0 = q.minOf { minOf(it.ul_y, it.ur_y) }
        val y1 = q.maxOf { maxOf(it.ll_y, it.lr_y) }
        assertEquals("$text left", want.left, x0, 2f)
        assertEquals("$text right", want.right, x1, 2f)
        assertEquals("$text top", want.top, y0, 3f)
        assertEquals("$text bottom", want.bottom, y1, 3f)
    }

    @Test
    fun readWordsBecomeSearchableTextInTheirPlaces() {
        val scanned = File(ctx.cacheDir, "layer-scanned.pdf")
        Arranger.fromPictures(listOf(picture(1240, 1754, "a4"), picture(1600, 900, "wide")), scanned.absolutePath)
        val doc = Document.openDocument(scanned.absolutePath) as PDFDocument
        val words = mapOf(
            0 to listOf(ReadWord("Lithium", 60f, 140f, 200f, 172f), ReadWord("levels", 210f, 140f, 320f, 172f), ReadWord("café", 60f, 400f, 130f, 420f)),
            1 to listOf(ReadWord("ﬁrst-line", 84f, 70f, 260f, 100f)),
        )
        val before = HashMap<Int, ByteArray>()
        for ((i, ws) in words) {
            val p = doc.loadPage(i) as PDFPage
            assertFalse("a bare scan has no words", TextLayer.hasText(p))
            before[i] = pixels(p)
            assertEquals(ws.size, TextLayer.add(doc, p, ws))
            p.destroy()
        }
        val out = File(shots, "searchable.pdf")
        doc.save(out.absolutePath, "garbage=compact")
        doc.destroy()

        val d = Document.openDocument(out.absolutePath)
        try {
            for ((i, ws) in words) {
                val p = d.loadPage(i)
                assertTrue("now it has words", TextLayer.hasText(p))
                assertArrayEquals("page ${i + 1} looks the same", before[i], pixels(p))
                p.destroy()
            }
            val p0 = d.loadPage(0)
            near(words[0]!![0], p0, "Lithium")
            near(words[0]!![1], p0, "levels")
            near(words[0]!![2], p0, "café")
            val st = p0.toStructuredText()
            val text = st.asText()
            st.destroy()
            assertTrue("copied text reads as words: $text", text.contains("Lithium levels"))
            p0.destroy()
            val p1 = d.loadPage(1)
            near(words[1]!![0], p1, "first-line")
            p1.destroy()
        } finally {
            d.destroy()
        }
    }

    @Test
    fun aTurnedPageGetsItsWordsWhereTheyShow() {
        // organise.pdf's fifth page is turned a quarter; boxes are given as the page is shown.
        val f = File(ctx.cacheDir, "layer-organise.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("organise.pdf").use { i -> f.outputStream().use { i.copyTo(it) } }
        val doc = Document.openDocument(f.absolutePath) as PDFDocument
        val p = doc.loadPage(4) as PDFPage
        val shown = p.bounds
        assertTrue("the page is shown on its side", (shown.x1 - shown.x0) > (shown.y1 - shown.y0))
        val w = ReadWord("Clozapine", 100f, 300f, 260f, 324f)
        TextLayer.add(doc, p, listOf(w))
        p.destroy()
        val out = File(ctx.cacheDir, "layer-turned.pdf")
        doc.save(out.absolutePath, "garbage=compact")
        doc.destroy()
        val d = Document.openDocument(out.absolutePath)
        try {
            val q = d.loadPage(4)
            near(w, q, "Clozapine")
            q.destroy()
        } finally {
            d.destroy()
        }
    }
}
