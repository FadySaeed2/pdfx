package com.fadysaeed.pdfx

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fadysaeed.pdfx.data.Library
import com.fadysaeed.pdfx.data.LibraryDb
import com.fadysaeed.pdfx.data.TextIndexer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * The library on a real Android system with All files access: finding PDFs in shared storage,
 * reading their words into the search index, and renaming, moving, trashing and restoring them.
 */
@RunWith(AndroidJUnit4::class)
class LibraryOnDeviceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val root = Environment.getExternalStorageDirectory()
    private val dir = File(root, "Download/pdfx-ci")
    private lateinit var db: LibraryDb
    private lateinit var lib: Library

    @Before
    fun setUp() {
        instr.uiAutomation.executeShellCommand("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
        Thread.sleep(500)
        assertTrue("All files access should be granted for this test", Environment.isExternalStorageManager())
        dir.deleteRecursively()
        dir.mkdirs()
        db = LibraryDb.inMemory(ctx)
        lib = Library(ctx, db)
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
        File(root, "Documents/pdfx-ci-moved").deleteRecursively()
    }

    private fun write(name: String, lines: List<String>): File {
        val out = File(dir, name)
        val doc = PdfDocument()
        val p = Paint().apply { isAntiAlias = true; textSize = 14f; color = Color.BLACK }
        lines.forEachIndexed { i, line ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
            page.canvas.drawText(line, 56f, 100f, p)
            doc.finishPage(page)
        }
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
        return out
    }

    @Test
    fun findIndexSearchAndManageRealFiles() = runBlocking {
        write("guideline.pdf", listOf("Introduction", "Monitor lithium levels after twelve hours", "Summary"))
        write("bill.pdf", listOf("Electricity account October"))

        val r = lib.scan(listOf(dir))
        assertEquals(2, r.found)
        val docs = db.dao().all()
        assertEquals(setOf("guideline.pdf", "bill.pdf"), docs.map { it.name }.toSet())

        val indexed = TextIndexer(ctx, lib).runAll()
        assertEquals(2, indexed)
        val guide = db.dao().all().first { it.name == "guideline.pdf" }
        assertEquals(3, guide.pages)
        val hits = lib.searchText("lithium")
        assertEquals(1, hits.size)
        assertEquals(guide.id, hits[0].docId)
        assertEquals(1, hits[0].page)

        val renamed = lib.rename(guide, "Mood guideline").getOrThrow()
        assertTrue(File(dir, "Mood guideline.pdf").exists())
        // Renaming keeps the index: nothing needs reading again.
        assertEquals(0, TextIndexer(ctx, lib).runAll())

        val target = File(root, "Documents/pdfx-ci-moved")
        val moved = lib.move(renamed, target).getOrThrow()
        assertTrue(File(target, "Mood guideline.pdf").exists())

        val trashed = lib.trash(moved).getOrThrow()
        assertFalse(File(target, "Mood guideline.pdf").exists())
        val back = lib.restore(trashed).getOrThrow()
        assertTrue(File(back.path!!).exists())
        assertEquals(1, lib.searchText("lithium").size)
    }
}
