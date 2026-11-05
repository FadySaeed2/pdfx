package com.fadysaeed.pdfx

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * A PDF opened straight from another app (WhatsApp, an email), the way the phone hands it over:
 * a link PDFx may read for a while, never a path. With access to the phone's files, as on a real
 * phone, it opens, becomes a real file in Documents/PDFx/Received, and is in Recent afterwards,
 * whether PDFx was closed or already open in the library.
 */
@RunWith(AndroidJUnit4::class)
class OpenFromAppOnDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx = ins.targetContext

    private fun shell(cmd: String) {
        ins.uiAutomation.executeShellCommand(cmd).close()
        Thread.sleep(300)
    }

    @Before
    fun allowFiles() = shell("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE allow")

    @After
    fun backAsItWas() = shell("appops set ${ctx.packageName} MANAGE_EXTERNAL_STORAGE default")

    /** A small PDF somewhere only a link reaches, called [name]. */
    private fun outside(name: String): File {
        val f = File(ctx.cacheDir, "from-app-${System.nanoTime()}/$name").apply { parentFile?.mkdirs() }
        val doc = PdfDocument()
        val p = Paint().apply { isAntiAlias = true; textSize = 16f; color = Color.BLACK }
        for (n in 1..2) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create())
            page.canvas.drawText("$name, page $n, ${System.nanoTime()}", 56f, 100f, p)
            doc.finishPage(page)
        }
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return f
    }

    private fun viewIntent(f: File): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f), "application/pdf")
            .setClass(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun pageShown() = compose.onAllNodesWithContentDescription("Page 1").fetchSemanticsNodes().isNotEmpty()

    private fun shown(text: String) = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun inRecent(base: String): Boolean = runBlocking {
        val app = ctx.applicationContext as PdfxApp
        app.library.dao.all().any { it.name == "$base.pdf" && !it.inTrash && it.lastOpened > 0 && it.path != null }
    }

    @Test
    fun aFileOpenedFromAnotherAppIsInRecent() {
        val base = "Clinic letter ${System.nanoTime() % 100000}"
        val f = outside("$base.pdf")
        ActivityScenario.launch<MainActivity>(viewIntent(f)).use { sc ->
            compose.waitUntil(30_000) { pageShown() }
            // Read a moment, as a person would, then back to the library.
            Thread.sleep(1500)
            compose.waitUntil(20_000) { inRecent(base) }
            sc.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(20_000) { shown(base) }
            // Still there once the library has looked round the phone again.
            sc.onActivity { a -> (a.applicationContext as PdfxApp).let { runBlocking { it.library.scan() } } }
            Thread.sleep(1000)
            assertTrue("in Recent after a look round", inRecent(base))
            compose.waitUntil(20_000) { shown(base) }

            // Already open in the library: a second file handed over the same way.
            val base2 = "Referral note ${System.nanoTime() % 100000}"
            val f2 = outside("$base2.pdf")
            ctx.startActivity(viewIntent(f2))
            compose.waitUntil(30_000) { pageShown() }
            Thread.sleep(1500)
            compose.waitUntil(20_000) { inRecent(base2) }
            sc.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(20_000) { shown(base2) }
            f2.parentFile?.deleteRecursively()
        }
        f.parentFile?.deleteRecursively()
    }
}
