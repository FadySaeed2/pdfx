package com.fadysaeed.pdfx.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fadysaeed.pdfx.data.Doc
import com.fadysaeed.pdfx.data.TagCount
import com.fadysaeed.pdfx.data.TextHit
import com.fadysaeed.pdfx.ui.components.AmberButton
import com.fadysaeed.pdfx.ui.components.Chip
import com.fadysaeed.pdfx.ui.components.Corner
import com.fadysaeed.pdfx.ui.components.GhostButton
import com.fadysaeed.pdfx.ui.components.Glyph
import com.fadysaeed.pdfx.ui.components.GlyphIcon
import com.fadysaeed.pdfx.ui.components.Hairline
import com.fadysaeed.pdfx.ui.components.IconButton
import com.fadysaeed.pdfx.ui.components.tagColor
import com.fadysaeed.pdfx.ui.theme.Ink
import com.fadysaeed.pdfx.ui.theme.Type
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class LibraryActions(
    val open: (Doc, Int?) -> Unit = { _, _ -> },
    val more: (Doc) -> Unit = {},
    val section: (Section) -> Unit = {},
    val folder: (String?) -> Unit = {},
    val tag: (Long) -> Unit = {},
    val tagMore: (TagCount) -> Unit = {},
    val back: () -> Unit = {},
    val query: (String) -> Unit = {},
    val sort: () -> Unit = {},
    val pick: () -> Unit = {},
    val scan: () -> Unit = {},
    val grantAccess: () -> Unit = {},
    val emptyTrash: () -> Unit = {},
    val newTag: () -> Unit = {},
    val undo: () -> Unit = {},
    val dismissNotice: () -> Unit = {},
    val duplicates: () -> Unit = {},
    val shelf: (Shelf) -> Unit = {},
    val topics: () -> Unit = {},
    val tidy: () -> Unit = {},
    /** Picks a file to act on with others (or unpicks it). */
    val pickDoc: (Doc) -> Unit = {},
    val pickAll: () -> Unit = {},
    /** Starts picking files: each tap picks one until Cancel. */
    val select: () -> Unit = {},
    val clearPicks: () -> Unit = {},
    /** What to do with the picked files: "move", "copy", "tag", "share", "trash", "more". */
    val many: (String) -> Unit = {},
    val folderMore: (FolderRow) -> Unit = {},
    /** A new folder inside the given one (null: inside Documents). */
    val newFolder: (String?) -> Unit = {},
    /** Opens a folder from the path shown above it. */
    val crumb: (String) -> Unit = {},
    /** A quick look at a file's first pages (a long press). */
    val peek: (Doc) -> Unit = {},
    /** Files as a grid of first pages, or as a list. */
    val grid: (Boolean) -> Unit = {},
    /** Narrows the search results. */
    val filter: (SearchFilter) -> Unit = {},
    /** Highlights: only the user's own, or everyone's. */
    val marksMine: (Boolean) -> Unit = {},
    /** Asks for the user's names on marks. */
    val names: () -> Unit = {},
    /** Shows the tags PDFx put on new files by itself. */
    val autoTags: () -> Unit = {},
)

@Composable
fun LibraryContent(
    ui: LibraryUi,
    actions: LibraryActions,
    thumb: @Composable (Doc) -> ImageBitmap? = { null },
    notice: Notice? = null,
    now: Long = System.currentTimeMillis(),
    label: (String?) -> String = { it ?: "From other apps" },
    /** Puts the cursor in the search box (the Search shortcut on the app's icon). */
    focusSearch: Boolean = false,
    onSearchFocused: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(Ink.Black).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PDFx", style = Type.brand)
            Spacer(Modifier.weight(1f))
            Text("ON THIS PHONE ONLY", style = Type.eyebrow)
        }
        val focus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(focusSearch) { if (focusSearch) { runCatching { focus.requestFocus() }; onSearchFocused() } }
        SearchField(ui.query, actions.query, Modifier.padding(horizontal = 20.dp).focusRequester(focus))
        Spacer(Modifier.height(4.dp))

        Box(Modifier.weight(1f)) {
            val results = ui.results
            if (results != null) {
                SearchResults(ui.query, ui.searchNote, ui.facets, results, actions, thumb, now, label)
            } else {
                Browse(ui, actions, thumb, now, label)
            }
            if (notice != null) {
                NoticeBar(notice, actions, Modifier.align(Alignment.BottomCenter))
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(Ink.Black)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            if (ui.selecting) PickBar(ui.picked.size, ui.items.size, actions)
            else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AmberButton("Scan", actions.scan, Modifier.weight(1f))
                GhostButton("Open or convert", actions.pick, Modifier.weight(1f), minHeight = 52.dp)
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val style = Type.body.copy(fontSize = 16.sp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = style.copy(color = Ink.Bone),
        cursorBrush = SolidColor(Ink.Amber),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "Search" },
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .clip(Corner)
                    .background(Ink.Field)
                    .border(1.dp, Ink.Edge, Corner)
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.SEARCH, Ink.Ash, size = 20.dp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text("Search names and the words inside", style = style.copy(color = Ink.Ash), maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty()) IconButton(Glyph.CLOSE, "Clear search", { onChange("") }, color = Ink.Dust, size = 44.dp)
            }
        },
    )
}

