package com.fadysaeed.pdfx.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fadysaeed.pdfx.data.Doc
import com.fadysaeed.pdfx.ui.components.AmberButton
import com.fadysaeed.pdfx.ui.components.Glyph
import com.fadysaeed.pdfx.ui.components.GlyphIcon
import com.fadysaeed.pdfx.ui.components.Hairline
import com.fadysaeed.pdfx.ui.components.IconButton
import com.fadysaeed.pdfx.ui.theme.Ink
import com.fadysaeed.pdfx.ui.theme.Type
import kotlinx.coroutines.launch

/** Which part of the tidy is open. */
enum class TidyStep { DUPLICATES, NAMES, SORT, BROKEN, SCANS, TAGS }

/**
 * The big tidy: everything PDFx found that would make the library easier to find one's way in,
 * one card each, worst first. Each opens its own review; nothing changes until it is approved
 * there, and whatever goes, goes to the trash first.
 */
@Composable
fun TidyScreen(counts: TidyCounts, reading: Int, onStep: (TidyStep) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Page("Tidy up", if (counts.total == 0) "Nothing left to tidy." else "${counts.total} ${if (counts.total == 1) "thing" else "things"} to look at", onClose) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (reading > 0) Text(
                "PDFx is still reading $reading ${if (reading == 1) "file" else "files"}. Their titles and kinds come in as it goes, so this list may grow.",
                style = Type.small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
            Card("Same file kept more than once", counts.duplicates, "files have spare copies. Keep one, trash the rest; the kept one takes their tags.") { onStep(TidyStep.DUPLICATES) }
            Card("Files named with codes", counts.codeNames, "files are called things like s41380-023-02145-x or document(3). Name them by author, year and title.") { onStep(TidyStep.NAMES) }
            Card("Files lying in Downloads and WhatsApp", counts.loose, "files sit where they landed. Put each in a folder for its kind: Papers, Books, Guidelines…") { onStep(TidyStep.SORT) }
            Card("Files that don't open", counts.broken, "files are damaged or empty. Move them to the trash.") { onStep(TidyStep.BROKEN) }
            Card("Scans with no words", counts.bareScans, "scans are only pictures. Read their words so search can find them.") { onStep(TidyStep.SCANS) }
            Card("Tags by topic", -1, "Topics that run through your files, to tag them all at once.") { onStep(TidyStep.TAGS) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Card(title: String, count: Int, what: String, onOpen: () -> Unit) {
    val done = count == 0
    Column(Modifier.fillMaxWidth().clickable(enabled = !done, onClick = onOpen).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).background(if (done) Ink.Teal else Ink.Field, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                if (done) GlyphIcon(Glyph.CHECK, Ink.Black, size = 14.dp) else Text(if (count > 0) "$count" else "·", style = Type.small.copy(color = Ink.Amber))
            }
            Spacer(Modifier.width(14.dp))
            Text(title, style = Type.bodyStrong, modifier = Modifier.weight(1f))
            if (!done) Text("Review", style = Type.bodyStrong.copy(color = Ink.Amber))
        }
        Text(if (done) "All clear." else if (count > 0) "$count $what" else what, style = Type.small, modifier = Modifier.padding(start = 36.dp, top = 4.dp))
    }
    Hairline(Modifier.padding(horizontal = 20.dp), color = Ink.Rule)
}

@Composable
private fun Page(title: String, sub: String?, onClose: () -> Unit, body: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink.Black).windowInsetsPadding(WindowInsets.statusBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Glyph.BACK, "Back", onClose)
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.title)
                if (sub != null) Text(sub, style = Type.small)
            }
        }
        Hairline(Modifier.padding(horizontal = 20.dp), color = Ink.Rule)
        body()
    }
}

@Composable
private fun Tick(on: Boolean) {
    Box(
        Modifier.size(22.dp).border(1.5.dp, if (on) Ink.Amber else Ink.Edge, RoundedCornerShape(4.dp)).background(if (on) Ink.Amber else Ink.Black, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) { if (on) GlyphIcon(Glyph.CHECK, Ink.Black, size = 16.dp) }
}

@Composable
private fun Bottom(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp)) {
        AmberButton(text, onClick, Modifier.fillMaxWidth(), enabled = enabled)
    }
}

