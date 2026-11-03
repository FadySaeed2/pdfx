package com.fadysaeed.pdfx

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Document
import com.fadysaeed.pdfx.engine.Arranger
import com.fadysaeed.pdfx.engine.PdfSession
import com.fadysaeed.pdfx.engine.Reading
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Scanned pages get their words: a page of a book photographed and a table, made into a file of
 * pictures as the scanner does, come out with words that search finds where they are and that
 * copy as the page reads, both for a whole file (the Scan button) and in an open file (Make searchable).
 */
@RunWith(AndroidJUnit4::class)
class ReadingOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets
    private val shots: File = File(ctx.filesDir, "shots").apply { mkdirs() }

    private fun asset(name: String): File {
        val f = File(ctx.cacheDir, "reading-$name")
        assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun truth(name: String): List<String> {
        val t = JSONArray(assets.open("ocr-truth.json").bufferedReader().readText())
        for (i in 0 until t.length()) if (t.getJSONObject(i).getString("name") == name)
            return t.getJSONObject(i).getJSONArray("words").let { a -> List(a.length()) { a.getString(it) } }
        error("no $name")
    }

    private fun norm(w: String) = w.trim { !it.isLetterOrDigit() }.lowercase()

    /** Share of the page's words found in [text], counted as a multiset. */
    private fun recall(truth: List<String>, text: String): Double {
        val left = HashMap<String, Int>()
        text.split(Regex("\\s+")).map(::norm).filter { it.isNotEmpty() }.forEach { left[it] = (left[it] ?: 0) + 1 }
        val want = truth.map(::norm).filter { it.isNotEmpty() }
        var hit = 0
        for (w in want) { val n = left[w] ?: 0; if (n > 0) { hit++; left[w] = n - 1 } }
        return hit.toDouble() / want.size
    }

    @Test
    fun aScanComesOutSearchable() {
        val pictures = File(ctx.cacheDir, "reading-pictures.pdf")
        assertEquals(2, Arranger.fromPictures(listOf(asset("ocr-book-photo.jpg").absolutePath, asset("ocr-table.jpg").absolutePath), pictures.absolutePath))
        val out = File(shots, "scan-searchable.pdf")
        val heard = ArrayList<Pair<Int, Int>>()
        val read = Reading.makeSearchable(pictures.absolutePath, out.absolutePath) { k, n -> heard += k to n }
        assertEquals(2, read)
        assertEquals(listOf(1 to 2, 2 to 2), heard)

        val d = Document.openDocument(out.absolutePath)
        try {
            val report = StringBuilder()
            val recalls = HashMap<String, Double>()
            for ((i, name) in listOf(0 to "book-photo", 1 to "table")) {
                val p = d.loadPage(i)
                val st = p.toStructuredText()
                val text = st.asText()
                st.destroy()
                recalls[name] = recall(truth(name), text)
                report.append("== $name: recall ").append("%.3f".format(recalls[name])).append('\n').append(text).append('\n')
                p.destroy()
            }
            File(shots, "reading.txt").writeText(report.toString())
            for ((i, name) in listOf(0 to "book-photo", 1 to "table")) {
                val r = recalls[name]!!
                assertTrue("$name: words read, recall $r", r > 0.9)
                val p = d.loadPage(i)
                // The longest word on the page is found, inside the page.
                val longest = truth(name).map(::norm).filter { w -> w.all { it.isLetter() } }.maxBy { it.length }
                val hits = p.search(longest)
                assertTrue("\"$longest\" is found", hits != null && hits.isNotEmpty())
                val b = p.bounds
                val q = hits!![0][0]
                assertTrue(q.ul_x >= b.x0 - 1 && q.lr_x <= b.x1 + 1 && q.ul_y >= b.y0 - 1 && q.lr_y <= b.y1 + 1)
                p.destroy()
            }
        } finally {
            d.destroy()
        }
        // Read again, nothing is left to read.
        val again = File(ctx.cacheDir, "reading-again.pdf")
        assertEquals(0, Reading.makeSearchable(out.absolutePath, again.absolutePath))
    }

    @Test
    fun anOpenFileGetsItsWordsAsOneUndoableStep() = runBlocking {
        val pictures = File(ctx.cacheDir, "reading-open.pdf")
        Arranger.fromPictures(listOf(asset("ocr-book.jpg").absolutePath), pictures.absolutePath)
        val s = PdfSession.openFile(pictures.absolutePath)
        try {
            assertEquals(listOf(0), s.pagesWithoutText())
            val (bmp, scale) = s.renderForReading(0)
            // The page is the photo: drawn at the photo's own sharpness (1822 pixels over 842 points).
            assertEquals(1822f / 842f, scale, 0.05f)
            val words = try { Reading.words(bmp) } finally { bmp.recycle() }
            assertTrue(words.size > 150)
            assertTrue(s.addReadText(mapOf(0 to Reading.toPage(words, scale))) > 150)
            assertTrue(s.dirty)
            assertEquals(emptyList<Int>(), s.pagesWithoutText())
            assertTrue(s.canUndo)
        } finally {
            s.close()
        }
    }
}
