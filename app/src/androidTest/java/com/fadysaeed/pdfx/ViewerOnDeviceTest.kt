package com.fadysaeed.pdfx

import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fadysaeed.pdfx.data.Certificates
import com.fadysaeed.pdfx.engine.Box
import com.fadysaeed.pdfx.engine.P12Signer
import com.fadysaeed.pdfx.ui.theme.PdfxTheme
import com.fadysaeed.pdfx.ui.viewer.DigitalAsk
import com.fadysaeed.pdfx.ui.viewer.OpenRequest
import com.fadysaeed.pdfx.ui.viewer.Reader
import com.fadysaeed.pdfx.ui.viewer.ViewerScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The whole viewer screen on a real file, the way it is used: signing keeps the place, and the name takes you to the start. */
@RunWith(AndroidJUnit4::class)
class ViewerOnDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun signingKeepsThePlaceAndTheNameGoesToTheStart() {
        val f = File(ctx.cacheDir, "Discharge-${System.nanoTime()}.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("long-letter.pdf").use { i -> f.outputStream().use { i.copyTo(it) } }
        val store = Certificates(File(ctx.cacheDir, "certs-${System.nanoTime()}"))
        val mine = store.create("Place Test", "", "", "secret9".toCharArray(), remember = false)

        var reader: Reader? = null
        var centre: (() -> Int)? = null
        compose.setContent {
            PdfxTheme {
                ViewerScreen(OpenRequest(Uri.fromFile(f), page = 8), onReader = { r, c -> reader = r; centre = c }) {}
            }
        }
        compose.waitUntil(15_000) { reader?.pages?.aspects?.size == 12 }
        compose.waitForIdle()
        val before = centre!!()
        assertTrue("opened in the middle of the file, at page $before", before in 7..9)

        // Sign on the page being read; the app saves and opens the file again.
        val first = reader!!
        val signer = P12Signer.open(store.open(mine.id).first, "secret9".toCharArray())
        runBlocking { assertTrue(first.signDigitally(DigitalAsk(before, Box(320f, 700f, 540f, 770f)), signer, null, "", "")) }
        compose.waitUntil(20_000) { reader !== first && reader?.docSignatures?.isNotEmpty() == true }
        compose.waitForIdle()
        Thread.sleep(500)
        compose.waitForIdle()
        assertEquals("still on the same page after signing", before, centre!!())

        // The file's name glides to the start, and "Back to page N" goes back.
        compose.onNode(SemanticsMatcher("opens the start") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Go to the start of the file" }).performClick()
        compose.waitUntil(10_000) { centre!!() == 0 }
        assertNotNull(reader!!.backTo)
        compose.onNodeWithText("Back to page ${reader!!.backTo!!.first + 1}").performClick()
        compose.waitUntil(10_000) { centre!!() == before }
        compose.waitForIdle()
        assertEquals(null, reader!!.backTo)
    }
}