/** Old name → new name for each file with a code name; untick any to leave it. */
@Composable
fun RenameReview(load: suspend () -> List<Pair<Doc, String>>, onApply: (List<Pair<Doc, String>>) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var list by remember { mutableStateOf<List<Pair<Doc, String>>?>(null) }
    val skip = remember { mutableStateListOf<Long>() }
    LaunchedEffect(Unit) { list = load() }
    Page("Name files by their titles", list?.let { "${it.size} files" }, onClose) {
        Text(
            "Each file gets its first author, year and title as its name. Untick any you would rather leave as it is. Nothing else about the file changes.",
            style = Type.note, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
        val l = list
        if (l == null) Text("Looking…", style = Type.note, modifier = Modifier.padding(20.dp))
        else LazyColumn(Modifier.weight(1f)) {
            items(l, key = { it.first.id }) { (d, name) ->
                val on = d.id !in skip
                Row(Modifier.fillMaxWidth().clickable { if (on) skip.add(d.id) else skip.remove(d.id) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Tick(on)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(LibraryModel.display(d), style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(name, style = Type.body.copy(color = if (on) Ink.Bone else Ink.Ash), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (!l.isNullOrEmpty()) {
            val n = l.count { it.first.id !in skip }
            Bottom(if (n == 1) "Rename 1 file" else "Rename $n files", n > 0) { onApply(l.filter { it.first.id !in skip }) }
        }
    }
}

/** Loose files by the shelf folder each would go to; untick a shelf or a file to leave it. */
@Composable
fun SortReview(load: suspend () -> Map<Shelf, List<Doc>>, folderFor: (Shelf) -> String, onApply: (Map<Shelf, List<Doc>>) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var groups by remember { mutableStateOf<Map<Shelf, List<Doc>>?>(null) }
    val skip = remember { mutableStateListOf<Long>() }
    val open = remember { mutableStateListOf<Shelf>() }
    LaunchedEffect(Unit) { groups = load() }
    Page("Put loose files away", groups?.let { g -> "${g.values.sumOf { it.size }} files" }, onClose) {
        Text(
            "Files from Downloads, WhatsApp and Received go to a folder for their kind inside Documents/Library. They keep their tags, stars and reading place. Tap a kind to see its files and untick any to leave.",
            style = Type.note, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
        val g = groups
        if (g == null) Text("Looking…", style = Type.note, modifier = Modifier.padding(20.dp))
        else LazyColumn(Modifier.weight(1f)) {
            for ((shelf, docs) in g) {
                item(key = "s:" + shelf.name) {
                    val all = docs.none { it.id in skip }
                    Row(Modifier.fillMaxWidth().clickable { if (shelf in open) open.remove(shelf) else open.add(shelf) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clickable { if (all) skip.addAll(docs.map { it.id }) else skip.removeAll(docs.map { it.id }.toSet()) }) { Tick(docs.any { it.id !in skip }) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(shelf.label, style = Type.bodyStrong)
                            Text("→ ${folderFor(shelf)}", style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("${docs.count { it.id !in skip }}", style = Type.figure)
                    }
                }
                if (shelf in open) items(docs, key = { "d:${it.id}" }) { d ->
                    val on = d.id !in skip
                    Row(Modifier.fillMaxWidth().clickable { if (on) skip.add(d.id) else skip.remove(d.id) }.padding(start = 56.dp, end = 20.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Tick(on)
                        Spacer(Modifier.width(12.dp))
                        Text(LibraryModel.titleOf(d), style = Type.small.copy(color = if (on) Ink.Bone else Ink.Ash), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                item(key = "h:" + shelf.name) { Hairline(Modifier.padding(horizontal = 20.dp), color = Ink.Rule) }
            }
        }
        if (!g.isNullOrEmpty()) {
            val chosen = g.mapValues { (_, docs) -> docs.filter { it.id !in skip } }.filterValues { it.isNotEmpty() }
            val n = chosen.values.sumOf { it.size }
            Bottom(if (n == 1) "Move 1 file" else "Move $n files", n > 0) { onApply(chosen) }
        }
    }
}

/** A list of files with one thing to do to all of them: trash the broken ones, read the scans. */
@Composable
fun FilesReview(
    title: String, note: String, action: String,
    load: suspend () -> List<Doc>, label: (String?) -> String,
    run: suspend (List<Doc>, (String) -> Unit) -> Unit, onClose: () -> Unit,
) {
    var list by remember { mutableStateOf<List<Doc>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    // Leaving while it works would stop it half way.
    BackHandler { if (busy == null) onClose() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { list = load() }
    Page(title, list?.let { "${it.size} files" }, { if (busy == null) onClose() }) {
        Text(note, style = Type.note, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        val l = list
        if (l == null) Text("Looking…", style = Type.note, modifier = Modifier.padding(20.dp))
        else LazyColumn(Modifier.weight(1f)) {
            items(l, key = { it.id }) { d ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Text(LibraryModel.titleOf(d), style = Type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(label(d.folder) + "  ·  " + humanSize(d.size), style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        busy?.let { Text(it, style = Type.bodyStrong.copy(color = Ink.Amber), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
        if (!l.isNullOrEmpty()) Bottom(if (busy != null) "Working…" else action, busy == null) {
            busy = "Starting…"
            scope.launch { run(l) { busy = it }; busy = null; onClose() }
        }
    }
}
