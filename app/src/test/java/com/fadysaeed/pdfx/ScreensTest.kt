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
import com.fadysaeed.pdfx.ui.viewer.Reader
import com.fadysaeed.pdfx.ui.viewer.PaperMode
import com.fadysaeed.pdfx.ui.viewer.ContentsSheet
import com.fadysaeed.pdfx.ui.viewer.DisplaySheet
import com.fadysaeed.pdfx.engine.Box
import com.fadysaeed.pdfx.engine.OutlineEntry
import com.fadysaeed.pdfx.engine.Selection
import com.fadysaeed.pdfx.engine.Mark
import com.fadysaeed.pdfx.engine.MarkKind
import com.fadysaeed.pdfx.ui.viewer.Draft
import com.fadysaeed.pdfx.ui.viewer.MarkColors
import com.fadysaeed.pdfx.ui.viewer.PenColors
import com.fadysaeed.pdfx.ui.viewer.Tool
import com.fadysaeed.pdfx.ui.viewer.ContentsSheetOnTab
import com.fadysaeed.pdfx.ui.viewer.ReflowFont
import com.fadysaeed.pdfx.ui.viewer.TextEdit
import com.fadysaeed.pdfx.ui.viewer.movedBox
import com.fadysaeed.pdfx.ui.viewer.resizedBox
import com.fadysaeed.pdfx.ui.viewer.isArabic
import com.fadysaeed.pdfx.ui.viewer.highlighted
import com.fadysaeed.pdfx.engine.Para
import com.fadysaeed.pdfx.ui.components.Hairline
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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

    private fun viewerShot(file: String, setup: (Reader) -> Unit) {
        val pages = (0 until 3).map { fakePage(it, 1152) }
        compose.setContent {
            PdfxTheme {
                val scope = rememberCoroutineScope()
                val reader = remember { Reader(null, 112, scope).also(setup) }
                ViewerContent(
                    name = "Clinical practice guideline - mood disorders",
                    reader = reader,
                    aspects = List(112) { 595f / 842f },
                    widths = List(112) { 595f },
                    image = { i -> pages.getOrNull(i) },
                    tile = { null },
                    listState = rememberLazyListState(),
                    onWant = {},
                    onTiles = {},
                    onBack = {},
                )
            }
        }
        compose.onRoot().captureRoboImage("screenshots/$file")
    }

    @Test fun viewer() = viewerShot("09_viewer.png") {}

    @Test fun viewerSearch() = viewerShot("11_viewer_search.png") { r ->
        r.showHits(
            "lithium",
            mapOf(
                0 to listOf(listOf(Box(57f, 201f, 121f, 211f)), listOf(Box(150f, 299f, 214f, 309f))),
                1 to listOf(listOf(Box(57f, 240f, 121f, 250f))),
            ),
            at = 1,
        )
    }

    @Test fun viewerNight() = viewerShot("12_viewer_night.png") { r -> r.changeMode(PaperMode.NIGHT) }

    @Test fun viewerSelection() = viewerShot("13_viewer_selection.png") { r ->
        r.chrome = false
        r.showSelection(Selection(0, 57f, 225f, 260f, 245f, listOf(Box(57f, 220f, 295f, 230f), Box(57f, 240f, 260f, 250f)), "monitor lithium levels twelve hours after the last dose"))
    }

    @Test fun viewerReflow() = viewerShot("14_viewer_reflow.png") { r ->
        r.changeReflowFont(ReflowFont.SERIF)
        r.showHits("lithium", emptyMap(), -1)
        r.showReflowForTest(true, mapOf(
            0 to listOf(
                Para("Maintenance treatment", 1),
                Para("Lithium remains the first-line long-term treatment for bipolar disorder, with the strongest evidence for preventing relapse and suicide."),
                Para("Monitoring", 2),
                Para("Check levels 12 hours after the last dose, weekly after each change until stable, then every three months for the first year."),
                Para("يُفضَّل قياس مستوى الليثيوم بعد ١٢ ساعة من آخر جرعة، ومتابعة وظائف الكلى والغدة الدرقية كل ستة أشهر."),
            ),
            1 to listOf(Para("Combining lithium with an ACE inhibitor, an ARB or a thiazide raises levels; check within a week of starting any of them.")),
        ))
    }

    @Test fun textBoxSelected() = viewerShot("19_text_box_moving.png") { r ->
        r.chrome = false
        val m = Mark(0, 2, MarkKind.TEXT, Box(70f, 300f, 300f, 340f), PenColors[2], "Recheck levels after the dose change", "", 15f)
        r.showSelectedForTest(m, movingTo = Box(110f, 380f, 340f, 420f))
    }

    @Test
    fun textEdit() {
        compose.setContent {
            PdfxTheme {
                TextEdit(Mark(0, 2, MarkKind.TEXT, Box(70f, 300f, 300f, 340f), PenColors[2], "Recheck levels after the dose change", "", 15f), {}) { _, _, _ -> }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/20_text_edit.png")
    }

    @Test
    fun reflowSettings() {
        compose.setContent {
            PdfxTheme {
                val scope = rememberCoroutineScope()
                val reader = remember { Reader(null, 112, scope).also { it.changeReflow(true) } }
                Column(Modifier.fillMaxSize().background(Ink.Field)) { DisplaySheet(reader) }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/21_reflow_settings.png")
    }

    @Test
    fun boxesAndText() {
        assertEquals(Box(20f, 30f, 120f, 80f), movedBox(Box(10f, 10f, 110f, 60f), 10f, 20f, 595f, 842f))
        // Kept on the page.
        assertEquals(Box(495f, 0f, 595f, 50f), movedBox(Box(10f, 10f, 110f, 60f), 900f, -40f, 595f, 842f))
        // Bottom-right corner grows the box; top-left can't pass the opposite corner.
        assertEquals(Box(10f, 10f, 140f, 90f), resizedBox(Box(10f, 10f, 110f, 60f), 3, 30f, 30f))
        assertEquals(Box(102f, 52f, 110f, 60f), resizedBox(Box(10f, 10f, 110f, 60f), 0, 500f, 500f))
        assertEquals(true, isArabic("«مرحبا» hello"))
        assertEquals(false, isArabic("12. Lithium مرحبا"))
        assertEquals(2, highlighted("Lithium and lithium", "LITHIUM", androidx.compose.ui.graphics.Color.Yellow).spanStyles.size)
    }

    @Test fun markup() = viewerShot("16_markup.png") { r ->
        r.showMarking(
            Tool.PEN,
            Draft.Ink(0, (0..60).map { t -> (70f + t * 6f) to (330f + 18f * kotlin.math.sin(t / 6f)) }, PenColors[1], 2f, 1f),
        )
        r.showSelectionlessDraftForTest()
    }

    @Test fun markSelected() = viewerShot("17_mark_selected.png") { r ->
        r.chrome = false
        r.showMarking(
            Tool.HIGHLIGHT, Draft.Words(0, listOf(Box(57f, 201f, 290f, 211f)), MarkColors[0]),
            selected = Mark(0, 0, MarkKind.HIGHLIGHT, Box(57f, 201f, 290f, 211f), MarkColors[0], "Check this against the 2026 update", "monitor lithium levels twelve hours after the last dose"),
        )
        r.stopMarkingKeepSelectionForTest()
    }

    @Test
    fun marksList() {
        compose.setContent {
            PdfxTheme {
                val scope = rememberCoroutineScope()
                val reader = remember {
                    Reader(null, 112, scope).also {
                        it.setAllMarksForTest(listOf(
                            Mark(18, 0, MarkKind.HIGHLIGHT, Box(57f, 201f, 290f, 211f), MarkColors[0], "", "Monitor lithium levels twelve hours after the last dose"),
                            Mark(18, 1, MarkKind.NOTE, Box(300f, 190f, 320f, 210f), MarkColors[3], "Ask about the new renal guidance", ""),
                            Mark(41, 0, MarkKind.UNDERLINE, Box(57f, 401f, 290f, 411f), MarkColors[2], "", "risk of toxicity rises with ACE inhibitors"),
                            Mark(44, 0, MarkKind.INK, Box(60f, 300f, 400f, 360f), PenColors[1], "", ""),
                        ))
                    }
                }
                Column(Modifier.fillMaxSize().background(Ink.Field)) { ContentsSheetOnTab(reader, 2) }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/18_marks_list.png")
    }

    @Test
    fun viewerSheets() {
        compose.setContent {
            PdfxTheme {
                val scope = rememberCoroutineScope()
                val reader = remember {
                    Reader(null, 112, scope).also {
                        it.setOutlineForTest(listOf(
                            OutlineEntry("Summary of recommendations", 2, 0),
                            OutlineEntry("Bipolar disorder", 10, 0),
                            OutlineEntry("Acute mania", 12, 1),
                            OutlineEntry("Maintenance treatment", 18, 1),
                            OutlineEntry("Lithium", 19, 2),
                            OutlineEntry("Depression", 44, 0),
                        ))
                        it.bookmarks.addAll(listOf(18, 47))
                    }
                }
                Column(Modifier.fillMaxSize().background(Ink.Field)) {
                    ContentsSheet(reader, 20) { _, _ -> }
                    Hairline(color = Ink.Edge)
                    DisplaySheet(reader)
                }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/15_viewer_sheets.png")
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
