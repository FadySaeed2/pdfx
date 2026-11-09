package com.fadysaeed.pdfx

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fadysaeed.pdfx.engine.PdfSession
import com.fadysaeed.pdfx.engine.Selection
import com.fadysaeed.pdfx.ui.viewer.Reader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Dragging a selection's handle: the end not held keeps its word however the finger moves (on
 * the spot, past the other end and back), and a selection made again from its own held points is
 * the same selection, in English and in Arabic, so nothing jumps from word to word.
 */
@RunWith(AndroidJUnit4::class)
class SelectionHandlesOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sample(): File {
        val out = File(ctx.cacheDir, "handles.pdf")
        val doc = PdfDocument()
        val body = Paint().apply { isAntiAlias = true; textSize = 13f; color = Color.BLACK }
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        page.canvas.drawText("هذا سطر عربي للتأكد من عرض الحروف العربية بشكل سليم", 56f, 170f, body)
        for (line in 0 until 10) page.canvas.drawText("Line ${line + 1}: the quick brown fox jumps over the lazy dog, again and again.", 56f, 210f + line * 17f, body)
        doc.finishPage(page)
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
        return out
    }

    /** Made again from the points just inside its two ends, a selection is itself. */
    private suspend fun sameFromItsOwnEnds(s: PdfSession, sel: Selection) {
        var now = sel
        repeat(5) {
            val (ax, ay) = now.startInside
            val (bx, by) = now.endInside
            val again = s.selectWords(now.page, ax, ay, bx, by)
            assertNotNull(again)
            assertEquals("round ${it + 1}", sel.text.trim(), again!!.text.trim())
            now = again
        }
    }

    @Test
    fun aSelectionMadeFromItsOwnEndsStaysTheSame(): Unit = runBlocking {
        val s = PdfSession.open(ctx, Uri.fromFile(sample()))
        try {
            // English: from "quick" in line 2 to "lazy" in line 4.
            val quick = s.searchHits(0, "quick")[1].boxes.first()
            val lazy = s.searchHits(0, "lazy")[3].boxes.first()
            val en = s.selectWords(0, (quick.x0 + quick.x1) / 2, (quick.y0 + quick.y1) / 2, (lazy.x0 + lazy.x1) / 2, (lazy.y0 + lazy.y1) / 2)!!
            assertTrue(en.text, en.text.trim().startsWith("quick") && en.text.trim().endsWith("lazy"))
            sameFromItsOwnEnds(s, en)
            // Arabic: a stretch of the Arabic line, between two points well inside it.
            val ar = s.selectWords(0, 120f, 166f, 330f, 166f)!!
            assertTrue(ar.text, ar.text.any { Character.getDirectionality(it) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC })
            sameFromItsOwnEnds(s, ar)
        } finally {
            s.close()
        }
    }

    @Test
    fun anArabicWordIsTakenWhole(): Unit = runBlocking {
        val words = "هذا سطر عربي للتأكد من عرض الحروف العربية بشكل سليم".split(' ').toSet()
        val s = PdfSession.open(ctx, Uri.fromFile(sample()))
        try {
            val seen = LinkedHashSet<String>()
            var x = 60f
            while (x < 420f) {
                s.selectWord(0, x, 166f)?.let { sel ->
                    val t = sel.text.trim()
                    assertTrue("a whole word, not part of one: \"$t\" at $x", t in words)
                    seen += t
                    // Made again from its own ends, still that word.
                    val (ax, ay) = sel.startInside
                    val (bx, by) = sel.endInside
                    assertEquals(t, s.selectWords(0, ax, ay, bx, by)!!.text.trim())
                }
                x += 9f
            }
            assertTrue("found several words: $seen", seen.size >= 5)
        } finally {
            s.close()
        }
    }

    @Test
    fun theHeldEndKeepsItsWordWhereverTheFingerGoes(): Unit = runBlocking {
        val s = PdfSession.open(ctx, Uri.fromFile(sample()))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val reader = Reader(s, s.pageCount, scope)
            val quick = s.searchHits(0, "quick")[1].boxes.first()
            val lazy = s.searchHits(0, "lazy")[3].boxes.first()
            val fox = s.searchHits(0, "fox")[5].boxes.first()
            reader.showSelection(s.selectWords(0, (quick.x0 + quick.x1) / 2, (quick.y0 + quick.y1) / 2, (lazy.x0 + lazy.x1) / 2, (lazy.y0 + lazy.y1) / 2))
            suspend fun settle() { delay(250) }
            fun text() = reader.selection!!.text.trim()

            // The end handle held on "lazy" and wiggled on the spot: the same words every time.
            reader.beginHandleDrag(start = false)
            val seen = HashSet<String>()
            repeat(20) { k ->
                reader.dragSelection(false, 0, (lazy.x0 + lazy.x1) / 2 + (k % 3 - 1) * 0.4f, (lazy.y0 + lazy.y1) / 2 + (k % 2) * 0.4f)
                settle()
                seen += text()
            }
            reader.endHandleDrag()
            assertEquals("no jumping between words: $seen", 1, seen.size)
            assertTrue(text().startsWith("quick"))

            // The start handle dragged on past the end (to "fox" two lines down) and back: "lazy" stays held.
            reader.beginHandleDrag(start = true)
            reader.dragSelection(true, 0, (fox.x0 + fox.x1) / 2, (fox.y0 + fox.y1) / 2)
            settle()
            assertTrue("past the end, the selection runs from lazy to fox: ${text()}", text().startsWith("lazy") && text().endsWith("fox"))
            reader.dragSelection(true, 0, (quick.x0 + quick.x1) / 2, (quick.y0 + quick.y1) / 2)
            settle()
            reader.endHandleDrag()
            assertTrue("and back, from quick to lazy again: ${text()}", text().startsWith("quick") && text().endsWith("lazy"))
        } finally {
            scope.cancel()
            s.close()
        }
    }

    /**
     * A scanned page read into words: each word stretched over its box, so its letters stand much
     * taller than their type size. A finger at the top or foot of a line's last word still means
     * that word, not the line's first word nor the next line's.
     */
    @Test
    fun onAScannedPageTheEndOfALineStaysTheEnd(): Unit = runBlocking {
        val blank = File(ctx.cacheDir, "scan-blank.pdf")
        PdfDocument().let { d -> d.finishPage(d.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())); FileOutputStream(blank).use { d.writeTo(it) }; d.close() }
        val lines = listOf(
            "The quick brown fox jumps over the lazy dog again",
            "Lithium levels should be checked every three months",
            "Renal function and thyroid tests are also needed here",
        )
        val rnd = java.util.Random(3)
        val words = ArrayList<com.fadysaeed.pdfx.engine.ReadWord>()
        lines.forEachIndexed { k, line ->
            var x = 56f
            val y = 100f + k * 18f
            for (w in line.split(' ')) {
                val tall = w.any { it.isUpperCase() || it in "bdfhklt" }
                val deep = w.any { it in "gjpqy" }
                val top = y - (if (tall) 9f else 6f) + (rnd.nextFloat() - 0.5f) * 1.2f
                val bottom = y + (if (deep) 3f else 0f) + (rnd.nextFloat() - 0.5f) * 1.2f
                val width = w.length * 5.6f
                words += com.fadysaeed.pdfx.engine.ReadWord(w, x, top, x + width, bottom)
                x += width + 4f
            }
        }
        val scanned = File(ctx.cacheDir, "scan-read.pdf")
        val doc = com.artifex.mupdf.fitz.Document.openDocument(blank.absolutePath).asPDF()
        val page = doc.loadPage(0) as com.artifex.mupdf.fitz.PDFPage
        com.fadysaeed.pdfx.engine.TextLayer.add(doc, page, words)
        page.destroy()
        doc.save(scanned.absolutePath, "")
        doc.destroy()

        val s = PdfSession.open(ctx, Uri.fromFile(scanned))
        try {
            val first = words.first { it.text == "The" }
            val again = words.first { it.text == "again" }
            val cx = (again.left + again.right) / 2
            for (y in listOf(again.top + 0.5f, (again.top + again.bottom) / 2, again.bottom - 0.5f)) {
                val sel = s.selectWords(0, (first.left + first.right) / 2, (first.top + first.bottom) / 2, cx, y)!!
                assertEquals("finger at y=$y on the line's last word", lines[0], sel.text.trim())
                val one = s.selectWord(0, cx, y)!!
                assertEquals("a tap at y=$y takes that word", "again", one.text.trim())
            }
        } finally {
            s.close()
        }
    }
    /** A scanned page read into words, [words] written in the order given (as a reader of the scan gives them). */
    private fun scanned(name: String, words: List<com.fadysaeed.pdfx.engine.ReadWord>): File {
        val blank = File(ctx.cacheDir, "$name-blank.pdf")
        PdfDocument().let { d -> d.finishPage(d.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())); FileOutputStream(blank).use { d.writeTo(it) }; d.close() }
        val out = File(ctx.cacheDir, "$name.pdf")
        val doc = com.artifex.mupdf.fitz.Document.openDocument(blank.absolutePath).asPDF()
        val page = doc.loadPage(0) as com.artifex.mupdf.fitz.PDFPage
        com.fadysaeed.pdfx.engine.TextLayer.add(doc, page, words)
        page.destroy()
        doc.save(out.absolutePath, "")
        doc.destroy()
        return out
    }

    /**
     * A table on a scanned page, its words given column by column (as readers of scans often give
     * them): one row selected from its first cell to its last is that row and nothing else, its
     * cells copied with tabs between them; two rows, each on a line of its own.
     */
    @Test
    fun aTableRowOnAScannedPageIsTakenAsSeen(): Unit = runBlocking {
        val table = listOf(
            listOf("Drug", "Daily dose", "Target level"),
            listOf("Lithium carbonate", "800 mg", "0.6 to 0.8"),
            listOf("Valproate", "1000 mg", "50 to 100"),
            listOf("Carbamazepine", "600 mg", "4 to 12"),
            listOf("Lamotrigine", "200 mg", "none"),
        )
        val colX = listOf(60f, 230f, 380f)
        val words = ArrayList<com.fadysaeed.pdfx.engine.ReadWord>()
        for (c in colX.indices) for ((r, row) in table.withIndex()) {
            var x = colX[c]
            val y = 120f + r * 22f
            for (w in row[c].split(' ')) {
                val width = w.length * 6f
                words += com.fadysaeed.pdfx.engine.ReadWord(w, x, y - 9f, x + width, y + 2f)
                x += width + 4f
            }
        }
        val s = PdfSession.open(ctx, Uri.fromFile(scanned("scan-table", words)))
        try {
            fun mid(w: com.fadysaeed.pdfx.engine.ReadWord) = ((w.left + w.right) / 2) to ((w.top + w.bottom) / 2)
            val lithium = mid(words.first { it.text == "Lithium" })
            val eight = mid(words.first { it.text == "0.8" })
            val one = s.selectWords(0, lithium.first, lithium.second, eight.first, eight.second)!!
            assertEquals("one row, as seen", "Lithium carbonate\t800 mg\t0.6 to 0.8", one.text.trim())
            assertTrue("its marks keep to that row: ${one.boxes}", one.boxes.all { (it.y0 + it.y1) / 2 in 130f..152f })
            // Made again from its own ends, still that row.
            val (ax, ay) = one.startInside
            val (bx, by) = one.endInside
            assertEquals(one.text.trim(), s.selectWords(0, ax, ay, bx, by)!!.text.trim())
            // Backwards, from its last cell to its first: the same row.
            assertEquals(one.text.trim(), s.selectWords(0, eight.first, eight.second, lithium.first, lithium.second)!!.text.trim())
            // Two rows.
            val hundred = mid(words.first { it.text == "100" })
            val two = s.selectWords(0, lithium.first, lithium.second, hundred.first, hundred.second)!!
            assertEquals("Lithium carbonate\t800 mg\t0.6 to 0.8\nValproate\t1000 mg\t50 to 100", two.text.trim())
        } finally {
            s.close()
        }
    }

    /** A page in two columns: a paragraph selected in one column is that paragraph, read down the column. */
    @Test
    fun onAPageOfTwoColumnsAParagraphStaysInItsColumn(): Unit = runBlocking {
        val out = File(ctx.cacheDir, "columns.pdf")
        val doc = PdfDocument()
        val body = Paint().apply { isAntiAlias = true; textSize = 11f; color = Color.BLACK }
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val left = (1..8).map { "Left line $it about lithium and the kidney." }
        val right = (1..8).map { "Right line $it on thyroid tests each year." }
        left.forEachIndexed { k, t -> page.canvas.drawText(t, 50f, 100f + k * 15f, body) }
        right.forEachIndexed { k, t -> page.canvas.drawText(t, 310f, 100f + k * 15f, body) }
        doc.finishPage(page)
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
        val s = PdfSession.open(ctx, Uri.fromFile(out))
        try {
            val l2 = s.searchHits(0, "Left line 2")[0].boxes.first()
            val l5 = s.searchHits(0, "kidney")[4].boxes.first()
            val sel = s.selectWords(0, l2.x0 + 2f, (l2.y0 + l2.y1) / 2, (l5.x0 + l5.x1) / 2, (l5.y0 + l5.y1) / 2)!!
            val t = sel.text.trim()
            assertTrue("down the left column: $t", t.startsWith("Left line 2") && t.contains("Left line 4") && t.endsWith("kidney"))
            assertTrue("nothing from the right column, no tabs: $t", !t.contains("Right") && !t.contains('\t'))
            // From the left column on into the right one: still read down the columns.
            val r3 = s.searchHits(0, "year")[2].boxes.first()
            val across = s.selectWords(0, l2.x0 + 2f, (l2.y0 + l2.y1) / 2, (r3.x0 + r3.x1) / 2, (r3.y0 + r3.y1) / 2)!!.text.trim()
            assertTrue("left to its foot, then the right column's top: $across", across.contains("Left line 8") && across.contains("Right line 1") && across.endsWith("year") && !across.contains('\t'))
        } finally {
            s.close()
        }
    }
}

