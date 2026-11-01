package com.fadysaeed.pdfx

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/** Turning the phone while a file is open keeps it open, on the same screen. */
@RunWith(AndroidJUnit4::class)
class RotationOnDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sample(): File {
        val out = File(ctx.cacheDir, "rotate.pdf")
        val doc = PdfDocument()
        val p = Paint().apply { isAntiAlias = true; textSize = 16f; color = Color.BLACK }
        for (n in 1..3) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n).create())
            page.canvas.drawText("Rotation check, page $n", 56f, 100f, p)
            doc.finishPage(page)
        }
        FileOutputStream(out).use { doc.writeTo(it) }
        doc.close()
        return out
    }

    private fun pageShown() = compose.onAllNodesWithContentDescription("Page 1").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun turningTheScreenKeepsTheFileOpen() {
        val f = sample()
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(f), "application/pdf")
            .setClass(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { sc ->
            compose.waitUntil(20_000) { pageShown() }
            var before: Activity? = null
            sc.onActivity { before = it; it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            Thread.sleep(3000)
            compose.waitForIdle()
            var after: Activity? = null
            sc.onActivity { after = it }
            assertSame("the screen was not rebuilt", before, after)
            assertTrue("the file is still open after turning", pageShown())
            sc.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            Thread.sleep(2000)
            compose.waitForIdle()
            assertTrue("and after turning back", pageShown())
            sc.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }
}
