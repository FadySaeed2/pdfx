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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.fadysaeed.pdfx.data.Doc
import com.fadysaeed.pdfx.data.Tag
import com.fadysaeed.pdfx.data.TagCount
import com.fadysaeed.pdfx.data.TextHit
import com.fadysaeed.pdfx.ui.library.ActionsSheet
import com.fadysaeed.pdfx.ui.library.DocResult
import com.fadysaeed.pdfx.ui.library.Facet
import com.fadysaeed.pdfx.ui.library.MarkGroup
import com.fadysaeed.pdfx.ui.library.SearchFilter
import com.fadysaeed.pdfx.ui.library.FolderRow
import com.fadysaeed.pdfx.ui.library.Shelf
import com.fadysaeed.pdfx.ui.library.ShelfRow
import com.fadysaeed.pdfx.ui.library.TidyCounts
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
import com.fadysaeed.pdfx.ui.viewer.BoxStyleFields
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.draw.clip
import com.fadysaeed.pdfx.ui.viewer.movedBox
import com.fadysaeed.pdfx.ui.viewer.resizedBox
import com.fadysaeed.pdfx.ui.viewer.isArabic
import com.fadysaeed.pdfx.ui.viewer.highlighted
import com.fadysaeed.pdfx.engine.Para
import com.fadysaeed.pdfx.engine.Span
import com.fadysaeed.pdfx.engine.Hit
import com.fadysaeed.pdfx.engine.findAll
import com.fadysaeed.pdfx.engine.snippet
import com.fadysaeed.pdfx.ui.viewer.SelectionFloat
import com.fadysaeed.pdfx.ui.viewer.ColorPickerBody
import org.junit.Assert.assertTrue
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

    /** Every icon in the app on one sheet, to see them side by side. */
    @Test fun glyphs() {
        compose.setContent {
            PdfxTheme {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.background(com.fadysaeed.pdfx.ui.theme.Ink.Black).padding(12.dp)) {
                    com.fadysaeed.pdfx.ui.components.Glyph.entries.chunked(6).forEach { row ->
                        androidx.compose.foundation.layout.Row {
                            row.forEach { g ->
                                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.width(66.dp).padding(vertical = 8.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                    com.fadysaeed.pdfx.ui.components.GlyphIcon(g, com.fadysaeed.pdfx.ui.theme.Ink.Bone)
                                    androidx.compose.material3.Text(g.name.lowercase(), style = com.fadysaeed.pdfx.ui.theme.Type.small.copy(fontSize = 8.sp), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/59_glyphs.png")
    }

    @Test fun picking() = shot(base.copy(picked = setOf(docs[0].doc.id, docs[2].doc.id), selecting = true, tidy = TidyCounts(duplicates = 2, codeNames = 14, loose = 31)), "48_picking.png")

    @Test fun folderInside() = shot(
        base.copy(
            place = Place(Section.FOLDERS, folder = "$root/Documents/Library", inFolder = true), title = "Library",
            crumbs = listOf("Documents" to "$root/Documents", "Library" to "$root/Documents/Library"),
            folders = listOf(FolderRow("$root/Documents/Library/Papers", "Papers", 318, pinned = true), FolderRow("$root/Documents/Library/Guidelines", "Guidelines", 44)),
        ),
        "49_folder_inside.png",
    )

    @Test fun tidy() {
        compose.setContent {
            PdfxTheme { com.fadysaeed.pdfx.ui.library.TidyScreen(TidyCounts(duplicates = 6, codeNames = 142, loose = 231, broken = 3, bareScans = 0), reading = 40, onStep = {}, onClose = {}) }
        }
        compose.onRoot().captureRoboImage("screenshots/50_tidy.png")
    }

    @Test fun shelves() = shot(
        base.copy(
            place = Place(Section.SHELVES), title = "Shelves", items = emptyList(),
            shelves = listOf(
                ShelfRow(Shelf.READING, 3), ShelfRow(Shelf.TO_READ, 12), ShelfRow(Shelf.DONE, 41), ShelfRow(Shelf.PAPER, 318),
                ShelfRow(Shelf.BOOK, 27), ShelfRow(Shelf.GUIDELINE, 44), ShelfRow(Shelf.LETTER, 63), ShelfRow(Shelf.SCAN, 9), ShelfRow(Shelf.OTHER, 85),
            ),
        ),
        "46_shelves.png",
    )

    @Test fun realTitles() = shot(
        base.copy(
            title = "Recent",
            duplicates = listOf(listOf(doc(20, "a.pdf", "$root/Download", 1, 1, 0), doc(21, "b.pdf", "$root/Download", 1, 1, 0))),
            items = listOf(
                Item(doc(11, "s41380-023-02145-x.pdf", "$root/Download", 2_400_000, 12, ago(0), page = 7).copy(title = "Lithium in the treatment of bipolar disorder: a reappraisal of the evidence", author = "Malhi et al.", year = 2023), listOf(reading)),
                Item(doc(12, "bjp.2024.172.pdf", "$root/Download", 1_100_000, 12, ago(2), page = 11).copy(title = "Pharmacological interventions for co-occurring psychopathology in borderline personality disorder", author = "Ribeiro et al.", year = 2024, reading = 2), emptyList()),
                Item(doc(13, "document(3).pdf", "$root/WhatsApp Documents", 90_000, 2, ago(5)), emptyList()),
            ),
        ),
        "47_real_titles.png",
    )

    @Test fun allNoAccess() = shot(
        base.copy(place = Place(Section.ALL), title = "All files", hasAccess = false, items = docs.sortedBy { it.doc.name }, sort = Sort(SortBy.NAME, false)),
        "02_all_no_access.png",
    )

    @Test fun folders() = shot(base.copy(place = Place(Section.FOLDERS), title = "All files", items = emptyList(), trashCount = 1), "03_folders.png")

    @Test fun allFiles() = shot(
        base.copy(
            place = Place(Section.ALL), title = "All files", items = docs.sortedBy { it.doc.name }.take(2), sort = Sort(SortBy.NAME, false), trashCount = 3,
            autoTagged = listOf(com.fadysaeed.pdfx.ui.library.AutoTagged(docs[0].doc, com.fadysaeed.pdfx.data.Tag(7, "Bipolar", 2))),
            upkeep = "Looked after today at 3:12 am: 4 new files found, 4 files read for search, 2 scans read for search, 1 file tagged.",
        ),
        "57_all_files.png",
    )

    @Test fun selecting() = shot(base.copy(place = Place(Section.ALL), title = "All files", items = docs, selecting = true), "58_selecting.png")

    @Test fun inFolder() = shot(
        base.copy(place = Place(Section.FOLDERS, "$root/Download", inFolder = true), title = "Download", items = docs.filter { it.doc.folder == "$root/Download" }, sort = Sort(SortBy.MODIFIED, true)),
        "04_in_folder.png",
    )

    @Test fun tags() = shot(base.copy(place = Place(Section.TAGS), title = "Tags", items = emptyList()), "05_tags.png")

    @Test fun grid() = shot(base.copy(place = Place(Section.ALL), title = "All files", grid = true, items = docs + docs.map { Item(it.doc.copy(id = it.doc.id + 20), it.tags) }), "51_grid.png")

    @Test fun highlights() = shot(
        base.copy(
            place = Place(Section.HIGHLIGHTS), title = "Highlights", items = emptyList(),
            marks = listOf(
                MarkGroup(docs[2].doc, listOf(
                    com.fadysaeed.pdfx.data.MarkRow(3, 17, "HIGHLIGHT", 0xF2C14E, "Check lithium levels 12 hours after the last dose, weekly until stable.", ""),
                    com.fadysaeed.pdfx.data.MarkRow(3, 40, "HIGHLIGHT", 0x7FD1B9, "ACE inhibitors raise lithium levels.", "Ask the GP about the ramipril."),
                    com.fadysaeed.pdfx.data.MarkRow(3, 63, "NOTE", 0xF2C14E, "", "Use this table in the teaching session."),
                )),
                MarkGroup(docs[0].doc, listOf(com.fadysaeed.pdfx.data.MarkRow(1, 2, "UNDERLINE", 0xE26D5C, "Budget closes on 30 November.", ""))),
            ),
        ),
        "52_highlights.png",
    )

    @Test fun searchRanked() = shot(
        base.copy(
            query = "lithuim",
            searchNote = "lithium",
            facets = listOf(
                Facet("Guidelines", 1, SearchFilter(kind = "GUIDELINE"), on = false), Facet("Papers", 1, SearchFilter(kind = "PAPER"), on = false),
                Facet("Last 2 years", 1, SearchFilter(years = 2), on = false), Facet("Clinic", 2, SearchFilter(tagId = 1), on = false),
            ),
            results = listOf(
                DocResult(docs[2].doc, true, listOf(
                    TextHit(3, 18, "…monitor \u0002lithium\u0003 levels 12 hours after the last dose, weekly until stable…"),
                ), 9, 20.0, listOf(com.fadysaeed.pdfx.data.MarkRow(3, 40, "HIGHLIGHT", 0x7FD1B9, "ACE inhibitors raise lithium levels.", "Ask the GP about the ramipril."))),
                DocResult(docs[0].doc, false, listOf(TextHit(1, 3, "…the \u0002lithium\u0003 battery supplier contract renewed in Q3…")), 1, 3.0),
            ),
        ),
        "53_search_ranked.png",
    )

    @Test fun peek() {
        compose.setContent {
            PdfxTheme {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(com.fadysaeed.pdfx.ui.theme.Ink.Field)) {
                    com.fadysaeed.pdfx.ui.library.PeekBody(docs[2].doc.copy(title = "Clinical practice guideline for mood disorders", author = "Malhi et al.", year = 2021, locked = true), "Documents/Guidelines", {}, {}, {}, {})
                }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/54_peek.png")
    }

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

    /** The phone on its side: two pages side by side. */
    @Test @Config(qualifiers = "w832dp-h384dp-xxhdpi") fun viewerSpread() = viewerShot("60_viewer_spread.png") {}

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
                Para("", table = com.fadysaeed.pdfx.engine.TableModel(
                    0, "Table 2. Monitoring", "2", Box(56f, 130f, 520f, 226f), listOf("Drug", "Dose", "Check"),
                    listOf(
                        com.fadysaeed.pdfx.engine.TableRow(false, listOf("Lithium", "400 mg", "Level at 12 h^a^")),
                        com.fadysaeed.pdfx.engine.TableRow(false, listOf("Valproate", "500 mg", "Liver tests")),
                        com.fadysaeed.pdfx.engine.TableRow(false, listOf("ليثيوم", "٤٠٠ مجم", "مستوى الدم")),
                    ),
                    listOf("^a^ Twelve hours after the last dose."), com.fadysaeed.pdfx.engine.TableKind.GRID, cols = 3,
                )),
                Para("Check sodium and creatinine before starting.", list = true, marker = "•"),
                Para("Avoid NSAIDs where possible, and warn about dehydration.", list = true, marker = "•"),
                Para("Stopping lithium abruptly raises the risk of relapse; taper over at least four weeks.", spans = listOf(Span(0, 17, bold = true, italic = false), Span(54, 84, bold = false, italic = true))),
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
                Column(Modifier.fillMaxSize().background(Ink.Field).padding(24.dp)) {
                    androidx.compose.material3.Text("Edit text", style = com.fadysaeed.pdfx.ui.theme.Type.title)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
                    androidx.compose.foundation.layout.Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                        BoxStyleFields(
                            "راجع المستوى بعد تغيير الجرعة\nRecheck levels after the dose change",
                            com.fadysaeed.pdfx.engine.BoxStyle(font = com.fadysaeed.pdfx.engine.BoxFont.SERIF, size = 16f, bold = true, italic = true, color = PenColors[2], fill = 0xFFFFF4C2.toInt(), border = true, spacing = 1),
                            listOf(0xFFB39DDB.toInt()), {}, {}, {},
                        )
                    }
                }
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

    @Test fun searchResults() = viewerShot("22_search_results.png") { r ->
        val words = mapOf(
            0 to listOf(
                Hit(listOf(Box(57f, 201f, 121f, 211f)), "", "Lithium", " remains the first-line long-term treatment for bipolar disorder, with the strongest…"),
                Hit(listOf(Box(150f, 299f, 214f, 309f)), "…levels 12 hours after the last dose. Toxicity is more likely when ", "lithium", " is combined with diuretics…"),
            ),
            3 to listOf(Hit(listOf(Box(57f, 240f, 121f, 250f)), "…يُفضَّل قياس مستوى ", "الليثيوم", " بعد ١٢ ساعة من آخر جرعة…")),
            17 to listOf(Hit(listOf(Box(57f, 240f, 121f, 250f)), "Stopping ", "lithium", " abruptly raises the risk of relapse; taper over at least four weeks.")),
        )
        r.showHits("lithium", words.mapValues { (_, v) -> v.map { it.boxes } }, at = 1, words = words, list = true)
    }

    @Test
    fun selectionColours() {
        compose.setContent {
            PdfxTheme {
                val scope = rememberCoroutineScope()
                val reader = remember { Reader(null, 112, scope) }
                Column(Modifier.fillMaxSize().background(Ink.Paper).padding(24.dp)) {
                    SelectionFloat(reader, {}, {}, {}, startOpen = true)
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(16.dp))
                    Column(Modifier.background(Ink.Field).padding(20.dp)) {
                        ColorPickerBody(330f, 0.55f, 0.95f, listOf(0xFFB39DDB.toInt(), 0xFF80CBC4.toInt()), { _, _, _ -> }, {})
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/23_selection_colours.png")
    }

    @Test
    fun folding() {
        assertEquals(1, findAll("علاج الإكتِئاب الحاد", "اكتئاب").size)
        assertEquals(1, findAll("Café au lait", "cafe").size)
        val h = snippet("one two three four five six seven eight nine ten eleven twelve", "seven", 30, emptyList(), reach = 12)
        assertEquals("seven", h.match)
        assertTrue(h.before.startsWith("…"))
        assertTrue(h.after.endsWith("…"))
        val marks = listOf(
            Mark(2, 0, MarkKind.HIGHLIGHT, Box(0f, 0f, 1f, 1f), MarkColors[0], "Check this", "monitor levels"),
            Mark(5, 0, MarkKind.NOTE, Box(0f, 0f, 1f, 1f), MarkColors[3], "Ask the team", ""),
        )
        val text = Reader(null, 10, kotlinx.coroutines.MainScope()).marksText("Guideline", marks)
        assertTrue(text.contains("Page 3") && text.contains("“monitor levels”") && text.contains("Note: Check this") && text.contains("Page 6"))
    }

    /** Handwriting on page 1: "cat" (3 strokes close together), then "sat" further along, then a second line. */
    private val ink = listOf(
        Box(60f, 300f, 72f, 320f), Box(74f, 302f, 86f, 320f), Box(88f, 296f, 98f, 320f),
        Box(118f, 300f, 130f, 320f), Box(132f, 302f, 144f, 320f),
        Box(60f, 350f, 90f, 370f),
    ).mapIndexed { i, b -> Mark(0, i, MarkKind.INK, b, PenColors[2], "", "", width = 2f, path = listOf(listOf(b.x0 to b.y1, (b.x0 + b.x1) / 2 to b.y0, b.x1 to b.y1))) }

    @Test
    fun inkGroups() {
        val r = Reader(null, 3, kotlinx.coroutines.MainScope())
        r.setMarksForTest(0, ink)
        r.pickInkScope(com.fadysaeed.pdfx.ui.viewer.InkScope.WORD)
        r.selectMark(ink[1])
        assertEquals(setOf(0, 1, 2), r.members.map { it.index }.toSet())
        r.pickInkScope(com.fadysaeed.pdfx.ui.viewer.InkScope.LINE)
        assertEquals(setOf(0, 1, 2, 3, 4), r.members.map { it.index }.toSet())
        r.pickInkScope(com.fadysaeed.pdfx.ui.viewer.InkScope.STROKE)
        assertEquals(listOf(1), r.members.map { it.index })
        // Nearest mark to a tap, measured to the stroke.
        assertEquals(3, r.markAt(0, 124f, 310f, 12f)?.index)
        assertEquals(null, r.markAt(0, 300f, 600f, 12f))
        // The lasso picks what is inside the loop.
        r.showMarking(Tool.SELECT, null)
        r.begin(0, 110f, 290f, eraser = false, reach = 12f)
        r.extend(listOf(150f to 290f, 150f to 330f, 110f to 330f, 110f to 291f))
        r.finish()
        assertEquals(setOf(3, 4), r.members.map { it.index }.toSet())
    }

    @Test fun inkWordSelected() = viewerShot("24_ink_word.png") { r ->
        r.chrome = false
        r.setMarksForTest(0, ink)
        r.showSelectedForTest(ink[0], with = ink.subList(1, 3))
    }

    @Test
    fun reflowRules() {
        fun line(t: String, size: Float = 10f, y: Float = 300f, b: Boolean = false, ital: Boolean = false) =
            com.fadysaeed.pdfx.engine.RawLine(t, BooleanArray(t.length) { b }, BooleanArray(t.length) { ital }, size, y, y + size)
        fun block(y0: Float, vararg l: com.fadysaeed.pdfx.engine.RawLine) = com.fadysaeed.pdfx.engine.RawBlock(Box(50f, y0, 500f, y0 + 12f * l.size), l.toList(), false)
        val blocks = listOf(
            block(30f, line("Clinical Guideline 2026", y = 30f)),
            block(100f, line("Maintenance", 16f, 100f)),
            block(140f, line("Lithium remains the first-line treat-", y = 140f), line("ment for bipolar disorder.", y = 152f)),
            block(180f, line("• Check sodium first.", y = 180f), line("• Then creatinine.", y = 192f)),
            com.fadysaeed.pdfx.engine.RawBlock(Box(60f, 220f, 400f, 420f), emptyList(), true),
            block(800f, line("12", y = 800f)),
        )
        val paras = com.fadysaeed.pdfx.engine.buildParas(blocks, 842f, setOf(com.fadysaeed.pdfx.engine.bandKey("Clinical Guideline 2027")))
        val texts = paras.map { it.text }
        assertTrue("running head dropped: $texts", texts.none { it.contains("Clinical Guideline") })
        assertTrue("page number dropped: $texts", texts.none { it == "12" })
        assertEquals(1, paras.first { it.text == "Maintenance" }.level)
        assertTrue(texts.contains("Lithium remains the first-line treatment for bipolar disorder."))
        assertEquals(2, paras.count { it.list })
        assertEquals(1, paras.count { it.figure != null })
        assertTrue(com.fadysaeed.pdfx.engine.runsOn(Para("and the dose was"), Para("halved after a week.")))
        assertTrue(!com.fadysaeed.pdfx.engine.runsOn(Para("The dose was halved."), Para("after a week.")))
        assertEquals("and the dose was halved.", com.fadysaeed.pdfx.engine.joinParas(Para("and the dose was"), Para("halved.")).text)
        // Tables: the table takes the place of the lines it covers; the text around it stays.
        val model = com.fadysaeed.pdfx.engine.TableModel(0, "", "", Box(50f, 500f, 500f, 600f), listOf("A", "B"), listOf(com.fadysaeed.pdfx.engine.TableRow(false, listOf("1", "2"))), emptyList(), com.fadysaeed.pdfx.engine.TableKind.GRID, cols = 2)
        fun wide(t: String, y: Float) = com.fadysaeed.pdfx.engine.RawLine(t, BooleanArray(t.length), BooleanArray(t.length), 10f, y, y + 10f, 50f, 480f)
        val around = listOf(
            com.fadysaeed.pdfx.engine.RawBlock(Box(50f, 450f, 500f, 530f), listOf(wide("Before", 450f), wide("A B", 505f), wide("1 2", 520f)), false),
            com.fadysaeed.pdfx.engine.RawBlock(Box(50f, 620f, 500f, 632f), listOf(wide("After", 620f)), false),
        )
        val placed = com.fadysaeed.pdfx.engine.placeTables(around, listOf(model))
        assertEquals(3, placed.size)
        assertEquals("Before", placed[0].lines.single().text)
        assertTrue(placed[1].table === model)
        assertEquals("After", placed[2].lines.single().text)
        val tp = com.fadysaeed.pdfx.engine.buildParas(placed, 842f, emptySet())
        assertTrue(tp.any { it.table === model } && tp.none { it.text.contains("A B") })
        // A table read only as a picture shows as one, in its place.
        val pic = com.fadysaeed.pdfx.engine.TableModel(0, "", "", Box(50f, 500f, 500f, 600f), emptyList(), emptyList(), emptyList(), com.fadysaeed.pdfx.engine.TableKind.IMAGE)
        assertTrue(com.fadysaeed.pdfx.engine.placeTables(around, listOf(pic))[1].image)
        assertEquals("1²", com.fadysaeed.pdfx.ui.viewer.tablePlain("1^²^"))
        // Text box style survives being written and read back.
        val st = com.fadysaeed.pdfx.engine.BoxStyle(font = com.fadysaeed.pdfx.engine.BoxFont.SERIF, size = 18f, bold = true, underline = true, align = com.fadysaeed.pdfx.engine.BoxAlign.CENTER, color = 0xFF2F6FED.toInt(), spacing = 2)
        val back = com.fadysaeed.pdfx.engine.BoxStyle.parse(st.css("hello"), "hello", 12f, 0, null, false, false)
        assertEquals(st, back)
        val ar = com.fadysaeed.pdfx.engine.BoxStyle()
        assertTrue(ar.rich("مرحبا\nhello").contains("<p dir=\"rtl\">مرحبا</p><p dir=\"ltr\">hello</p>"))
        assertEquals(com.fadysaeed.pdfx.engine.BoxAlign.RIGHT, ar.alignFor("مرحبا"))
    }

    private val formFields = listOf(
        com.fadysaeed.pdfx.engine.Field(0, 0, com.fadysaeed.pdfx.engine.FieldKind.TEXT, "full_name", "Fady Saeed", Box(170f, 100f, 520f, 120f)),
        com.fadysaeed.pdfx.engine.Field(0, 1, com.fadysaeed.pdfx.engine.FieldKind.TEXT, "date_of_birth", "", Box(170f, 130f, 320f, 150f), isDate = true, datePattern = "dd/MM/yyyy"),
        com.fadysaeed.pdfx.engine.Field(0, 2, com.fadysaeed.pdfx.engine.FieldKind.CHECKBOX, "consent", "Yes", Box(170f, 270f, 186f, 286f), on = true),
        com.fadysaeed.pdfx.engine.Field(0, 3, com.fadysaeed.pdfx.engine.FieldKind.CHOICE, "clinic", "Armadale", Box(170f, 330f, 360f, 350f), options = listOf("Armadale", "Fremantle", "Joondalup")),
    )

    @Test fun formFilling() = viewerShot("25_form.png") { r ->
        r.chrome = false
        r.showFieldsForTest(0, formFields, editing = formFields[1])
    }

    private val drawnSign = com.fadysaeed.pdfx.data.SavedSign(
        "a", false,
        listOf(listOf(0f to 0.25f, 0.1f to 0.05f, 0.18f to 0.3f, 0.3f to 0.1f, 0.45f to 0.28f, 0.62f to 0.12f, 0.8f to 0.26f, 1f to 0.15f), listOf(0.2f to 0.33f, 0.9f to 0.3f)),
        3f, 0xFF1F3F9F.toInt(),
    )

    @Test fun signTools() = viewerShot("26_sign_tools.png") { r ->
        r.setSignsForTest(listOf(drawnSign, drawnSign.copy(id = "b", initials = true, aspect = 1.6f, color = 0xFF1A1A1A.toInt())))
        r.pickSignWithDate(true)
        r.showMarking(Tool.SIGN, null)
    }

    private val myCert = com.fadysaeed.pdfx.data.SavedCert("c1", "Dr Fady Saeed", "fady@example.org", "PDFx Clinic", "Dr Fady Saeed", 1_900_000_000_000L, selfSigned = true, remembered = true)
    private val hospitalCert = com.fadysaeed.pdfx.data.SavedCert("c2", "Dr Fady Saeed", "", "East Metro Health", "Health Sector CA", 1_850_000_000_000L, selfSigned = false, remembered = false)
    private fun sig(name: String, intact: Boolean, trusted: Boolean, self: Boolean, after: Boolean = false) = com.fadysaeed.pdfx.engine.DocSignature(
        page = 0, box = com.fadysaeed.pdfx.engine.Box(60f, 380f, 280f, 450f), field = "doctor_signature", reason = "Referral approved", location = "Perth",
        check = com.fadysaeed.pdfx.engine.SignatureCheck(
            signer = com.fadysaeed.pdfx.engine.Person(name, "", "Elsewhere Clinic"), issuer = com.fadysaeed.pdfx.engine.Person(if (self) name else "Health Sector CA"),
            time = 1_793_000_000_000L, intact = intact, trusted = trusted, selfSigned = self, changedAfter = after,
            validFrom = 1_760_000_000_000L, validTo = 1_850_000_000_000L,
        ),
    )

    @Test fun signDigitalTools() = viewerShot("29_sign_digital.png") { r ->
        r.certs.addAll(listOf(myCert, hospitalCert))
        r.pickDigital(true)
        r.showMarking(Tool.SIGN, null)
    }

    @Test fun signaturesBanner() = viewerShot("30_signatures_banner.png") { r ->
        r.docSignatures.addAll(listOf(sig("Dr Elsewhere", intact = true, trusted = false, self = false)))
    }

    @Test
    fun certSheet() {
        compose.setContent {
            PdfxTheme {
                androidx.compose.foundation.layout.Box(Modifier.background(Ink.Field).padding(24.dp)) {
                    com.fadysaeed.pdfx.ui.viewer.CertSheetBody(
                        making = true, onMaking = {}, picked = false, error = null, onChooseFile = {},
                        password = "secret", onPassword = {}, again = "secre", onAgain = {}, remember = true, onRemember = {},
                        name = "Dr Fady Saeed", onName = {}, email = "fady@example.org", onEmail = {}, org = "", onOrg = {},
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/31_cert_sheet.png")
    }

    @Test
    fun digitalSignDialog() {
        compose.setContent {
            PdfxTheme {
                androidx.compose.foundation.layout.Box(Modifier.background(Ink.Field).padding(24.dp)) {
                    com.fadysaeed.pdfx.ui.viewer.DigitalSignBody(
                        cert = hospitalCert, hasPicture = true, error = "That password doesn't open Dr Fady Saeed's certificate.",
                        password = "abc", onPassword = {}, remember = false, onRemember = {}, picture = true, onPicture = {},
                        reason = "I approve this referral", onReason = {}, location = "", onLocation = {},
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/32_digital_sign.png")
    }

    @Test
    fun shrinkResult() {
        val ui = com.fadysaeed.pdfx.ui.tools.ShrinkUi().apply {
            before = 12_400_000L; after = 3_100_000L; made = java.io.File("made.pdf")
        }
        compose.setContent {
            PdfxTheme {
                androidx.compose.foundation.layout.Box(Modifier.background(Ink.Field).padding(24.dp)) {
                    com.fadysaeed.pdfx.ui.tools.ShrinkBody(ui, signed = false)
                }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/43_shrink.png")
    }

    @Test
    fun watermarkDialog() {
        compose.setContent {
            PdfxTheme {
                androidx.compose.foundation.layout.Box(Modifier.background(Ink.Field).padding(24.dp)) {
                    com.fadysaeed.pdfx.ui.tools.WatermarkBody(com.fadysaeed.pdfx.ui.tools.WatermarkUi(), busy = false, note = null)
                }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/55_watermark.png")
    }

    @Test
    fun pageNumbersDialog() {
        compose.setContent {
            PdfxTheme {
                androidx.compose.foundation.layout.Box(Modifier.background(Ink.Field).padding(24.dp)) {
                    com.fadysaeed.pdfx.ui.tools.PageNumbersBody(com.fadysaeed.pdfx.ui.tools.NumbersUi().apply { style = com.fadysaeed.pdfx.engine.Stamp.Style.OF; place = com.fadysaeed.pdfx.engine.Stamp.Place.BOTTOM_RIGHT }, 12, busy = false, note = null)
                }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/56_page_numbers.png")
    }

    @Test
    fun redactDialog() {
        compose.setContent {
            PdfxTheme {
                androidx.compose.foundation.layout.Box(Modifier.background(Ink.Field).padding(24.dp)) {
                    com.fadysaeed.pdfx.ui.tools.RedactBody(
                        "Ann Example", {}, marked = 14, busy = false,
                        note = "Marked 14 places on 6 pages. They show as red boxes: look them over, then black them out.",
                        signed = false, scrub = true, onScrub = {}, onFind = {}, onByHand = {},
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/44_redact.png")
    }

    @Test
    fun signaturesList() {
        compose.setContent {
            PdfxTheme {
                androidx.compose.foundation.layout.Column(Modifier.background(Ink.Field).padding(24.dp)) {
                    com.fadysaeed.pdfx.ui.viewer.SignaturesList(listOf(
                        sig("Dr Hospital", intact = true, trusted = true, self = false, after = true),
                        sig("Fady Test", intact = true, trusted = false, self = true),
                        sig("Dr Altered", intact = false, trusted = false, self = false),
                    ))
                }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/33_signatures_list.png")
    }

    @Test
    fun signingRules() {
        val t = com.fadysaeed.pdfx.engine.pdfDate("D:20261102103000+02'00'")
        assertEquals(java.time.Instant.parse("2026-11-02T08:30:00Z").toEpochMilli(), t)
        assertEquals(java.time.Instant.parse("2026-11-02T00:00:00Z").toEpochMilli(), com.fadysaeed.pdfx.engine.pdfDate("D:20261102"))
        assertEquals(null, com.fadysaeed.pdfx.engine.pdfDate("rubbish"))
        assertEquals(com.fadysaeed.pdfx.engine.SignState.VALID, sig("a", true, true, false).check.state)
        assertEquals(com.fadysaeed.pdfx.engine.SignState.UNVERIFIED, sig("a", true, false, true).check.state)
        assertEquals(com.fadysaeed.pdfx.engine.SignState.BROKEN, sig("a", false, true, false).check.state)
    }

    @Test fun backToPage() = viewerShot("34_back_to_page.png") { r -> r.backTo = 36 to 120 }

    /** A real table, found the way the app finds it, reflowed: heading text then the table. */
    private fun tableShot(file: String, source: String, page: Int, mode: PaperMode = PaperMode.PAPER) = viewerShot(file) { r ->
        r.changeReflowFont(ReflowFont.SERIF)
        if (mode != PaperMode.PAPER) r.changeMode(mode)
        val t = TableFixtures.tables(source, page).first()
        r.chrome = false
        r.showReflowForTest(true, mapOf(0 to listOf(Para("The table as it reads on a phone.", 2), Para("", table = t))))
    }

    @Test fun tableCards() = tableShot("35_table_cards.png", "gpm", 41)

    @Test fun tableGrid() = tableShot("36_table_grid.png", "coch", 6)

    @Test fun tableList() = tableShot("37_table_list.png", "gpm", 50)

    @Test fun tableWideGrid() = tableShot("38_table_wide_grid.png", "gpm", 65, PaperMode.NIGHT)

    @Test fun tableRuledGrid() = tableShot("39_table_ruled_grid.png", "grid", 1)

    /** The app's icon as launchers cut it: a circle and a rounded square, and the one-colour version. */
    @Test
    fun appIcon() {
        compose.setContent {
            androidx.compose.foundation.layout.Row(Modifier.background(androidx.compose.ui.graphics.Color(0xFF141414)).padding(24.dp)) {
                for (shape in listOf(androidx.compose.foundation.shape.CircleShape, androidx.compose.foundation.shape.RoundedCornerShape(40.dp))) {
                    androidx.compose.foundation.layout.Box(Modifier.padding(12.dp).size(160.dp).clip(shape), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_background), null, Modifier.requiredSize(240.dp))
                        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground), null, Modifier.requiredSize(240.dp))
                    }
                }
                androidx.compose.foundation.layout.Box(Modifier.padding(12.dp).size(160.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(0xFF3B3226)), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_monochrome), null, Modifier.requiredSize(240.dp),
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.ui.graphics.Color(0xFFF2D9A6)),
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("screenshots/00_app_icon.png")
    }

    /** The pages screen with six pages: two picked, one moved, one turned, a blank page added. */
    private fun pagesShot(file: String, setup: (com.fadysaeed.pdfx.ui.viewer.Organizer) -> Unit) {
        compose.setContent {
            PdfxTheme {
                val scope = rememberCoroutineScope()
                val o = remember {
                    com.fadysaeed.pdfx.ui.viewer.Organizer(6, scope, null).also { o ->
                        o.items.forEachIndexed { i, p -> o.thumbs[o.key(p)] = fakePage(i, 300) }
                        setup(o)
                        o.items.forEachIndexed { i, p -> if (o.key(p) !in o.thumbs) o.thumbs[o.key(p)] = fakePage(p.page, 300).let { b -> if (p.turn != 0) rotated(b, p.turn) else b } }
                    }
                }
                com.fadysaeed.pdfx.ui.viewer.PagesScreen(o, "Clinical practice guideline", false, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onRoot().captureRoboImage("screenshots/$file")
    }

    private fun rotated(b: ImageBitmap, deg: Int): ImageBitmap {
        val src = b.asAndroidBitmap()
        val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true).asImageBitmap()
    }

    @Test fun pagesGrid() = pagesShot("41_pages.png") { o -> o.record(); o.move(4, 1) }

    @Test fun pagesPicked() = pagesShot("42_pages_picked.png") { o ->
        o.toggle(o.items[2].id); o.toggle(o.items[3].id)
        o.turn(90)
        o.insert(listOf(com.fadysaeed.pdfx.engine.PageItem(com.fadysaeed.pdfx.engine.PageSource.BLANK)))
        o.toggle(o.items[2].id); o.toggle(o.items[3].id)
    }

    @Test
    fun pagesRules() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val o = com.fadysaeed.pdfx.ui.viewer.Organizer(5, scope, null)
        assertTrue(!o.changed)
        o.record(); o.move(0, 4)
        assertEquals(listOf(1, 2, 3, 4, 0), o.items.map { it.page })
        assertTrue(o.changed)
        o.undo()
        assertEquals(listOf(0, 1, 2, 3, 4), o.items.map { it.page })
        assertTrue(!o.changed)
        o.redo()
        assertEquals(0, o.items.last().page)
        o.undo()
        // Copy page 2: the copy stands right after it, and is picked instead.
        o.toggle(o.items[1].id)
        o.duplicate()
        assertEquals(listOf(0, 1, 1, 2, 3, 4), o.items.map { it.page })
        assertEquals(listOf(o.items[2].id), o.picked.toList())
        assertTrue(o.items[1].id != o.items[2].id)
        // Turn the copy, then take it out.
        o.turn(90)
        assertEquals(90, o.items[2].turn)
        o.turn(-180)
        assertEquals(270, o.items[2].turn)
        assertTrue(o.delete())
        assertEquals(listOf(0, 1, 2, 3, 4), o.items.map { it.page })
        // The last page always stays.
        o.pickAll()
        assertTrue(!o.delete())
        assertEquals(5, o.items.size)
        o.pickNone()
        // New pages go after the last picked one, or at the end.
        o.toggle(o.items[0].id)
        o.insert(listOf(com.fadysaeed.pdfx.engine.PageItem(com.fadysaeed.pdfx.engine.PageSource.BLANK)))
        assertEquals(com.fadysaeed.pdfx.engine.PageSource.BLANK, o.items[1].source)
        o.pickNone()
        o.insert(listOf(com.fadysaeed.pdfx.engine.PageItem(com.fadysaeed.pdfx.engine.PageSource.BLANK)))
        assertEquals(com.fadysaeed.pdfx.engine.PageSource.BLANK, o.items.last().source)
    }

    @Test fun figureWithCaption() = viewerShot("40_figure_caption.png") { r ->
        r.changeReflowFont(ReflowFont.SERIF)
        val f = TableFixtures.figures("gpm", 62).first()
        r.chrome = false
        r.showReflowForTest(true, mapOf(0 to listOf(Para("A drawn figure, its caption and its notes.", 2), Para("", figure = f.box, figureInfo = f))))
    }

    @Test fun stampTools() = viewerShot("27_stamp_tools.png") { r -> r.showMarking(Tool.STAMP, null) }

    @Test
    fun signPad() {
        compose.setContent {
            PdfxTheme {
                com.fadysaeed.pdfx.ui.viewer.SignPadBody(
                    initials = false, busy = false, onDismiss = {}, onSave = { _, _ -> }, onChoose = {}, onShoot = {},
                    startStrokes = listOf(listOf(60f to 220f, 120f to 120f, 170f to 240f, 240f to 140f, 330f to 230f, 430f to 150f, 560f to 210f, 700f to 170f), listOf(150f to 260f, 640f to 250f)),
                )
            }
        }
        compose.onRoot().captureRoboImage("screenshots/28_sign_pad.png")
    }

    @Test
    fun formRules() {
        assertEquals("dd/MM/yyyy", com.fadysaeed.pdfx.engine.acrobatToJavaDate("dd/mm/yyyy"))
        assertEquals("d-MMM-yy", com.fadysaeed.pdfx.engine.acrobatToJavaDate("d-mmm-yy"))
        assertEquals("dd/MM/yyyy", com.fadysaeed.pdfx.engine.dateFormatFromScript("AFDate_FormatEx(\"dd/mm/yyyy\");"))
        val day = java.time.LocalDate.of(2026, 11, 1)
        assertEquals("01/11/2026", com.fadysaeed.pdfx.engine.formatToday("dd/MM/yyyy", day))
        assertEquals("1 November 2026", com.fadysaeed.pdfx.engine.formatToday("d MMMM yyyy", day))
        assertEquals("Date of birth", formFields[1].label)
        // A photographed signature: the paper goes, the ink stays, trimmed close.
        val bmp = android.graphics.Bitmap.createBitmap(400, 200, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.rgb(235, 232, 225))
        c.drawRect(100f, 80f, 300f, 110f, android.graphics.Paint().apply { color = android.graphics.Color.rgb(25, 25, 40) })
        val clean = com.fadysaeed.pdfx.data.Signatures.clearBackground(bmp)!!
        assertTrue("trimmed: ${clean.width}x${clean.height}", clean.width in 200..220 && clean.height in 30..50)
        assertEquals(0, android.graphics.Color.alpha(clean.getPixel(0, 0)))
        assertTrue(android.graphics.Color.alpha(clean.getPixel(clean.width / 2, clean.height / 2)) > 200)
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

    /** Drawing under way: the bar folded to one thin row, the page with the room. */
    @Test fun markupSlim() = viewerShot("16b_markup_slim.png") { r ->
        r.showMarking(
            Tool.PEN,
            Draft.Ink(0, (0..60).map { t -> (70f + t * 6f) to (330f + 18f * kotlin.math.sin(t / 6f)) }, PenColors[1], 2f, 1f),
        )
        r.showSelectionlessDraftForTest()
        r.slimBar(true)
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
