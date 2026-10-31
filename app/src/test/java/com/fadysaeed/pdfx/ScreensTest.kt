package com.fadysaeed.pdfx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.fadysaeed.pdfx.data.RecentFile
import com.fadysaeed.pdfx.ui.home.HomeContent
import com.fadysaeed.pdfx.ui.home.humanSize
import com.fadysaeed.pdfx.ui.theme.PdfxTheme
import com.fadysaeed.pdfx.ui.viewer.LockedPanel
import com.fadysaeed.pdfx.ui.viewer.ViewerContent
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime
import java.time.ZoneId

/** Screenshots of every screen, published by CI so the design can be checked before a build ships. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w384dp-h832dp-xxhdpi")
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private val now = LocalDateTime.of(2026, 11, 1, 16, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun ago(hours: Long) = now - hours * 3_600_000

    private val sample = listOf(
        RecentFile("content://a", "Quarterly report - Q3 2026.pdf", 1_840_000, 14, ago(1), 6),
        RecentFile("content://b", "Lease agreement - signed copy.pdf", 612_000, 38, ago(5), 0),
        RecentFile("content://c", "Clinical practice guideline - mood disorders.pdf", 9_420_000, 112, ago(26), 47),
        RecentFile("content://d", "Electricity bill October.pdf", 214_000, 3, ago(80), 0),
        RecentFile("content://e", "Scanned letter 2026-10-14.pdf", 22_500_000, 9, ago(300), 2),
    )

    @Test
    fun home() {
        compose.setContent { PdfxTheme { HomeContent(sample, null, {}, {}, now) } }
        compose.onRoot().captureRoboImage("screenshots/01_home.png")
    }

    @Test
    fun homeEmpty() {
        compose.setContent { PdfxTheme { HomeContent(emptyList(), null, {}, {}, now) } }
        compose.onRoot().captureRoboImage("screenshots/02_home_empty.png")
    }

    @Test
    fun homeWithProblem() {
        compose.setContent {
            PdfxTheme {
                HomeContent(sample.take(2), "Couldn't open Discharge summary.pdf. The file is not a readable PDF. If it came from another app, open it again from there.", {}, {}, now)
            }
        }
        compose.onRoot().captureRoboImage("screenshots/03_home_problem.png")
    }

    @Test
    fun viewer() {
        val pages = (0 until 3).map { fakePage(it) }
        compose.setContent {
            PdfxTheme {
                ViewerContent(
                    name = "Clinical practice guideline - mood disorders.pdf",
                    pageCount = 112,
                    aspects = List(112) { 595f / 842f },
                    image = { i -> pages.getOrNull(i) },
                    listState = rememberLazyListState(),
                    onWant = {},
                    onBack = {},
                )
            }
        }
        compose.onRoot().captureRoboImage("screenshots/04_viewer.png")
    }

    @Test
    fun locked() {
        compose.setContent { PdfxTheme { LockedPanel("Bank statement October.pdf", wrong = true, onCancel = {}, onUnlock = {}) } }
        compose.onRoot().captureRoboImage("screenshots/05_locked.png")
    }

    @Test
    fun sizes() {
        assertEquals("512 B", humanSize(512))
        assertEquals("1.8 MB", humanSize(1_840_000))
        assertEquals("214 KB", humanSize(219_136))
    }

    /** A stand-in page: the real engine is exercised on a device by the instrumented tests. */
    private fun fakePage(i: Int): ImageBitmap {
        val w = 1152
        val h = (w * 842f / 595f).toInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        val ink = Paint().apply { color = Color.rgb(35, 35, 35); isAntiAlias = true }
        val grey = Paint().apply { color = Color.rgb(150, 150, 150) }
        ink.textSize = 46f
        c.drawText(if (i == 0) "Mood disorders" else "Section ${i + 1}", 110f, 190f, ink)
        var y = 280f
        repeat(36) { r ->
            val len = if (r % 7 == 6) 0.55f else 0.86f
            c.drawRect(110f, y, 110f + (w - 220) * len, y + 12f, grey)
            y += 38f
        }
        return b.asImageBitmap()
    }
}
