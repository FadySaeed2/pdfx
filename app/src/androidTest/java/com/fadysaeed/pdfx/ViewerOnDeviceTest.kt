package com.fadysaeed.pdfx

import android.net.Uri
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
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
    /**
     * Compose's test host now and then trips over its own threads on the emulator ("Detected
     * multithreaded access to SnapshotStateObserver"), which says nothing about PDFx: such a test
     * runs once more. Any other failure fails at once.
     */
    @get:Rule(order = 0) val retry = org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                try { base.evaluate() } catch (t: Throwable) {
                    val flake = generateSequence(t) { it.cause }.any { it.message?.contains("multithreaded access to SnapshotStateObserver") == true || it.message?.contains("performMeasureAndLayout called during measure layout") == true }
                    if (!flake) throw t
                    base.evaluate()
                }
            }
        }
    }
    @get:Rule(order = 1) val compose = createComposeRule()
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

    @Test
    fun pagesScreenTakesAPageOutAndSaves() {
        val f = File(ctx.cacheDir, "Chapters-${System.nanoTime()}.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("organise.pdf").use { i -> f.outputStream().use { i.copyTo(it) } }
        var reader: Reader? = null
        compose.setContent {
            PdfxTheme { ViewerScreen(OpenRequest(Uri.fromFile(f), page = 0), onReader = { r, _ -> reader = r }) {} }
        }
        compose.waitUntil(15_000) { reader?.pages?.aspects?.size == 6 }
        compose.waitForIdle()
        val first = reader!!
        compose.onNode(SemanticsMatcher("opens tools") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.firstOrNull()?.startsWith("Tools:") == true }).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Arrange pages").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        save(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(), "tools_sheet.png")
        compose.onNodeWithText("Arrange pages").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("hold and drag to move", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodes(SemanticsMatcher("page 2 drawn") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.firstOrNull() == "Small picture of page 2" }).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(SemanticsMatcher("page 2") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.firstOrNull() == "Small picture of page 2" }).performClick()
        compose.onNodeWithText("1 of 6 picked").assertExists()
        compose.onNodeWithText("Take out").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(20_000) { reader !== first && reader?.pages?.aspects?.size == 5 }
        compose.waitForIdle()
        save(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(), "pages_saved.png")
        val d = com.artifex.mupdf.fitz.Document.openDocument(f.absolutePath)
        try {
            assertEquals(5, d.countPages())
            val p = d.loadPage(1)
            val t = p.toStructuredText().asText()
            p.destroy()
            assertTrue("page 2 is now chapter 3: $t", t.contains("marker-3"))
        } finally {
            d.destroy()
        }
        // Undo puts the file back as it was before the pages were saved.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }
        val saved = reader
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(20_000) { reader !== saved && reader?.pages?.aspects?.size == 6 }
        val back = com.artifex.mupdf.fitz.Document.openDocument(f.absolutePath)
        try { assertEquals(6, back.countPages()) } finally { back.destroy() }
    }

    @Test
    fun fileToolsLockTheFileWithAPassword() {
        val f = File(ctx.cacheDir, "Locked-${System.nanoTime()}.pdf")
        InstrumentationRegistry.getInstrumentation().context.assets.open("organise.pdf").use { i -> f.outputStream().use { i.copyTo(it) } }
        var reader: Reader? = null
        compose.setContent {
            PdfxTheme { ViewerScreen(OpenRequest(Uri.fromFile(f), page = 0), onReader = { r, _ -> reader = r }) {} }
        }
        compose.waitUntil(15_000) { reader?.pages?.aspects?.size == 6 }
        compose.waitForIdle()
        val first = reader!!
        compose.onNode(SemanticsMatcher("opens tools") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.firstOrNull()?.startsWith("Tools:") == true }).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Lock with a password").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        save(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(), "file_tools.png")
        compose.onNodeWithText("Lock with a password").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Once more").fetchSemanticsNodes().isNotEmpty() }
        val pw = "Clozapine, 400"
        compose.onNodeWithContentDescription("Password").performTextInput(pw)
        compose.onNodeWithContentDescription("Once more").performTextInput(pw)
        compose.onNodeWithText("Replace this file").performClick()
        compose.waitForIdle()
        save(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot(), "password_dialog.png")
        compose.onNodeWithText("Lock").performClick()
        compose.waitUntil(20_000) { reader !== first && reader?.pages?.aspects?.size == 6 }
        compose.waitForIdle()
        assertTrue("the file stays open after locking", reader?.hasPassword == true)
        val d = com.artifex.mupdf.fitz.Document.openDocument(f.absolutePath)
        try {
            assertTrue("the file now asks for its password", d.needsPassword())
            assertTrue(d.authenticatePassword(pw))
            assertEquals(6, d.countPages())
        } finally {
            d.destroy()
        }
    }

    private fun save(b: android.graphics.Bitmap, name: String) {
        val dir = File(ctx.filesDir, "shots").apply { mkdirs() }
        File(dir, name).outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
