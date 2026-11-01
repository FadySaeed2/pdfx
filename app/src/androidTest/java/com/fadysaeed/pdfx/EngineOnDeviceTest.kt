package com.fadysaeed.pdfx

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Document
import com.fadysaeed.pdfx.engine.PdfSession
import com.fadysaeed.pdfx.engine.Box
import com.fadysaeed.pdfx.engine.MarkKind
import com.fadysaeed.pdfx.data.Library
import com.fadysaeed.pdfx.data.LibraryDb
import com.fadysaeed.pdfx.data.Saver
import com.fadysaeed.pdfx.ui.theme.PdfxTheme
import com.fadysaeed.pdfx.ui.viewer.PaperMode
import com.fadysaeed.pdfx.ui.viewer.Reader
import com.fadysaeed.pdfx.ui.viewer.ViewerContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Runs MuPDF for real on an Android device: opening by Uri the way other apps hand files over,
 * text and search, a password-locked copy, and the viewer drawing actual pages.
 */
@RunWith(AndroidJUnit4::class)
class EngineOnDeviceTest {
    @get:Rule val compose = createComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val shots: File = File(ctx.filesDir, "shots").apply { mkdirs() }

    private fun samplePdf(pages: Int = 5): File {
        val out = File(ctx.cacheDir, "sample-$pages.pdf")
        val doc = PdfDocument()
        val title = Paint().apply { isAntiAlias = true; textSize = 26f; isFakeBoldText = true; color = Color.BLACK }
        val body = Paint().apply { isAntiAlias = true; textSize = 13f; color = Color.rgb(40, 40, 40) }
        val mark = Paint().apply { color = Color.rgb(224, 164, 59) }
        for (p in 1..pages) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, p).create())
            val c = page.canvas
            c.drawText("PDFx engine check", 56f, 90f, title)
            c.drawRect(56f, 104f, 300f, 108f, mark)
            c.drawText("Page $p of $pages. MuPDF renders this text, finds it and reads it back.", 56f, 140f, body)
            c.drawText("هذا سطر عربي للتأكد من عرض الحروف العربية بشكل سليم", 56f, 170f, body)
            for (line in 0 until 34) c.drawText("Line ${line + 1}: the quick brown fox jumps over the lazy dog, again and again.", 56f, 210f + line * 17f, body)
            doc.finishPage(page)
        }
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
        return out
    }

    @Test
    fun opensRendersReadsAndSearchesByUri() = runBlocking {
        val file = samplePdf()
        val s = PdfSession.open(ctx, Uri.fromFile(file))
        try {
            assertFalse(s.needsPassword)
            assertEquals(5, s.pageCount)
            val size = s.pageSize(0)
            assertEquals(595f, size.width, 1f)
            assertEquals(842f, size.height, 1f)

            val bmp = s.render(0, 1080)
            assertEquals(1080, bmp.width)
            assertTrue("rendered page should contain dark text pixels", darkPixels(bmp) > 2000)
            save(bmp, "engine_page1.png")

            assertTrue(s.text(2).contains("Page 3 of 5"))
            assertEquals(1, s.countHits(0, "engine check"))
            assertTrue(s.countHits(4, "lazy dog") >= 30)
        } finally {
            s.close()
        }
    }

    @Test
    fun locksAndUnlocksWithPassword() = runBlocking {
        val plain = samplePdf(2)
        val locked = File(ctx.cacheDir, "locked.pdf")
        val d = Document.openDocument(plain.absolutePath)
        d.asPDF().save(locked.absolutePath, "encrypt=aes-256,user-password=fady,owner-password=owner")
        d.destroy()

        val s = PdfSession.openFile(locked.absolutePath)
        try {
            assertTrue(s.needsPassword)
            assertFalse(s.unlock("wrong"))
            assertTrue(s.unlock("fady"))
            assertEquals(2, s.pageCount)
            assertTrue(s.text(0).contains("engine check"))
        } finally {
            s.close()
        }
    }

    @Test
    fun viewerDrawsRealPagesAndFindsText() {
        val file = samplePdf(12)
        val session = runBlocking { PdfSession.open(ctx, Uri.fromFile(file)) }
        var reader: Reader? = null
        compose.setContent {
            PdfxTheme {
                val scope = rememberCoroutineScope()
                val r = remember { Reader(session, session.pageCount, scope).also { reader = it } }
                val pages = r.pages!!
                ViewerContent(
                    name = "Engine check",
                    reader = r,
                    aspects = pages.aspects,
                    widths = pages.widths,
                    image = { pages.images[it] },
                    tile = { pages.tiles[it] },
                    listState = rememberLazyListState(),
                    onWant = pages::want,
                    onTiles = pages::wantTiles,
                    onBack = {},
                )
            }
        }
        compose.waitUntil(20_000) { (reader?.pages?.images?.size ?: 0) >= 2 }
        compose.runOnIdle { reader!!.searchOpen = true; reader!!.search("lazy dog", 0) }
        compose.waitUntil(20_000) { reader!!.order.size >= 12 * 34 && !reader!!.searching }
        assertEquals(0, reader!!.currentHit()!!.first)
        compose.waitForIdle()
        Thread.sleep(500)
        save(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(), "viewer_search_hits.png")

        compose.runOnIdle { reader!!.closeSearch(); reader!!.changeMode(PaperMode.NIGHT) }
        compose.waitForIdle()
        Thread.sleep(500)
        save(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(), "viewer_night.png")
        compose.runOnIdle { reader!!.changeMode(PaperMode.PAPER) }
        session.close()
    }

    @Test
    fun selectsCopiesAndDrawsRegions() = runBlocking {
        val file = samplePdf(2)
        val s = PdfSession.open(ctx, Uri.fromFile(file))
        try {
            // "engine" sits in the title, drawn at x=56..300, baseline y=90 (26pt bold).
            val sel = s.selectWord(0, 150f, 82f)
            assertTrue("a word should be selected", sel != null && sel.text.isNotBlank())
            val wider = s.select(0, 57f, 82f, 299f, 82f)!!
            assertTrue(wider.text.contains("engine check"))
            assertTrue(wider.boxes.isNotEmpty())

            val hits = s.search(1, "lazy dog")
            assertEquals(34, hits.size)
            assertTrue(hits[0][0].y0 > 200f)

            val region = s.renderRegion(0, 3000, 200, 200, 600, 400)
            assertEquals(600, region.width)
            assertTrue(darkPixels(region) > 500)

            assertTrue(s.outline().isEmpty())
            assertTrue(s.links(0).isEmpty())
            val paras = s.paragraphs(0)
            val title = paras.first { it.text.contains("engine check") }
            assertEquals("the large title reads as a heading", 1, title.level)
            assertTrue(paras.any { it.level == 0 && it.text.contains("lazy dog") })
        } finally {
            s.close()
        }
    }

    @Test
    fun marksUndoSaveAndReopen() = runBlocking<Unit> {
        val root = File(ctx.cacheDir, "marks-${System.nanoTime()}").apply { mkdirs() }
        val src = samplePdf(3)
        val f = File(root, "Marked.pdf")
        src.copyTo(f, overwrite = true)
        val db = LibraryDb.inMemory(ctx)
        val lib = Library(ctx, db, root, allFilesAccess = { true })
        lib.scan()
        val doc = db.dao().all().single()

        val s = PdfSession.open(ctx, Uri.fromFile(f))
        try {
            assertTrue(s.editable)
            val words = s.select(0, 57f, 82f, 299f, 82f)!!
            assertTrue(s.addTextMark(0, MarkKind.HIGHLIGHT, words.boxes, 0xFFFFD54A.toInt()))
            assertTrue(s.addInk(0, listOf(listOf(100f to 400f, 200f to 420f, 300f to 400f)), 0xFFE5484D.toInt(), 2f))
            assertTrue(s.addShape(1, MarkKind.ARROW, 100f, 100f, 300f, 200f, 0xFF2F6FED.toInt(), 2f))
            assertTrue(s.addTextBox(1, 80f, 600f, "Check with the team\nملاحظة بالعربي", 0xFF1A1A1A.toInt(), 15f))
            assertTrue(s.addNote(2, 400f, 100f, "Ask about the renal guidance", 0xFFFF7FAE.toInt()))

            val m0 = s.marks(0)
            assertEquals(setOf(MarkKind.HIGHLIGHT, MarkKind.INK), m0.map { it.kind }.toSet())
            assertTrue(m0.first { it.kind == MarkKind.HIGHLIGHT }.quoted.contains("engine check"))
            assertEquals(setOf(MarkKind.ARROW, MarkKind.TEXT), s.marks(1).map { it.kind }.toSet())
            assertEquals(5, s.allMarks().size)
            save(s.render(1, 1080), "marks_page2.png")

            assertTrue(s.canUndo)
            assertTrue(s.undo())
            assertEquals(4, s.allMarks().size)
            assertTrue(s.redo())
            assertEquals(5, s.allMarks().size)

            // Edit a text box, then move it; resize a drawing; move a note.
            val box = s.marks(1).first { it.kind == MarkKind.TEXT }
            assertEquals(15f, box.size, 0.5f)
            assertTrue(s.editTextBox(1, box.index, "Moved and edited\nنص معدّل", 20f, 0xFF2F6FED.toInt()))
            val edited = s.marks(1).first { it.kind == MarkKind.TEXT }
            assertEquals("Moved and edited\nنص معدّل", edited.note)
            assertEquals(20f, edited.size, 0.5f)
            assertEquals(0xFF2F6FED.toInt(), edited.color)
            assertTrue(s.reshapeMark(1, edited.index, Box(300f, 300f, 300f + edited.box.width, 300f + edited.box.height)))
            val moved = s.marks(1).first { it.kind == MarkKind.TEXT }
            assertEquals(300f, moved.box.x0, 2f)
            assertEquals(300f, moved.box.y0, 2f)
            val ink = s.marks(0).first { it.kind == MarkKind.INK }
            val bigger = Box(ink.box.x0, ink.box.y0, ink.box.x0 + ink.box.width * 1.5f, ink.box.y0 + ink.box.height * 2f)
            assertTrue(s.reshapeMark(0, ink.index, bigger))
            assertEquals(bigger.width, s.marks(0).first { it.kind == MarkKind.INK }.box.width, 4f)
            val note = s.marks(2).first { it.kind == MarkKind.NOTE }
            assertTrue(s.reshapeMark(2, note.index, Box(100f, 500f, 100f + note.box.width, 500f + note.box.height)))
            assertEquals(100f, s.marks(2).first { it.kind == MarkKind.NOTE }.box.x0, 2f)
            assertEquals(5, s.allMarks().size)
            save(s.render(1, 1080), "marks_moved_page2.png")
            assertTrue(s.undo())
            assertTrue(s.redo())

            val saved = Saver(lib).save(s, doc, Uri.fromFile(f)).getOrThrow()
            assertTrue(saved is Saver.Saved.InPlace)
            assertEquals(1, lib.versions.list(doc.id).size)
        } finally {
            s.close()
        }

        // The saved file opens on its own and holds every mark.
        val again = PdfSession.open(ctx, Uri.fromFile(f))
        try {
            assertEquals(3, again.pageCount)
            assertEquals(5, again.allMarks().size)
            assertTrue(again.allMarks().any { it.kind == MarkKind.NOTE && it.note.contains("renal") })
            val t = again.allMarks().first { it.kind == MarkKind.TEXT }
            assertEquals(300f, t.box.x0, 2f)
            assertTrue(t.note.startsWith("Moved and edited"))
            // Flattening turns marks into page content.
            assertTrue(again.flatten())
            assertTrue(again.allMarks().none { it.kind == MarkKind.HIGHLIGHT || it.kind == MarkKind.INK || it.kind == MarkKind.ARROW })
        } finally {
            again.close()
        }
        db.close()
        root.deleteRecursively()
    }

    private fun darkPixels(b: Bitmap): Int {
        var n = 0
        val step = 3
        for (y in 0 until b.height step step) for (x in 0 until b.width step step) {
            val c = b.getPixel(x, y)
            if (Color.alpha(c) > 200 && Color.red(c) < 90 && Color.green(c) < 90 && Color.blue(c) < 90) n++
        }
        return n * step * step
    }

    private fun save(b: Bitmap, name: String) {
        FileOutputStream(File(shots, name)).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
