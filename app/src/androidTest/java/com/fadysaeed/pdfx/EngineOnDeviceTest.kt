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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import com.fadysaeed.pdfx.ui.viewer.Tool
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Document
import com.fadysaeed.pdfx.engine.PdfSession
import com.fadysaeed.pdfx.engine.Box
import com.fadysaeed.pdfx.engine.BoxAlign
import com.fadysaeed.pdfx.engine.BoxFont
import com.fadysaeed.pdfx.engine.BoxStyle
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
    fun fillsFormsAndSigns() = runBlocking<Unit> {
        val f = File(ctx.cacheDir, "form-${System.nanoTime()}.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("form.pdf").use { i -> f.outputStream().use { i.copyTo(it) } }
        val s = PdfSession.open(ctx, Uri.fromFile(f))
        try {
            assertTrue(s.hasForm())
            val fields = s.fields(0)
            assertEquals(8, fields.size)
            val name = fields.first { it.name == "full_name" }
            val dob = fields.first { it.name == "date_of_birth" }
            assertTrue(dob.isDate)
            assertEquals("dd/MM/yyyy", dob.datePattern)
            assertTrue(fields.first { it.name == "referral_date" }.isDate)
            assertTrue(fields.first { it.name == "reason" }.multiline)
            val consent = fields.first { it.name == "consent" }
            val radios = fields.filter { it.name == "smoker" }
            val clinic = fields.first { it.name == "clinic" }
            assertEquals(listOf("Armadale", "Fremantle", "Joondalup"), clinic.options)

            assertTrue(s.setFieldText(0, name.index, "Fady Saeed"))
            assertTrue(s.setFieldText(0, dob.index, "05/11/1980"))
            assertTrue(s.setFieldText(0, fields.first { it.name == "reason" }.index, "Low mood for six weeks.\nمراجعة العلاج"))
            assertTrue(s.toggleField(0, consent.index))
            assertTrue(s.toggleField(0, radios[1].index))
            assertTrue(s.setFieldChoice(0, clinic.index, "Fremantle"))
            val now = s.fields(0)
            assertEquals("Fady Saeed", now.first { it.name == "full_name" }.value)
            assertTrue("consent ticked", now.first { it.name == "consent" }.on)
            val r2 = now.filter { it.name == "smoker" }
            assertTrue("second radio on, first off", r2[1].on && !r2[0].on)
            assertEquals("Fremantle", now.first { it.name == "clinic" }.value)

            // A drawn signature and a photographed one.
            assertTrue(s.addInk(0, listOf(listOf(180f to 420f, 200f to 400f, 220f to 430f, 260f to 405f)), 0xFF1A1A1A.toInt(), 1.6f))
            val png = File(ctx.cacheDir, "sig.png")
            val bmp = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bmp).drawLine(20f, 70f, 280f, 30f, Paint().apply { color = Color.BLACK; strokeWidth = 6f })
            FileOutputStream(png).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(s.addImageStamp(0, Box(300f, 390f, 450f, 440f), png.absolutePath))
            assertTrue(s.marks(0).any { it.kind == MarkKind.STAMP })
            save(s.render(0, 1080), "form_filled.png")
            s.writeTo(f.absolutePath + ".out", false)
        } finally {
            s.close()
        }
        val again = PdfSession.open(ctx, Uri.fromFile(File(f.absolutePath + ".out")))
        try {
            val fs = again.fields(0)
            assertEquals("Fady Saeed", fs.first { it.name == "full_name" }.value)
            assertTrue(fs.first { it.name == "consent" }.on)
            assertEquals("Fremantle", fs.first { it.name == "clinic" }.value)
            assertTrue(again.marks(0).any { it.kind == MarkKind.STAMP } && again.marks(0).any { it.kind == MarkKind.INK })
        } finally {
            again.close()
        }
    }

    /** Real taps on the screen, as a finger makes them, place a signature, a stamp, a tick and a date. */
    @Test
    fun tapsPlaceSignaturesAndStamps() {
        val file = samplePdf(2)
        val session = runBlocking { PdfSession.open(ctx, Uri.fromFile(file)) }
        var reader: Reader? = null
        compose.setContent {
            PdfxTheme {
                val scope = rememberCoroutineScope()
                val r = remember { Reader(session, session.pageCount, scope).also { reader = it } }
                val pages = r.pages!!
                ViewerContent(
                    name = "Sign check", reader = r, aspects = pages.aspects, widths = pages.widths,
                    image = { pages.images[it] }, tile = { pages.tiles[it] }, listState = rememberLazyListState(),
                    onWant = pages::want, onTiles = pages::wantTiles, onBack = {},
                )
            }
        }
        compose.waitUntil(20_000) { (reader?.pages?.images?.size ?: 0) >= 1 }
        val r = reader!!
        val sign = com.fadysaeed.pdfx.data.SavedSign("t", false, listOf(listOf(0f to 0.2f, 0.3f to 0f, 0.6f to 0.3f, 1f to 0.1f)), 3f, Color.BLACK)
        fun countOn(kind: MarkKind) = runBlocking { session.marks(0) }.count { it.kind == kind }
        fun tapAt(fx: Float, fy: Float) {
            compose.onNodeWithContentDescription("Page 1").performTouchInput {
                // A finger never lands perfectly still: a small wobble, as on a real phone.
                down(Offset(width * fx, height * fy)); moveBy(Offset(9f, 7f)); up()
            }
            compose.waitForIdle()
        }
        val steps = listOf(
            Triple(Tool.SIGN, MarkKind.INK, 0.5f to 0.55f),
            Triple(Tool.STAMP, MarkKind.TEXT, 0.5f to 0.7f),
            Triple(Tool.TICK, MarkKind.INK, 0.2f to 0.8f),
            Triple(Tool.DATE, MarkKind.TEXT, 0.6f to 0.85f),
        )
        compose.runOnIdle { r.setSignsForTest(listOf(sign)); r.pickSign("t"); r.startMarking() }
        for ((tool, kind, at) in steps) {
            val before = countOn(kind)
            compose.runOnIdle { r.selectMark(null); r.pickTool(tool) }
            tapAt(at.first, at.second)
            compose.waitUntil(10_000) { countOn(kind) > before }
        }
        // And with the pen in use, a finger tap still places things.
        compose.runOnIdle { r.stylusSeen = true; r.selectMark(null); r.pickTool(Tool.CROSS) }
        val inkBefore = countOn(MarkKind.INK)
        tapAt(0.3f, 0.9f)
        compose.waitUntil(10_000) { countOn(MarkKind.INK) > inkBefore }
        compose.waitForIdle()
        Thread.sleep(400)
        save(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(), "viewer_signed.png")
        session.close()
    }

    /** A page with a ruled table of drugs, doses and checks, between two paragraphs. */
    private fun tablePdf(): File {
        val out = File(ctx.cacheDir, "table.pdf")
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val c = page.canvas
        val body = Paint().apply { isAntiAlias = true; textSize = 12f; color = Color.BLACK }
        val bold = Paint(body).apply { isFakeBoldText = true }
        val rule = Paint().apply { color = Color.BLACK; strokeWidth = 0.8f; style = Paint.Style.STROKE }
        c.drawText("Mood stabilisers are monitored as shown in the table below.", 56f, 100f, body)
        val rows = listOf(listOf("Drug", "Dose", "Check"), listOf("Lithium", "400 mg", "Level at 12 h"), listOf("Valproate", "500 mg", "Liver tests"), listOf("Lamotrigine", "25 mg", "Rash"))
        val xs = listOf(56f, 206f, 336f, 520f)
        val top = 130f
        val h = 24f
        rows.forEachIndexed { r, cells ->
            cells.forEachIndexed { k, t -> c.drawText(t, xs[k] + 6f, top + r * h + 16f, if (r == 0) bold else body) }
        }
        for (r in 0..rows.size) c.drawLine(xs.first(), top + r * h, xs.last(), top + r * h, rule)
        for (x in xs) c.drawLine(x, top, x, top + rows.size * h, rule)
        c.drawText("Check levels again after any change in dose.", 56f, top + rows.size * h + 40f, body)
        doc.finishPage(page)
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
        return out
    }

    @Test
    fun tablePagesKeepTheirWords() = runBlocking<Unit> {
        val s = PdfSession.open(ctx, Uri.fromFile(tablePdf()))
        try {
            val paras = s.paragraphs(0)
            val table = paras.firstNotNullOfOrNull { it.table }
            File(shots, "table_result.txt").writeText(paras.joinToString("\n") { p ->
                val t = p.table
                when {
                    t != null -> "TABLE ${t.kind} header=${t.header} " + t.rows.joinToString(" | ") { it.cells.joinToString(" ; ") }
                    p.figure != null -> "FIGURE ${p.figure}"
                    else -> "PARA ${p.text}"
                }
            })
            // A ruled grid is a table: its cells come from its lines, and the text around it stays text.
            assertTrue("the paragraphs around the table stay", paras.any { it.text.contains("monitored") } && paras.any { it.text.contains("after any change") })
            assertTrue("the grid is a table", table != null)
            val cells = table!!.header + table.rows.flatMap { it.cells }
            assertTrue("every cell is there: $cells", listOf("Drug", "Valproate", "500 mg", "Lamotrigine", "Rash", "Level at 12 h").all { it in cells })
            assertTrue("three columns", table.rows.all { it.cells.size == 3 })
            assertTrue("the table's words are not loose text too", paras.none { it.table == null && it.text.contains("Valproate") })
        } finally {
            s.close()
        }
    }

    @Test
    fun selectsCopiesAndDrawsRegions() = runBlocking {
        val file = samplePdf(2)
        val s = PdfSession.open(ctx, Uri.fromFile(file))
        try {
            // "engine" sits in the title, drawn at x=56..300, baseline y=90 (26pt bold).
            val sel = s.selectWord(0, 150f, 82f)
            assertTrue("a word should be selected", sel != null && sel.text.isNotBlank())
            // Word selection never stops mid-word.
            val words2 = s.selectWords(0, 125f, 82f, 170f, 82f)!!.text.trim()
            assertTrue("whole words: '$words2'", words2.isNotEmpty() && words2.split(Regex("\\s+")).all { it in setOf("PDFx", "engine", "check") })
            val wider = s.select(0, 57f, 82f, 299f, 82f)!!
            assertTrue(wider.text.contains("engine check"))
            assertTrue(wider.boxes.isNotEmpty())

            val hits = s.search(1, "lazy dog")
            assertEquals(34, hits.size)
            assertTrue(hits[0][0].y0 > 200f)
            // Each hit comes with the words around it.
            val words = s.searchHits(1, "lazy dog")
            assertEquals(34, words.size)
            assertTrue(words[0].before.contains("brown fox"))
            assertTrue(words[0].match.equals("lazy dog", ignoreCase = true))
            // Arabic: hamza and short vowels don't stop a match.
            assertTrue("Arabic search should ignore the hamza", s.searchHits(0, "للتاكد").isNotEmpty())

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
            assertTrue(s.addTextMark(0, MarkKind.HIGHLIGHT, words.boxes, 0xFFFFD54A.toInt(), 0.35f))
            val hl = s.marks(0).first { it.kind == MarkKind.HIGHLIGHT }
            assertEquals(0.35f, hl.opacity, 0.02f)
            assertTrue(s.setMarkOpacity(0, hl.index, 0.6f))
            assertTrue(s.setMarkColor(0, hl.index, 0xFFFF7FAE.toInt()))
            assertEquals(0.6f, s.marks(0).first { it.kind == MarkKind.HIGHLIGHT }.opacity, 0.02f)
            assertEquals(0xFFFF7FAE.toInt(), s.marks(0).first { it.kind == MarkKind.HIGHLIGHT }.color)
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
            // The whole box's style: font, weight, alignment, background and border.
            assertTrue(s.editTextBox(1, edited.index, edited.note, BoxStyle(font = BoxFont.SERIF, size = 20f, bold = true, italic = true, align = BoxAlign.CENTER, color = 0xFF2F6FED.toInt(), fill = 0xFFFFF4C2.toInt(), border = true)))
            val styledMark = s.marks(1).first { it.kind == MarkKind.TEXT }
            val styled = styledMark.style!!
            val raw = s.textBoxRaw(1, styledMark.index)
            assertEquals("stored: $raw", BoxFont.SERIF, styled.font)
            assertTrue("stored: $raw", styled.bold && styled.italic && styled.border && styled.fill != null)
            assertEquals("stored: $raw", BoxAlign.CENTER, styled.align)
            assertEquals(20f, styled.size, 0.5f)
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
            // Several strokes at once: width, move and delete as single steps.
            assertTrue(s.addInk(2, listOf(listOf(100f to 700f, 120f to 720f)), 0xFF1A1A1A.toInt(), 2f))
            assertTrue(s.addInk(2, listOf(listOf(130f to 700f, 150f to 720f)), 0xFF1A1A1A.toInt(), 2f))
            val two = s.marks(2).filter { it.kind == MarkKind.INK }
            assertEquals(2, two.size)
            assertTrue(two.all { it.path.isNotEmpty() })
            assertTrue("a tap on the stroke finds it", two[0].distanceTo(110f, 710f) < 0.5f)
            assertTrue("a tap away from it does not", two[0].distanceTo(110f, 740f) > 10f)
            assertTrue(s.setMarksWidth(2, two.map { it.index }, 3.6f))
            assertTrue(s.marks(2).filter { it.kind == MarkKind.INK }.all { kotlin.math.abs(it.width - 3.6f) < 0.1f })
            assertTrue(s.reshapeMarks(2, two.map { it.index to Box(it.box.x0 + 50f, it.box.y0, it.box.x1 + 50f, it.box.y1) }))
            assertEquals(two[0].box.x0 + 50f, s.marks(2).filter { it.kind == MarkKind.INK }[0].box.x0, 2f)
            assertTrue(s.deleteMarks(2, two.map { it.index }))
            assertTrue(s.marks(2).none { it.kind == MarkKind.INK })
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
