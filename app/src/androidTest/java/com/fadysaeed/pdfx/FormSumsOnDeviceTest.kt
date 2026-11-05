package com.fadysaeed.pdfx

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fadysaeed.pdfx.engine.PdfSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}
