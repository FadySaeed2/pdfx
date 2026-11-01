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
import com.fadysaeed.pdfx.ui.theme.PdfxTheme
import com.fadysaeed.pdfx.ui.viewer.PageImages
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
    fun viewerDrawsRealPages() {
        val file = samplePdf(12)
        val session = runBlocking { PdfSession.open(ctx, Uri.fromFile(file)) }
        var pages: PageImages? = null
        compose.setContent {
            PdfxTheme {
                val scope = rememberCoroutineScope()
                val images = remember { PageImages(session, scope).also { pages = it } }
                ViewerContent(
                    name = "Engine check.pdf",
                    pageCount = session.pageCount,
                    aspects = images.aspects,
                    image = { images.images[it] },
                    listState = rememberLazyListState(),
                    onWant = images::want,
                    onBack = {},
                )
            }
        }
        compose.waitUntil(20_000) { (pages?.images?.size ?: 0) >= 2 }
        compose.waitForIdle()
        Thread.sleep(400)
        // A system screenshot: drawing the Compose view from the test thread races the UI thread.
        save(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(), "viewer_real_pages.png")
        session.close()
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
