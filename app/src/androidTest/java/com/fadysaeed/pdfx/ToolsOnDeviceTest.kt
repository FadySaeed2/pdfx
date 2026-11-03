package com.fadysaeed.pdfx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.fadysaeed.pdfx.engine.Arranger
import com.fadysaeed.pdfx.engine.Secure
import com.fadysaeed.pdfx.engine.Shrink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/**
 * Make smaller, password and hidden details on real files: a heavy scan, and organise.pdf (six
 * chapters, a contents list, a filled form field, a link). What must change changes; the pages,
 * words, form and contents stay.
 */
@RunWith(AndroidJUnit4::class)
class ToolsOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val shots: File = File(ctx.filesDir, "shots").apply { mkdirs() }

    private fun asset(name: String): File {
        val f = File(ctx.cacheDir, "tools-$name")
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun text(path: String, password: String? = null): List<String> {
        val d = Document.openDocument(path)
        try {
            if (password != null) assertTrue(d.authenticatePassword(password))
            return List(d.countPages()) { i ->
                val p = d.loadPage(i)
                val st = p.toStructuredText()
                try { st.asText() } finally { st.destroy(); p.destroy() }
            }
        } finally {
            d.destroy()
        }
    }

    /** A heavy scan: a big, grainy JPEG, as phone cameras make them. */
    private fun heavyScan(): File {
        val b = Bitmap.createBitmap(3000, 4000, Bitmap.Config.ARGB_8888)
        val row = IntArray(3000)
        val r = Random(7)
        for (y in 0 until 4000) {
            for (x in 0 until 3000) { val v = 200 + r.nextInt(56); row[x] = Color.rgb(v, v - 5, v - 12) }
            b.setPixels(row, 0, 3000, 0, y, 3000, 1)
        }
        Canvas(b).drawText("Discharge summary", 300f, 600f, Paint().apply { color = Color.BLACK; textSize = 160f })
        val jpg = File(ctx.cacheDir, "tools-heavy.jpg")
        jpg.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        b.recycle()
        val pdf = File(ctx.cacheDir, "tools-heavy.pdf")
        Arranger.fromPictures(listOf(jpg.absolutePath, jpg.absolutePath), pdf.absolutePath)
        return pdf
    }

    private fun biggestPicture(path: String): Int {
        val d = Document.openDocument(path) as PDFDocument
        try {
            var best = 0
            for (i in 1 until d.countObjects()) {
                val o = d.newIndirect(i, 0)
                if (runCatching { o.isStream && o.get("Subtype").asName() == "Image" }.getOrDefault(false))
                    best = maxOf(best, o.get("Width").asInteger(), o.get("Height").asInteger())
            }
            return best
        } finally {
            d.destroy()
        }
    }

    @Test
    fun aHeavyScanComesOutMuchSmallerAndTheSame() {
        val src = heavyScan()
        val report = StringBuilder("scan ${src.length()} bytes\n")
        for (level in Shrink.Level.entries) {
            val out = File(shots, "smaller-${level.name.lowercase()}.pdf")
            val r = Shrink.write(src.absolutePath, out.absolutePath, level)
            assertEquals(2, r.pages)
            report.append("${level.label}: ${out.length()} bytes, ${r.pictures} pictures, biggest side ${biggestPicture(out.absolutePath)}\n")
            assertEquals(2, Document.openDocument(out.absolutePath).let { d -> d.countPages().also { d.destroy() } })
            if (level == Shrink.Level.TIDY) {
                assertEquals("pictures are left alone", 4000, biggestPicture(out.absolutePath))
            } else {
                assertTrue("${level.label} is much smaller", out.length() < src.length() / 2)
                assertTrue(biggestPicture(out.absolutePath) <= level.maxSide)
            }
        }
        File(shots, "smaller.txt").writeText(report.toString())
    }

    @Test
    fun tidyingKeepsWordsFormAndContents() {
        val src = asset("organise.pdf")
        val out = File(ctx.cacheDir, "tools-tidy.pdf")
        Shrink.write(src.absolutePath, out.absolutePath, Shrink.Level.BALANCED)
        assertEquals(text(src.absolutePath), text(out.absolutePath))
        val d = Document.openDocument(out.absolutePath) as PDFDocument
        try {
            assertTrue("the form is kept", d.countPages() == 6 && !d.trailer.get("Root").get("AcroForm").get("Fields").isNull)
            assertTrue("the contents list is kept", d.loadOutline() != null)
        } finally {
            d.destroy()
        }
    }

    @Test
    fun aPasswordLocksWithAes256AndComesOff() {
        val src = asset("organise.pdf")
        val locked = File(shots, "locked.pdf")
        val pw = "Lithium, 0.8 mmol/L"
        Secure.protect(src.absolutePath, locked.absolutePath, pw)
        val d = Document.openDocument(locked.absolutePath) as PDFDocument
        try {
            assertTrue("it asks for the password", d.needsPassword())
            assertFalse("a wrong one does not open it", d.authenticatePassword("lithium"))
            assertTrue(d.authenticatePassword(pw))
            val enc = d.trailer.get("Encrypt")
            assertEquals("AES-256 (V5)", 5, enc.get("V").asInteger())
            assertEquals(6, enc.get("R").asInteger())
        } finally {
            d.destroy()
        }
        assertEquals(text(src.absolutePath), text(locked.absolutePath, pw))

        // Making it smaller keeps it locked.
        val smaller = File(ctx.cacheDir, "tools-locked-smaller.pdf")
        Shrink.write(locked.absolutePath, smaller.absolutePath, Shrink.Level.BALANCED, pw)
        assertTrue(Document.openDocument(smaller.absolutePath).let { x -> x.needsPassword().also { x.destroy() } })

        val open = File(ctx.cacheDir, "tools-unlocked.pdf")
        Secure.unprotect(locked.absolutePath, open.absolutePath, pw)
        assertFalse(Document.openDocument(open.absolutePath).let { x -> x.needsPassword().also { x.destroy() } })
        assertEquals(text(src.absolutePath), text(open.absolutePath))
    }

    @Test
    fun hiddenDetailsAreShownThenRemoved() {
        val src = asset("organise.pdf")
        val dirty = File(ctx.cacheDir, "tools-details.pdf")
        val d = Document.openDocument(src.absolutePath) as PDFDocument
        try {
            d.setMetaData("info:Author", "Dr Fady Saeed")
            d.setMetaData("info:Creator", "Microsoft Word for Microsoft 365")
            d.setMetaData("info:CreationDate", "D:20240312101500+08'00'")
            val xmp = d.addStream("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><dc:creator>Dr Fady Saeed</dc:creator></x:xmpmeta>")
            d.trailer.get("Root").put("Metadata", xmp)
            d.save(dirty.absolutePath, "garbage=compact")
        } finally {
            d.destroy()
        }
        val found = Secure.details(dirty.absolutePath).toMap()
        assertEquals("Dr Fady Saeed", found["Author"])
        assertEquals("Microsoft Word for Microsoft 365", found["Made with"])
        assertEquals("2024-03-12 10:15", found["Created"])
        assertTrue("XMP" in found)

        val clean = File(shots, "clean.pdf")
        Secure.scrub(dirty.absolutePath, clean.absolutePath)
        assertEquals(emptyList<Pair<String, String>>(), Secure.details(clean.absolutePath))
        assertFalse("the author is nowhere in the file", String(clean.readBytes(), Charsets.ISO_8859_1).contains("Fady"))
        assertEquals(text(src.absolutePath), text(clean.absolutePath))
    }
}