@Composable
private fun Browse(ui: LibraryUi, actions: LibraryActions, thumb: @Composable (Doc) -> ImageBitmap?, now: Long, label: (String?) -> String) {
    val p = ui.place
    val nested = p.inFolder || p.tagId != null || p.shelf != null || p.section == Section.TRASH
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "head") {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (nested) {
                        IconButton(Glyph.BACK, "Back", actions.back, size = 40.dp)
                        Spacer(Modifier.width(4.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (p.inFolder) "FOLDER" else if (p.tagId != null) "TAG" else if (p.shelf != null) "SHELF" else "LIBRARY", style = Type.eyebrow)
                        Spacer(Modifier.height(4.dp))
                        Text(ui.title, style = Type.display, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(6.dp))
                SubLine(ui, actions)
            }
        }
        item(key = "chips") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Folders and the trash live inside All files: a view of it, and a row at its end.
                LIBRARY_CHIPS.forEach { s ->
                    Chip(s.label, { actions.section(s) }, selected = p.section == s || (s == Section.ALL && p.section in setOf(Section.FOLDERS, Section.TRASH)))
                }
            }
        }
        if (p.section in setOf(Section.ALL, Section.FOLDERS) && !p.inFolder) {
            item(key = "all-view") {
                Row(
                    Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Show", style = Type.note)
                    Spacer(Modifier.width(4.dp))
                    Chip("Every file", { actions.section(Section.ALL) }, selected = p.section == Section.ALL)
                    Chip("By folder", { actions.section(Section.FOLDERS) }, selected = p.section == Section.FOLDERS)
                }
            }
        }
        if (!ui.hasAccess && p.section in setOf(Section.ALL, Section.FOLDERS, Section.RECENT)) {
            item(key = "access") { AccessBanner(actions.grantAccess) }
        }
        if (ui.hasAccess && ui.unindexed > 0 && p.section != Section.TRASH) {
            item(key = "index") {
                Row(Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(Ink.Teal))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Reading ${ui.unindexed} ${if (ui.unindexed == 1) "file" else "files"} for search and their real titles.",
                        style = Type.small,
                    )
                }
            }
        }
        if (ui.tidy.total > 0 && !nested && p.section in setOf(Section.RECENT, Section.ALL, Section.SHELVES)) {
            item(key = "tidy") {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = actions.tidy).padding(horizontal = 20.dp).padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).background(Ink.Amber))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Tidy up: ${ui.tidy.total} ${if (ui.tidy.total == 1) "thing" else "things"} to look at" + (if (ui.tidy.duplicates > 0) ", copies among them" else "") + ".",
                        style = Type.small, modifier = Modifier.weight(1f),
                    )
                    Text("Review", style = Type.bodyStrong.copy(color = Ink.Amber))
                }
            }
        }
        if (ui.autoTagged.isNotEmpty() && !nested && p.section in setOf(Section.RECENT, Section.ALL, Section.TAGS)) {
            item(key = "auto-tags") {
                val files = ui.autoTagged.map { it.doc.id }.distinct().size
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = actions.autoTags).padding(horizontal = 20.dp).padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).background(Ink.Teal))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Tagged for you: " + (if (files == 1) "1 new file" else "$files new files") + " took tags from files like them.",
                        style = Type.small, modifier = Modifier.weight(1f),
                    )
                    Text("Look", style = Type.bodyStrong.copy(color = Ink.Amber))
                }
            }
        }
        item(key = "rule") { Hairline(Modifier.padding(horizontal = 20.dp), color = Ink.Rule) }

        when {
            p.section == Section.FOLDERS && !p.inFolder -> {
                if (ui.folders.isEmpty()) item { EmptyNote("No folders with PDFs yet.") }
                items(ui.folders, key = { "f:" + (it.path ?: "-") }) { f -> FolderLine(f, { actions.folder(f.path) }, if (f.path != null) ({ actions.folderMore(f) }) else null) }
                item(key = "new-folder") {
                    Text("New folder", style = Type.bodyStrong.copy(color = Ink.Amber), modifier = Modifier.clickable { actions.newFolder(null) }.padding(horizontal = 20.dp, vertical = 16.dp))
                }
                trashRow(ui, actions)
            }
            p.section == Section.FOLDERS && p.inFolder -> {
                if (ui.crumbs.size > 1) item(key = "crumbs") {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ui.crumbs.forEachIndexed { i, (name, path) ->
                            if (i > 0) Text("  ›  ", style = Type.small)
                            val last = i == ui.crumbs.lastIndex
                            Text(name, style = if (last) Type.small.copy(color = Ink.Bone) else Type.small.copy(color = Ink.Amber), modifier = if (last) Modifier else Modifier.clickable { actions.crumb(path) })
                        }
                    }
                }
                items(ui.folders, key = { "f:" + (it.path ?: "-") }) { f -> FolderLine(f, { actions.folder(f.path) }, { actions.folderMore(f) }) }
                if (ui.loaded && ui.items.isEmpty() && ui.folders.isEmpty()) item { EmptyNote(emptyText(p)) }
                docs(ui, actions, thumb, now, label, showFolder = false)
                if (p.folder != null) item(key = "new-folder") {
                    Text("New folder here", style = Type.bodyStrong.copy(color = Ink.Amber), modifier = Modifier.clickable { actions.newFolder(p.folder) }.padding(horizontal = 20.dp, vertical = 16.dp))
                }
            }
            p.section == Section.SHELVES && p.shelf == null -> {
                items(ui.shelves, key = { "s:" + it.shelf.name }) { s -> ShelfLine(s) { actions.shelf(s.shelf) } }
                item(key = "shelves-note") {
                    Text(
                        "PDFx puts each file on a shelf by what it finds in it: a DOI or an abstract makes a paper, a contents list a book, and so on. Now and then it gets one wrong; nothing is moved or renamed because of it.",
                        style = Type.small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            }
            p.section == Section.TAGS && p.tagId == null -> {
                item(key = "topics") {
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = actions.topics).padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlyphIcon(Glyph.TAG, Ink.Amber, size = 20.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Suggested tags", style = Type.bodyStrong.copy(color = Ink.Amber))
                            Text("Topics that run through your files, to tag them all at once", style = Type.small)
                        }
                    }
                    Hairline(Modifier.padding(start = 56.dp))
                }
                if (ui.tags.isEmpty()) item { EmptyNote("No tags yet. Add one from any file's menu, or make one here.") }
                items(ui.tags, key = { "t:${it.id}" }) { t -> TagLine(t, { actions.tag(t.id) }, { actions.tagMore(t) }) }
                item { Box(Modifier.padding(20.dp)) { GhostButton("New tag", actions.newTag) } }
            }
            p.section == Section.HIGHLIGHTS -> {
                item(key = "marks-whose") {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Chip("Mine", { actions.marksMine(true) }, selected = ui.marksMine)
                            Chip("Everyone's", { actions.marksMine(false) }, selected = !ui.marksMine)
                        }
                        Spacer(Modifier.height(8.dp))
                        val who = ui.myNames.firstOrNull()
                        Text(
                            (if (ui.marksMine) "Marks made in PDFx, marks signed " + (if (ui.myNames.isEmpty()) "with your name" else ui.myNames.joinToString(" or ") { "“$it”" }) + ", and marks no one signed." +
                                (if (ui.othersMarks > 0) " ${ui.othersMarks} from others are left out." else "")
                            else "Every highlight and note in your files, whoever made them.") +
                                " New marks carry " + (who?.let { "your name, “$it”." } ?: "“PDFx”: add your name so other apps show it."),
                            style = Type.small,
                        )
                        Text(if (who == null) "Add your name" else "Change your names", style = Type.bodyStrong.copy(color = Ink.Amber, fontSize = 14.sp), modifier = Modifier.clickable(onClick = actions.names).padding(vertical = 8.dp))
                    }
                    Hairline(Modifier.padding(horizontal = 20.dp), color = Ink.Rule)
                }
                if (ui.marks.isEmpty()) item { EmptyNote(if (ui.marksMine && ui.othersMarks > 0) "None of your own yet. The ${ui.othersMarks} marks in your files came from others: Everyone's shows them." else emptyText(p)) }
                for (g in ui.marks) {
                    item(key = "mh:${g.doc.id}") {
                        Row(Modifier.fillMaxWidth().clickable { actions.open(g.doc, null) }.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(LibraryModel.titleOf(g.doc), style = Type.bodyStrong, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (g.marks.size == 1) "1 mark" else "${g.marks.size} marks", style = Type.small)
                            }
                        }
                    }
                    // Two marks can be alike in every way (a note copied, a highlight made twice): their place in the list tells them apart.
                    itemsIndexed(g.marks, key = { i, _ -> "m:${g.doc.id}:$i" }) { _, m -> MarkLine(m) { actions.open(g.doc, m.page) } }
                    item(key = "mr:${g.doc.id}") { Hairline(Modifier.padding(start = 20.dp, top = 8.dp), color = Ink.Rule) }
                }
            }
            else -> {
                if (ui.loaded && ui.items.isEmpty()) item { EmptyNote(emptyText(p)) }
                docs(ui, actions, thumb, now, label, showFolder = p.section != Section.FOLDERS)
                if (p.section == Section.ALL) {
                    trashRow(ui, actions)
                    upkeepLine(ui)
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

/** The sections across the top; Folders and Trash are reached from All files. */
private val LIBRARY_CHIPS = listOf(Section.RECENT, Section.SHELVES, Section.ALL, Section.STARRED, Section.HIGHLIGHTS, Section.TAGS)

/** What the nightly upkeep did last, at the very end of All files. */
private fun androidx.compose.foundation.lazy.LazyListScope.upkeepLine(ui: LibraryUi) {
    val line = ui.upkeep ?: return
    if (ui.selecting) return
    item(key = "upkeep") {
        Text(line, style = Type.small.copy(color = Ink.Ash), modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
    }
}

/** The trash, as a row at the end of All files. */
private fun androidx.compose.foundation.lazy.LazyListScope.trashRow(ui: LibraryUi, actions: LibraryActions) {
    if (ui.trashCount == 0 || ui.selecting) return
    item(key = "trash-row") {
        Column {
            Hairline(Modifier.padding(horizontal = 20.dp), color = Ink.Rule)
            Row(
                Modifier.fillMaxWidth().clickable { actions.section(Section.TRASH) }.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.TRASH, Ink.Dust, size = 20.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Trash", style = Type.bodyStrong)
                    Text((if (ui.trashCount == 1) "1 file" else "${ui.trashCount} files") + ", kept for 30 days", style = Type.small)
                }
                Text("›", style = Type.bodyStrong.copy(color = Ink.Dust))
            }
        }
    }
}

@Composable
private fun SubLine(ui: LibraryUi, actions: LibraryActions) {
    val p = ui.place
    val listing = !(p.section == Section.FOLDERS && !p.inFolder) && !(p.section == Section.TAGS && p.tagId == null) && !(p.section == Section.SHELVES && p.shelf == null)
    Row(verticalAlignment = Alignment.CenterVertically) {
        val count = when {
            p.section == Section.FOLDERS && !p.inFolder -> ui.folders.size.let { if (it == 1) "1 folder" else "$it folders" }
            p.section == Section.TAGS && p.tagId == null -> ui.tags.size.let { if (it == 1) "1 tag" else "$it tags" }
            p.section == Section.SHELVES && p.shelf == null -> "Your files by kind, and what you are reading"
            p.section == Section.HIGHLIGHTS -> ui.marks.sumOf { it.marks.size }.let { n -> if (n == 1) "1 highlight or note" else "$n highlights and notes" } + ui.marks.size.let { if (it == 0) "" else if (it == 1) " in 1 file" else " in $it files" }
            else -> ui.items.size.let { if (it == 1) "1 document" else "$it documents" }
        }
        Text(count, style = Type.note)
        if (p.section == Section.TRASH) {
            Text("  ·  kept for 30 days", style = Type.note)
            Spacer(Modifier.weight(1f))
            if (ui.items.isNotEmpty()) Text("Empty trash", style = Type.bodyStrong.copy(color = Ink.Amber, fontSize = 14.sp), modifier = Modifier.clickable(onClick = actions.emptyTrash).padding(vertical = 8.dp))
        } else if (listing && ui.items.size > 1 && p.section != Section.HIGHLIGHTS) {
            Spacer(Modifier.weight(1f))
            Row(Modifier.clickable(onClick = actions.sort).padding(vertical = 8.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.SORT, Ink.Dust, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(ui.sort.by.label + if (ui.sort.descending) "  ↓" else "  ↑", style = Type.note.copy(color = Ink.Sand))
            }
            if (!ui.selecting) IconButton(Glyph.SELECT_MANY, "Select: pick several files to move, tag or share", actions.select, color = Ink.Dust, size = 40.dp)
            IconButton(if (ui.grid) Glyph.LIST else Glyph.GRID, if (ui.grid) "Show as a list" else "Show as a grid", { actions.grid(!ui.grid) }, color = Ink.Dust, size = 40.dp)
        }
    }
}

@Composable
private fun AccessBanner(onGrant: () -> Unit) {
    Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(3.dp).heightIn(min = 92.dp).background(Ink.Amber))
        Spacer(Modifier.width(12.dp))
        Column {
            Text("See every PDF on this phone", style = Type.bodyStrong)
            Spacer(Modifier.height(2.dp))
            Text("Android asks once for access to all files. PDFx only looks for PDFs and never sends anything anywhere.", style = Type.note)
            Spacer(Modifier.height(10.dp))
            GhostButton("Allow access", onGrant, minHeight = 42.dp)
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(text, style = Type.note, modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp))
}

private fun emptyText(p: Place) = when (p.section) {
    Section.RECENT -> "Files you open appear here."
    Section.ALL -> "No PDFs found on the phone yet."
    Section.STARRED -> "Star a file from its menu to keep it here."
    Section.TRASH -> "The trash is empty."
    Section.FOLDERS -> "This folder has no PDFs."
    Section.TAGS -> "Nothing has this tag yet."
    Section.SHELVES -> "Nothing on this shelf yet."
    Section.HIGHLIGHTS -> "Highlights and notes from every file come together here, once PDFx has read them. Mark a passage in any file and it shows up."
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DocLine(
    item: Item,
    now: Long,
    image: ImageBitmap?,
    showFolder: Boolean,
    label: (String?) -> String,
    onOpen: () -> Unit,
    onMore: () -> Unit,
    /** Picks the file to act on with others. */
    onPick: (() -> Unit)? = null,
    picking: Boolean = false,
    picked: Boolean = false,
    /** A long press: a quick look at its first pages. */
    onPeek: (() -> Unit)? = null,
) {
    val d = item.doc
    Column(Modifier.fillMaxWidth().background(if (picked) Ink.Field else Ink.Black).combinedClickable(onClick = onOpen, onLongClick = onPeek ?: onPick ?: onMore)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Thumb(d, image)
                if (picking) Box(
                    Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp)
                        .background(if (picked) Ink.Amber else Ink.Black.copy(alpha = 0.7f), RoundedCornerShape(50))
                        .border(1.5.dp, if (picked) Ink.Amber else Ink.Bone, RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) { if (picked) GlyphIcon(Glyph.CHECK, Ink.Black, size = 14.dp) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(LibraryModel.titleOf(d), style = Type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(meta(d, now, showFolder, label), style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val progress = LibraryModel.progressOf(d)
                if (d.lastPage > 0 && progress > 0.01f && !d.inTrash) {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth(0.6f).height(2.dp).background(Ink.Rule)) {
                        Box(Modifier.fillMaxWidth(progress.coerceAtMost(1f)).height(2.dp).background(if (d.reading == 2 || progress > 0.95f) Ink.Teal else Ink.Amber))
                    }
                }
                if (item.tags.isNotEmpty()) {
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        item.tags.take(3).forEach { t ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).background(tagColor(t.color)))
                                Spacer(Modifier.width(5.dp))
                                Text(t.name, style = Type.small.copy(color = Ink.Sand), maxLines = 1)
                            }
                        }
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                if (d.starred) GlyphIcon(Glyph.STAR_FILLED, Ink.Amber, size = 16.dp)
                if (d.lastPage > 0 && d.pages > 0 && !d.inTrash) Text("p. ${d.lastPage + 1}", style = Type.figure, modifier = Modifier.padding(top = 4.dp))
            }
            IconButton(Glyph.MORE, "More for ${LibraryModel.display(d)}", onMore, color = Ink.Dust, size = 44.dp)
        }
        Hairline(Modifier.padding(start = 78.dp))
    }
}

@Composable
private fun Thumb(d: Doc, image: ImageBitmap?) {
    Box(
        Modifier.width(44.dp).height(58.dp).background(if (image != null) Ink.Paper else Ink.Field).border(1.dp, Ink.Rule),
        contentAlignment = Alignment.Center,
    ) {
        when {
            image != null -> Image(image, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
            d.locked -> GlyphIcon(Glyph.LOCK, Ink.Ash, size = 18.dp)
            else -> GlyphIcon(Glyph.PAGE, Ink.Slate, size = 20.dp)
        }
    }
}

@Composable
private fun ShelfLine(s: ShelfRow, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(if (s.shelf.ordinal < 3) Glyph.BOOKMARK else Glyph.LIST, if (s.shelf.ordinal < 3) Ink.Amber else Ink.Dust, size = 22.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(s.shelf.label, style = Type.body, maxLines = 1)
                if (s.shelf.note.isNotEmpty()) Text(s.shelf.note, style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${s.count}", style = Type.figure)
        }
        Hairline(Modifier.padding(start = 58.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderLine(f: FolderRow, onClick: () -> Unit, onMore: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onMore)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = if (onMore != null) 4.dp else 20.dp, top = 15.dp, bottom = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.FOLDER, if (f.pinned) Ink.Amber else Ink.Dust, size = 24.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(f.label.substringAfterLast('/'), style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (f.label.contains('/')) Text(f.label.substringBeforeLast('/'), style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${f.count}", style = Type.figure)
            if (onMore != null) IconButton(Glyph.MORE, "More for ${f.label}", onMore, color = Ink.Dust, size = 44.dp)
        }
        Hairline(Modifier.padding(start = 60.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagLine(t: TagCount, onClick: () -> Unit, onMore: () -> Unit) {
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onMore)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(tagColor(t.color)))
            Spacer(Modifier.width(18.dp))
            Text(t.name, style = Type.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${t.count}", style = Type.figure)
            IconButton(Glyph.MORE, "More for tag ${t.name}", onMore, color = Ink.Dust, size = 44.dp)
        }
        Hairline(Modifier.padding(start = 50.dp))
    }
}

/** The files of the open place, as lines or as a grid of first pages, three a row. */
private fun androidx.compose.foundation.lazy.LazyListScope.docs(ui: LibraryUi, actions: LibraryActions, thumb: @Composable (Doc) -> ImageBitmap?, now: Long, label: (String?) -> String, showFolder: Boolean) {
    val picking = ui.selecting
    if (ui.grid) {
        items(ui.items.chunked(3), key = { row -> "g:" + row.first().doc.id }) { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { item ->
                    GridCell(
                        item, thumb(item.doc), Modifier.weight(1f),
                        onOpen = { if (picking) actions.pickDoc(item.doc) else actions.open(item.doc, null) },
                        onPeek = { actions.peek(item.doc) },
                        picking = picking, picked = item.doc.id in ui.picked,
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    } else {
        items(ui.items, key = { "d:${it.doc.id}" }) { item ->
            DocLine(
                item, now, thumb(item.doc), showFolder = showFolder, label = label,
                onOpen = { if (picking) actions.pickDoc(item.doc) else actions.open(item.doc, null) },
                onMore = { actions.more(item.doc) }, onPick = { actions.pickDoc(item.doc) },
                picking = picking, picked = item.doc.id in ui.picked, onPeek = { actions.peek(item.doc) },
            )
        }
    }
}

/** A file in the grid: its first page, how far it is read, its title. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridCell(item: Item, image: ImageBitmap?, modifier: Modifier, onOpen: () -> Unit, onPeek: () -> Unit, picking: Boolean = false, picked: Boolean = false) {
    val d = item.doc
    Column(modifier.combinedClickable(onClick = onOpen, onLongClick = onPeek).semantics { contentDescription = LibraryModel.titleOf(d) }) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f).background(if (image != null) Ink.Paper else Ink.Field).border(if (picked) 2.dp else 1.dp, if (picked) Ink.Amber else Ink.Rule)) {
            when {
                image != null -> Image(image, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
                d.locked -> GlyphIcon(Glyph.LOCK, Ink.Ash, size = 24.dp)
                else -> Box(Modifier.align(Alignment.Center)) { GlyphIcon(Glyph.PAGE, Ink.Slate, size = 28.dp) }
            }
            if (d.starred) Box(Modifier.align(Alignment.TopStart).padding(4.dp).background(Ink.Black.copy(alpha = 0.7f), RoundedCornerShape(50)).padding(3.dp)) { GlyphIcon(Glyph.STAR_FILLED, Ink.Amber, size = 14.dp) }
            if (picking) Box(
                Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp)
                    .background(if (picked) Ink.Amber else Ink.Black.copy(alpha = 0.7f), RoundedCornerShape(50))
                    .border(1.5.dp, if (picked) Ink.Amber else Ink.Bone, RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) { if (picked) GlyphIcon(Glyph.CHECK, Ink.Black, size = 14.dp) }
            val progress = LibraryModel.progressOf(d)
            if (d.lastPage > 0 && progress > 0.01f && !d.inTrash) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Ink.Black.copy(alpha = 0.5f))) {
                Box(Modifier.fillMaxWidth(progress.coerceAtMost(1f)).height(3.dp).background(if (d.reading == 2 || progress > 0.95f) Ink.Teal else Ink.Amber))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(LibraryModel.titleOf(d), style = Type.small.copy(color = Ink.Bone), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
    }
}

/** A highlight or note: its colour, the words it marks and the note on it, and its page. */
@Composable
fun MarkLine(m: com.fadysaeed.pdfx.data.MarkRow, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp)) {
        Box(Modifier.width(3.dp).heightIn(min = 22.dp).background(androidx.compose.ui.graphics.Color(m.color or (0xFF shl 24))))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (m.quote.isNotBlank()) Text("“" + m.quote + "”", style = Type.body.copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic), maxLines = 6, overflow = TextOverflow.Ellipsis)
            if (m.note.isNotBlank()) {
                if (m.quote.isNotBlank()) Spacer(Modifier.height(4.dp))
                Text(m.note, style = Type.body.copy(color = Ink.Sand), maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text("p. ${m.page + 1}", style = Type.figure)
    }
}

@Composable
private fun SearchResults(query: String, note: String?, facets: List<Facet>, results: List<DocResult>, actions: LibraryActions, thumb: @Composable (Doc) -> ImageBitmap?, now: Long, label: (String?) -> String) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text("SEARCH", style = Type.eyebrow)
                Spacer(Modifier.height(4.dp))
                val files = results.size
                val pages = results.sumOf { it.totalHits }
                val shown = note ?: query
                Text(
                    when {
                        files == 0 -> "Nothing matches “$shown”."
                        pages == 0 -> "$files ${if (files == 1) "file" else "files"} for “$shown”, best first"
                        else -> "$files ${if (files == 1) "file" else "files"}, $pages ${if (pages == 1) "page" else "pages"} with “$shown”, best first"
                    },
                    style = Type.note,
                )
                if (note != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("Nothing was spelt “$query”, so this is “$note”.", style = Type.small.copy(color = Ink.Sand))
                }
            }
            if (facets.isNotEmpty()) Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                facets.forEach { f -> Chip("${f.label}  ${f.count}", { actions.filter(f.filter) }, selected = f.on) }
            }
            Hairline(Modifier.padding(horizontal = 20.dp), color = Ink.Rule)
        }
        items(results, key = { "r:${it.doc.id}" }) { r ->
            DocLine(Item(r.doc, emptyList()), now, thumb(r.doc), showFolder = true, label = label, onOpen = { actions.open(r.doc, r.hits.firstOrNull()?.page ?: r.marks.firstOrNull()?.page) }, onMore = { actions.more(r.doc) }, onPeek = { actions.peek(r.doc) })
            r.marks.forEach { m -> Box(Modifier.padding(start = 58.dp)) { MarkLine(m) { actions.open(r.doc, m.page) } } }
            r.hits.forEach { h -> HitLine(h) { actions.open(r.doc, h.page) } }
            if (r.totalHits > r.hits.size) {
                Text("${r.totalHits - r.hits.size} more ${if (r.totalHits - r.hits.size == 1) "page" else "pages"} in this file", style = Type.small, modifier = Modifier.padding(start = 78.dp, top = 2.dp, bottom = 12.dp))
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun HitLine(h: TextHit, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 78.dp, end = 20.dp, top = 6.dp, bottom = 10.dp)) {
        Text("p. ${h.page + 1}", style = Type.figure.copy(color = Ink.Amber), modifier = Modifier.width(52.dp))
        Text(highlight(h.snippet), style = Type.note.copy(color = Ink.Sand), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

/** Turns the index's match markers into amber, semibold words. */
fun highlight(snippet: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < snippet.length) {
        val start = snippet.indexOf('\u0002', i)
        if (start < 0) { append(snippet.substring(i)); break }
        append(snippet.substring(i, start))
        val end = snippet.indexOf('\u0003', start + 1).let { if (it < 0) snippet.length else it }
        withStyle(SpanStyle(color = Ink.Amber, fontWeight = FontWeight.SemiBold)) { append(snippet.substring(start + 1, end)) }
        i = end + 1
    }
}

@Composable
private fun NoticeBar(n: Notice, actions: LibraryActions, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(Ink.Field, Corner)
            .border(1.dp, Ink.Edge, Corner)
            .clickable(onClick = actions.dismissNotice)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(n.text, style = Type.note.copy(color = Ink.Bone), modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        if (n.undo != null) {
            Text("Undo", style = Type.bodyStrong.copy(color = Ink.Amber, fontSize = 14.sp), modifier = Modifier.clickable(onClick = actions.undo).padding(horizontal = 12.dp, vertical = 10.dp))
        }
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
private val yearFmt = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)

fun whenText(millis: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val t = Instant.ofEpochMilli(millis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val d = t.toLocalDate()
    return when {
        d == today -> "Today ${t.format(timeFmt)}"
        d == today.minusDays(1) -> "Yesterday ${t.format(timeFmt)}"
        d.year == today.year -> t.format(dayFmt)
        else -> t.format(yearFmt)
    }
}

fun humanSize(bytes: Long): String = when {
    bytes < 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes / 1024.0).let { if (it < 10) String.format(Locale.US, "%.1f", it) else it.toInt().toString() }} KB"
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    else -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
}

private fun meta(d: Doc, now: Long, showFolder: Boolean, label: (String?) -> String): String {
    val parts = mutableListOf<String>()
    LibraryModel.byline(d).takeIf { it.isNotEmpty() }?.let { parts += it }
    if (d.pages > 0) parts += if (d.pages == 1) "1 page" else "${d.pages} pages"
    if (d.size >= 0) parts += humanSize(d.size)
    when {
        d.inTrash -> parts += "deleted " + whenText(d.trashedAt, now).replaceFirstChar { it.lowercase() }
        d.lastOpened > 0 -> parts += whenText(d.lastOpened, now)
        d.modified > 0 -> parts += whenText(d.modified, now)
    }
    if (showFolder && !d.inTrash) parts += label(d.folder).substringAfterLast('/')
    return parts.joinToString("  ·  ")
}


/** What can be done with the picked files, in place of Scan and Open while files are picked. */
@Composable
private fun PickBar(count: Int, total: Int, actions: LibraryActions) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (count == 0) "Tap files to pick them" else if (count == 1) "1 file picked" else "$count files picked", style = Type.bodyStrong, modifier = Modifier.weight(1f))
            if (count < total) Text("Pick all", style = Type.bodyStrong.copy(color = Ink.Amber), modifier = Modifier.clickable(onClick = actions.pickAll).padding(8.dp))
            Text("Cancel", style = Type.bodyStrong.copy(color = Ink.Dust), modifier = Modifier.clickable(onClick = actions.clearPicks).padding(8.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, key) in listOf("Move" to "move", "Tag" to "tag", "Trash" to "trash", "More" to "more")) {
                GhostButton(label, { if (count > 0) actions.many(key) }, Modifier.weight(1f), color = if (count > 0) Ink.Bone else Ink.Ash, minHeight = 46.dp)
            }
        }
    }
}
