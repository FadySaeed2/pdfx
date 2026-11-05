package com.fadysaeed.pdfx

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fadysaeed.pdfx.engine.PdfSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.cancel
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A form that works out its own sums (an invoice: amount = quantity × price): changing the
 * quantity changes the amount, kept when saved, and a box that takes numbers says no
 * to letters rather than dropping them silently.
 */
@RunWith(AndroidJUnit4::class)
class FormSumsOnDeviceTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aFormsOwnSumsFollowWhatIsFilledIn(): Unit = runBlocking {
        val f = File(ctx.cacheDir, "calc-form.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("calc-form.pdf").use { i -> f.outputStream().use { i.copyTo(it) } }
        val s = PdfSession.open(ctx, Uri.fromFile(f))
        try {
            fun index(name: String) = runBlocking { s.fields(0).first { it.name == name }.index }
            assertTrue(s.setFieldText(0, index("Quantity"), "4"))
            val amount = s.fields(0).first { it.name == "Amount" }
            assertEquals("10000", amount.value)
            // Letters in a box for a number: turned down, and said.
            val r = runCatching { s.setFieldText(0, index("Quantity"), "abc") }
            assertTrue("refused: $r", r.isFailure)
            assertEquals("4", s.fields(0).first { it.name == "Quantity" }.value)
            // Saved and opened again, the new sums are in the file.
            val out = File(ctx.cacheDir, "calc-form-saved.pdf")
            s.writeTo(out.absolutePath, incremental = false)
            val again = PdfSession.open(ctx, Uri.fromFile(out))
            try {
                assertEquals("10000", again.fields(0).first { it.name == "Amount" }.value)
            } finally {
                again.close()
            }
        } finally {
            s.close()
        }
    }

    /** With Edit text in hand, a tap on a form's box opens that box; the boxes are known as soon as the page shows. */
    @Test
    fun editTextOpensAFormsBox(): Unit = runBlocking {
        val f = java.io.File(ctx.cacheDir, "calc-form-edit.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("calc-form.pdf").use { i -> f.outputStream().use { i.copyTo(it) } }
        val s = PdfSession.open(ctx, Uri.fromFile(f))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
        try {
            val r = com.fadysaeed.pdfx.ui.viewer.Reader(s, s.pageCount, scope)
            // The page shows at once, before the reader has found out the file has a form.
            InstrumentationRegistry.getInstrumentation().runOnMainSync { r.loadMarks(0) }
            var waited = 0
            while ((r.fields[0].isNullOrEmpty()) && waited < 5000) { kotlinx.coroutines.delay(100); waited += 100 }
            assertTrue("the page's boxes are known without typing anything", !r.fields[0].isNullOrEmpty())
            val qty = r.fields[0]!!.first { it.name == "Quantity" }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                r.startMarking()
                r.pickTool(com.fadysaeed.pdfx.ui.viewer.Tool.EDIT)
                val x = (qty.box.x0 + qty.box.x1) / 2
                val y = (qty.box.y0 + qty.box.y1) / 2
                r.begin(0, x, y, eraser = false)
                r.finish()
            }
            assertEquals("the box opens to fill in", "Quantity", r.editingField?.name)
        } finally {
            scope.cancel()
            s.close()
        }
    }
}
