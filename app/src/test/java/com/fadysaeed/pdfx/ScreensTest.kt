package com.fadysaeed.pdfx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.fadysaeed.pdfx.data.Doc
import com.fadysaeed.pdfx.data.Tag
import com.fadysaeed.pdfx.data.TagCount
import com.fadysaeed.pdfx.data.TextHit
import com.fadysaeed.pdfx.ui.library.ActionsSheet
import com.fadysaeed.pdfx.ui.library.DocResult
import com.fadysaeed.pdfx.ui.library.FolderRow
import com.fadysaeed.pdfx.ui.library.Item
import com.fadysaeed.pdfx.ui.library.LibraryActions
import com.fadysaeed.pdfx.ui.library.LibraryContent
import com.fadysaeed.pdfx.ui.library.LibraryUi
import com.fadysaeed.pdfx.ui.library.Notice
import com.fadysaeed.pdfx.ui.library.Place
import com.fadysaeed.pdfx.ui.library.Section
import com.fadysaeed.pdfx.ui.library.Sort
import com.fadysaeed.pdfx.ui.library.SortBy
import com.fadysaeed.pdfx.ui.library.humanSize
import com.fadysaeed.pdfx.ui.theme.Ink
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
    private val root = "/storage/emulated/0"

    private fun doc(id: Long, name: String, folder: String?, size: Long, pages: Int, opened: Long, page: Int = 0, starred: Boolean = false, trashed: Long = 0) =
        Doc(
            id = id, key = "$folder/$name", path = folder?.let { "$it/$name" }, uri = "file://$folder/$name", name = name,
            folder = folder, size = size, modified = ago(400), pages = pages, lastOpened = opened, lastPage = page,
            starred = starred, trashedAt = trashed, trashOrigin = if (trashed > 0) "$root/Download/$name" else null,
        )

    private val clinic = Tag(1, "Clinic", 1)
    private val bills = Tag(2, "Bills", 0)
    private val reading = Tag(3, "Reading", 3)

    private val docs = listOf(
        Item(doc(1, "Quarterly report - Q3 2026.pdf", "$root/Documents/Work", 1_840_000, 14, ago(1), 6, starred = true), listOf(clinic)),
        Item(doc(2, "Lease agreement - signed copy.pdf", "$root/Download", 612_000, 38, ago(5)), emptyList()),
        Item(doc(3, "Clinical practice guideline - mood disorders.pdf", "$root/Documents/Guidelines", 9_420_000, 112, ago(26), 47), listOf(clinic, reading)),
        Item(doc(4, "Electricity bill October.pdf", "$root/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents", 214_000, 3, ago(80)), listOf(bills)),
        Item(doc(5, "Scanned letter 2026-10-14.pdf", "$root/Download", 22_500_000, 9, ago(300), 2), emptyList()),
    )

    private fun label(f: String?): String = when {
        f == null -> "From other apps"
        f.endsWith("WhatsApp Documents") -> "WhatsApp Documents"
        else -> f.removePrefix("$root/")
    }

    private val base = LibraryUi(
        title = "Recent", items = docs, loaded = true, hasAccess = true,
        tags = listOf(TagCount(2, "Bills", 0, 1), TagCount(1, "Clinic", 1, 2), TagCount(3, "Reading", 3, 1)),
        allTags = listOf(bills, clinic, reading),
        folders = listOf(
            FolderRow("$root/Documents/Guidelines", "Documents/Guidelines", 31),
            FolderRow("$root/Documents/Work", "Documents/Work", 12),
            FolderRow("$root/Download", "Download", 64),
            FolderRow("$root/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents", "WhatsApp Documents", 118),
            FolderRow(null, "From other apps", 2),
        ),
    )

    private fun shot(ui: LibraryUi, file: String, notice: Notice? = null) {
        val thumbs = mapOf(1L to fakePage(0, 168), 3L to fakePage(1, 168), 5L to fakePage(2, 168))
        compose.setContent {
            PdfxTheme { LibraryContent(ui, LibraryActions(), thumb = { thumbs[it.id] }, notice = notice, now = now, label = ::label) }
        }
        compose.onRoot().captureRoboImage("screenshots/$file")
    }

    @Test fun recent() = shot(base.copy(unindexed = 37), "01_recent.png")

    @Test fun allNoAccess() = shot(
        base.copy(place = Place(Section.ALL), title = "All files", hasAccess = false, items = docs.sortedBy { it.doc.name }, sort = Sort(SortBy.NAME, false)),
        "02_all_no_access.png",
    )

    @Test fun folders() = shot(base.copy(place = Place(Section.FOLDERS), title = "Folders", items = emptyList()), "03_folders.png")

    @Test fun inFolder() = shot(
        base.copy(place = Place(Section.FOLDERS, "$root/Download", inFolder = true), title = "Download", items = docs.filter { it.doc.folder == "$root/Download" }, sort = Sort(SortBy.MODIFIED, true)),
        "04_in_folder.png",
    )

    @Test fun tags() = shot(base.copy(place = Place(Section.TAGS), title = "Tags", items = emptyList()), "05_tags.png")

    @Test fun search() = shot(
        base.copy(
            query = "lithium",
            results = listOf(
                DocResult(docs[2].doc, false, listOf(
                    TextHit(3, 18, "…monitor \u0002lithium\u0003 levels 12 hours after the last dose, weekly until stable…"),
                    TextHit(3, 41, "…when \u0002lithium\u0003 is combined with an ACE inhibitor the risk of toxicity rises…"),
                    TextHit(3, 57, "…\u0002lithium\u0003 remains the first-line long-term option for bipolar…"),
                ), 9),
                DocResult(docs[0].doc, false, listOf(TextHit(1, 3, "…the \u0002lithium\u0003 battery supplier contract renewed in Q3…")), 1),
            ),
        ),
        "06_search.png",
    )

    @Test fun trash() = shot(
        base.copy(place = Place(Section.TRASH), title = "Trash", items = listOf(Item(doc(9, "Old invoice.pdf", "$root/.pdfx-trash", 98_000, 2, 0, trashed = ago(30)), emptyList()))),
        "07_trash.png",
        notice = Notice("Moved Old invoice to the trash.", undo = {}),
    )

    @Test
    fun actions() {
        compose.setContent {
            PdfxTheme { Column(Modifier.fillMaxSize().background(Ink.Field)) { ActionsSheet(docs[0].doc, "Documents/Work") {} } }
        }
        compose.onRoot().captureRoboImage("screenshots/08_actions.png")
    }

    @Test
    fun viewer() {
        val pages = (0 until 3).map { fakePage(it, 1152) }
        compose.setContent {
            PdfxTheme {
                ViewerContent(
                    name = "Clinical practice guideline - mood disorders",
                    pageCount = 112,
                    aspects = List(112) { 595f / 842f },
                    image = { i -> pages.getOrNull(i) },
                    listState = rememberLazyListState(),
                    onWant = {},
                    onBack = {},
                )
            }
        }
        compose.onRoot().captureRoboImage("screenshots/09_viewer.png")
    }

    @Test
    fun locked() {
        compose.setContent { PdfxTheme { LockedPanel("Bank statement October.pdf", wrong = true, onCancel = {}, onUnlock = {}) } }
        compose.onRoot().captureRoboImage("screenshots/10_locked.png")
    }

    @Test
    fun sizes() {
        assertEquals("512 B", humanSize(512))
        assertEquals("1.8 MB", humanSize(1_840_000))
        assertEquals("214 KB", humanSize(219_136))
    }

    /** A stand-in page: the real engine is exercised on a device by the instrumented tests. */
    private fun fakePage(i: Int, w: Int): ImageBitmap {
        val h = (w * 842f / 595f).toInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val k = w / 1152f
        c.drawColor(Color.WHITE)
        val ink = Paint().apply { color = Color.rgb(35, 35, 35); isAntiAlias = true; textSize = 46f * k }
        val grey = Paint().apply { color = Color.rgb(150, 150, 150) }
        c.drawText(if (i == 0) "Mood disorders" else "Section ${i + 1}", 110f * k, 190f * k, ink)
        var y = 280f * k
        repeat(36) { r ->
            val len = if (r % 7 == 6) 0.55f else 0.86f
            c.drawRect(110f * k, y, 110f * k + (w - 220 * k) * len, y + 12f * k, grey)
            y += 38f * k
        }
        return b.asImageBitmap()
    }
}
