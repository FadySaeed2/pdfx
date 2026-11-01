package com.fadysaeed.pdfx.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
    val grantAccess: () -> Unit = {},
    val emptyTrash: () -> Unit = {},
    val newTag: () -> Unit = {},
    val undo: () -> Unit = {},
    val dismissNotice: () -> Unit = {},
)

@Composable
fun LibraryContent(
    ui: LibraryUi,
    actions: LibraryActions,
    thumb: @Composable (Doc) -> ImageBitmap? = { null },
    notice: Notice? = null,
    now: Long = System.currentTimeMillis(),
    label: (String?) -> String = { it ?: "From other apps" },
) {
    Column(Modifier.fillMaxSize().background(Ink.Black).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PDFx", style = Type.brand)
            Spacer(Modifier.weight(1f))
            Text("ON THIS PHONE ONLY", style = Type.eyebrow)
        }
        SearchField(ui.query, actions.query, Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(4.dp))

        Box(Modifier.weight(1f)) {
            val results = ui.results
            if (results != null) {
                SearchResults(ui.query, results, actions, thumb, now, label)
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
            AmberButton("Open a PDF", actions.pick, Modifier.fillMaxWidth())
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
    val nested = p.inFolder || p.tagId != null
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
                        Text(if (p.inFolder) "FOLDER" else if (p.tagId != null) "TAG" else "LIBRARY", style = Type.eyebrow)
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
                Section.entries.forEach { s -> Chip(s.label, { actions.section(s) }, selected = p.section == s) }
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
                        "Reading the words in ${ui.unindexed} ${if (ui.unindexed == 1) "file" else "files"} so search can find them.",
                        style = Type.small,
                    )
                }
            }
        }
        item(key = "rule") { Hairline(Modifier.padding(horizontal = 20.dp), color = Ink.Rule) }

        when {
            p.section == Section.FOLDERS && !p.inFolder -> {
                if (ui.folders.isEmpty()) item { EmptyNote("No folders with PDFs yet.") }
                items(ui.folders, key = { "f:" + (it.path ?: "-") }) { f -> FolderLine(f) { actions.folder(f.path) } }
            }
            p.section == Section.TAGS && p.tagId == null -> {
                if (ui.tags.isEmpty()) item { EmptyNote("No tags yet. Add one from any file's menu, or make one here.") }
                items(ui.tags, key = { "t:${it.id}" }) { t -> TagLine(t, { actions.tag(t.id) }, { actions.tagMore(t) }) }
                item { Box(Modifier.padding(20.dp)) { GhostButton("New tag", actions.newTag) } }
            }
            else -> {
                if (ui.loaded && ui.items.isEmpty()) item { EmptyNote(emptyText(p)) }
                items(ui.items, key = { "d:${it.doc.id}" }) { item ->
                    DocLine(item, now, thumb(item.doc), showFolder = p.section != Section.FOLDERS, label = label, onOpen = { actions.open(item.doc, null) }, onMore = { actions.more(item.doc) })
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun SubLine(ui: LibraryUi, actions: LibraryActions) {
    val p = ui.place
    val listing = !(p.section == Section.FOLDERS && !p.inFolder) && !(p.section == Section.TAGS && p.tagId == null)
    Row(verticalAlignment = Alignment.CenterVertically) {
        val count = when {
            p.section == Section.FOLDERS && !p.inFolder -> ui.folders.size.let { if (it == 1) "1 folder" else "$it folders" }
            p.section == Section.TAGS && p.tagId == null -> ui.tags.size.let { if (it == 1) "1 tag" else "$it tags" }
            else -> ui.items.size.let { if (it == 1) "1 document" else "$it documents" }
        }
        Text(count, style = Type.note)
        if (p.section == Section.TRASH) {
            Text("  ·  kept for 30 days", style = Type.note)
            Spacer(Modifier.weight(1f))
            if (ui.items.isNotEmpty()) Text("Empty trash", style = Type.bodyStrong.copy(color = Ink.Amber, fontSize = 14.sp), modifier = Modifier.clickable(onClick = actions.emptyTrash).padding(vertical = 8.dp))
        } else if (listing && ui.items.size > 1) {
            Spacer(Modifier.weight(1f))
            Row(Modifier.clickable(onClick = actions.sort).padding(vertical = 8.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                GlyphIcon(Glyph.SORT, Ink.Dust, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text(ui.sort.by.label + if (ui.sort.descending) "  ↓" else "  ↑", style = Type.note.copy(color = Ink.Sand))
            }
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
) {
    val d = item.doc
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = onMore)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(d, image)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(LibraryModel.display(d), style = Type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(meta(d, now, showFolder, label), style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
private fun FolderLine(f: FolderRow, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphIcon(Glyph.FOLDER, Ink.Dust, size = 24.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(f.label.substringAfterLast('/'), style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (f.label.contains('/')) Text(f.label.substringBeforeLast('/'), style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("${f.count}", style = Type.figure)
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

@Composable
private fun SearchResults(query: String, results: List<DocResult>, actions: LibraryActions, thumb: @Composable (Doc) -> ImageBitmap?, now: Long, label: (String?) -> String) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text("SEARCH", style = Type.eyebrow)
                Spacer(Modifier.height(4.dp))
                val files = results.size
                val pages = results.sumOf { it.totalHits }
                Text(
                    when {
                        files == 0 -> "Nothing matches “$query”."
                        pages == 0 -> "$files ${if (files == 1) "file" else "files"} named like “$query”"
                        else -> "$files ${if (files == 1) "file" else "files"}, $pages ${if (pages == 1) "page" else "pages"} with “$query”"
                    },
                    style = Type.note,
                )
            }
            Hairline(Modifier.padding(horizontal = 20.dp), color = Ink.Rule)
        }
        items(results, key = { "r:${it.doc.id}" }) { r ->
            DocLine(Item(r.doc, emptyList()), now, thumb(r.doc), showFolder = true, label = label, onOpen = { actions.open(r.doc, r.hits.firstOrNull()?.page) }, onMore = { actions.more(r.doc) })
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
