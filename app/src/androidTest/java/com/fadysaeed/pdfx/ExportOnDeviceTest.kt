package com.fadysaeed.pdfx

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fadysaeed.pdfx.engine.Block
import com.fadysaeed.pdfx.engine.DocxExport
import com.fadysaeed.pdfx.engine.ExportPages
import com.fadysaeed.pdfx.engine.PdfSession
import com.fadysaeed.pdfx.engine.TextExport
import com.fadysaeed.pdfx.engine.XlsxExport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * The GPM book's pages 20–21 (a table on each) and 53–54 (two figures) written out as Word, Excel
 * and text: the tables arrive as tables, the figures as pictures with their captions, the words
 * as paragraphs. The files land in shots/ to be looked at.
 */
@RunWith(AndroidJUnit4::class)
class ExportOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val shots: File = File(ctx.filesDir, "shots").apply { mkdirs() }

    private fun asset(name: String): File {
        val f = File(ctx.cacheDir, "export-$name")
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return f
    }

    private fun entry(zip: File, name: String): String = ZipFile(zip).use { z -> z.getInputStream(z.getEntry(name)).bufferedReader().readText() }

    @Test
    fun aBookBecomesWordExcelAndText() = runBlocking {
        val s = PdfSession.openFile(asset("gpm.pdf").absolutePath)
        try {
            val pages = listOf(19, 20, 52, 53)
            val heard = ArrayList<Int>()
            val blocks = ExportPages.blocks(s, pages, File(ctx.cacheDir, "export-tmp"), withPictures = true) { k, _ -> heard += k }
            assertEquals(listOf(1, 2, 3, 4), heard)
            val tables = blocks.filterIsInstance<Block.Table>()
            val pictures = blocks.filterIsInstance<Block.Picture>()
            assertTrue("tables arrive as tables: ${tables.size}", tables.size >= 2)
            assertTrue("figures arrive as pictures: ${pictures.size}", pictures.size >= 2)
            assertTrue("with their captions", pictures.any { it.caption.startsWith("FIGURE 5") })

            val word = File(shots, "gpm.docx")
            word.outputStream().use { DocxExport.write(blocks, it, "GPM") }
            val doc = entry(word, "word/document.xml")
            assertTrue("Word tables", doc.split("<w:tbl>").size - 1 >= 2)
            assertTrue("pictures in the file", ZipFile(word).use { z -> z.entries().toList().count { it.name.startsWith("word/media/") } } >= 2)
            assertTrue("the table's words are there", doc.contains("Distinctive characteristics of GPM"))

            val text = File(shots, "gpm.txt")
            text.bufferedWriter().use { TextExport.write(blocks, it) }
            val t = text.readText()
            assertTrue("table cells split by tabs", t.lines().any { it.startsWith("Case management\t") })

            val all = ExportPages.tables(s, listOf(19, 20, 28), progress = { _, _ -> })
            assertTrue(all.size >= 3)
            val excel = File(shots, "gpm.xlsx")
            excel.outputStream().use { XlsxExport.write(all, it) }
            val book = entry(excel, "xl/workbook.xml")
            assertTrue("sheets named by page and table: $book", book.contains("p20 Table 1"))
            assertTrue(entry(excel, "xl/worksheets/sheet1.xml").contains("<sheetData>"))
        } finally {
            s.close()
        }
    }
}
