package com.fadysaeed.pdfx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.artifex.mupdf.fitz.Matrix
import com.fadysaeed.pdfx.engine.Books
import com.fadysaeed.pdfx.engine.PagePictures
import com.fadysaeed.pdfx.engine.PdfSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A small EPUB (two chapters, English and Arabic, a table, a picture, a link from chapter 1 to
 * chapter 2) made into a PDF at each page size: real words, the Arabic in a phone font rather than
 * empty boxes, the chapters as the contents list, the link still going to chapter 2. And pages
 * saved as pictures, at the size asked for.
 */
@RunWith(AndroidJUnit4::class)
class BooksOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val shots: File = File(ctx.filesDir, "shots").apply { mkdirs() }

    private fun epub(): File {
        val png = ByteArrayOutputStream().also { Bitmap.createBitmap(300, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 130, 170)) }.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val f = File(ctx.cacheDir, "book-test.epub")
        ZipOutputStream(f.outputStream()).use { z ->
            // The first entry, uncompressed, says what the zip is.
            val mt = "application/epub+zip".toByteArray()
            z.putNextEntry(ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mt.size.toLong(); compressedSize = mt.size.toLong(); crc = CRC32().apply { update(mt) }.value })
            z.write(mt); z.closeEntry()
            fun put(name: String, body: String) { z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry() }
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OEBPS/content.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Lithium Notes</dc:title><dc:identifier id="id">pdfx-test</dc:identifier><dc:language>en</dc:language></metadata><manifest><item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/><item id="b" href="b.xhtml" media-type="application/xhtml+xml"/><item id="img" href="pic.png" media-type="image/png"/></manifest><spine toc="ncx"><itemref idref="a"/><itemref idref="b"/></spine></package>""")
            put("OEBPS/toc.ncx", """<?xml version="1.0"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head/><docTitle><text>Lithium Notes</text></docTitle><navMap><navPoint id="n1" playOrder="1"><navLabel><text>One: Levels</text></navLabel><content src="a.xhtml"/></navPoint><navPoint id="n2" playOrder="2"><navLabel><text>Two: Side effects</text></navLabel><content src="b.xhtml"/></navPoint></navMap></ncx>""")
            val body = "Lithium remains the first choice for bipolar disorder. ".repeat(60)
            put("OEBPS/a.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>One: Levels</h1><p>$body</p><p dir="rtl" lang="ar">الليثيوم هو العلاج الأساسي للاضطراب الوجداني ثنائي القطب.</p><table border="1"><tr><th>Level</th><th>mmol/L</th></tr><tr><td>Usual</td><td>0.6-0.8</td></tr></table><p><img src="pic.png" alt="a picture"/></p><p><a href="b.xhtml">Go to side effects</a></p></body></html>""")
            put("OEBPS/b.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml"><body><h1>Two: Side effects</h1><p>Tremor, thirst and weight gain are common.</p></body></html>""")
            z.putNextEntry(ZipEntry("OEBPS/pic.png")); z.write(png); z.closeEntry()
        }
        return f
    }

    @Test
    fun anEpubBecomesAPdfWithItsWordsChaptersAndLinks() {
        val book = epub()
        for (size in Books.Size.entries) {
            val out = File(shots, "book-${size.name.lowercase()}.pdf")
            val heard = ArrayList<Int>()
            val r = Books.toPdf(book.absolutePath, out.absolutePath, size, 11f) { k, _ -> heard += k }
            assertEquals("Lithium Notes", r.title)
            assertTrue(r.pages >= 2)
            assertEquals((1..r.pages).toList(), heard)
            val d = Document.openDocument(out.absolutePath) as PDFDocument
            try {
                assertEquals(r.pages, d.countPages())
                val p0 = d.loadPage(0)
                assertEquals(size.w, p0.bounds.x1 - p0.bounds.x0, 1f)
                p0.destroy()
                val text = (0 until d.countPages()).joinToString("\n") { i -> val p = d.loadPage(i); val st = p.toStructuredText(); try { st.asText() } finally { st.destroy(); p.destroy() } }
                assertTrue("real words", text.contains("Lithium remains the first choice"))
                assertTrue("the Arabic is real words too: $text", text.contains("الليثيوم"))
                assertTrue("the table's words", text.contains("0.6-0.8"))
                val outline = d.loadOutline()
                assertEquals("the chapters are the contents list", listOf("One: Levels", "Two: Side effects"), outline?.map { it.title })
                // The link to chapter 2 goes to the page it starts on.
                val two = d.pageNumberFromLocation(d.resolveLink(outline!![1].uri))
                val links = (0 until d.countPages()).flatMap { i -> val p = d.loadPage(i) as PDFPage; try { p.links?.toList().orEmpty() } finally { p.destroy() } }
                assertTrue("the link is kept", links.any { !it.isExternal && d.pageNumberFromLocation(d.resolveLink(it)) == two })
                // The Arabic is drawn with one of the phone's own fonts, not left as empty boxes.
                var phoneFont = false
                for (i in 1 until d.countObjects()) {
                    val o = d.newIndirect(i, 0)
                    val name = runCatching { if (o.isDictionary && o.get("Type").asName() == "Font") o.get("BaseFont").asName() else "" }.getOrDefault("")
                    if (name.contains("Arabic", true) || name.contains("Naskh", true)) phoneFont = true
                }
                assertTrue("an Arabic font from the phone is in the file", phoneFont)
                if (size == Books.Size.PHONE) {
                    val p = d.loadPage(0)
                    val bmp = AndroidDrawDevice.drawPage(p, Matrix(2f))
                    File(shots, "book_phone_page1.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle(); p.destroy()
                }
            } finally {
                d.destroy()
            }
        }
    }

    @Test
    fun pagesBecomePicturesAtTheSizeAsked() = runBlocking {
        val f = File(ctx.cacheDir, "pictures-organise.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("organise.pdf").use { i -> f.outputStream().use { i.copyTo(it) } }
        val s = PdfSession.openFile(f.absolutePath)
        try {
            val dir = File(ctx.cacheDir, "pictures-out").apply { deleteRecursively() }
            val files = PagePictures.write(s, listOf(0, 4), dir, "Chapters", PagePictures.Format.PNG, 144) { _, _ -> }
            assertEquals(listOf("Chapters p01.png", "Chapters p05.png"), files.map { it.name })
            val a = BitmapFactory.decodeFile(files[0].absolutePath)
            assertEquals("A4 at 144 dpi", 1190, a.width, 2)
            val b = BitmapFactory.decodeFile(files[1].absolutePath)
            assertTrue("the turned page stays turned", b.width > b.height)
            val jpg = PagePictures.write(s, listOf(1), dir, "Chapters", PagePictures.Format.JPEG, 72) { _, _ -> }
            assertTrue(jpg.single().name.endsWith(".jpg"))
        } finally {
            s.close()
        }
    }

    private fun assertEquals(msg: String, want: Int, got: Int, slack: Int) = assertTrue("$msg: $got", kotlin.math.abs(want - got) <= slack)
}
