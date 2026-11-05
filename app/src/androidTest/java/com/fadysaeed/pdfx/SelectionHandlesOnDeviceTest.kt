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
}
