package com.fadysaeed.pdfx.ui.tools

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fadysaeed.pdfx.PdfxApp
import com.fadysaeed.pdfx.data.Doc
import com.fadysaeed.pdfx.engine.Arranger
import com.fadysaeed.pdfx.engine.PageItem
import com.fadysaeed.pdfx.engine.PageSource
import com.fadysaeed.pdfx.engine.Splits
import com.fadysaeed.pdfx.ui.components.AmberButton
import com.fadysaeed.pdfx.ui.components.Chip
import com.fadysaeed.pdfx.ui.components.GhostButton
import com.fadysaeed.pdfx.ui.components.Glyph
import com.fadysaeed.pdfx.ui.components.GlyphIcon
import com.fadysaeed.pdfx.ui.components.Hairline
import com.fadysaeed.pdfx.ui.components.IconButton
import com.fadysaeed.pdfx.ui.components.InkField
import com.fadysaeed.pdfx.ui.library.LibraryModel
import com.fadysaeed.pdfx.ui.theme.Ink
import com.fadysaeed.pdfx.ui.theme.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Reading files for the file tools: where each one is, and how many pages it has. */
object FileWork {
    /** A path to read the file from: its own, or a copy in the app's space when it lives inside another app. */
    suspend fun localPath(context: Context, d: Doc): String = withContext(Dispatchers.IO) {
        d.path?.takeIf { File(it).canRead() }?.let { return@withContext it }
        val app = context.applicationContext as PdfxApp
        app.library.keptCopy(d).takeIf { it.isFile && it.length() > 0 }?.let { return@withContext it.absolutePath }
        val f = File(context.cacheDir, "tools/${d.id}-${System.nanoTime()}.pdf")
        f.parentFile?.mkdirs()
        val ok = runCatching { context.contentResolver.openInputStream(Uri.parse(d.uri))?.use { i -> f.outputStream().use { i.copyTo(it) } } != null }.getOrDefault(false)
        if (!ok) {
            f.delete()
            // Gmail, WhatsApp and the like let another app read a file only for a while.
            throw java.io.IOException(
                "${LibraryModel.display(d)} came from another app, which no longer lets PDFx read it. " +
                    "Open it again from that app (that lets PDFx keep its own copy), then try again.",
            )
        }
        f.absolutePath
    }

    /** The number of pages, or null when the file is locked with a password or cannot be read. */
    fun pages(path: String): Int? = runCatching {
        val doc = com.artifex.mupdf.fitz.Document.openDocument(path)
        try { if (doc.needsPassword()) null else doc.countPages() } finally { doc.destroy() }
    }.getOrNull()

    fun clean(context: Context) { runCatching { File(context.cacheDir, "tools").deleteRecursively() } }
}

/**
 * Several files made into one, in the order shown: the first file's own pages keep everything
 * (contents list, form, marks), the others' pages come with their marks, and the new file's
 * contents list gets one entry per file, so each can be found again.
 */
@Composable
fun MergeScreen(start: Doc?, onClose: () -> Unit, onDone: (Doc) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as PdfxApp
    val scope = rememberCoroutineScope()
    val files = remember { mutableStateListOf<Doc>().apply { start?.let { add(it) } } }
    var name by remember { mutableStateOf(start?.let { LibraryModel.display(it) + " (merged)" } ?: "Merged") }
    var adding by remember { mutableStateOf(start == null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }

    fun merge() {
        if (busy || files.size < 2) return
        busy = true
        problem = null
        val list = files.toList()
        scope.launch {
            val r = runCatching {
                val paths = list.map { FileWork.localPath(context, it) }
                val counts = withContext(Dispatchers.IO) { paths.map { FileWork.pages(it) } }
                val locked = list.filterIndexed { i, _ -> counts[i] == null }
                if (locked.isNotEmpty()) throw IllegalStateException(
                    locked.joinToString(", ") { LibraryModel.display(it) } + (if (locked.size == 1) " is" else " are") +
                        " locked with a password or can't be read. Open it first, or take it off the list.",
                )
                val items = ArrayList<PageItem>()
                val contents = ArrayList<Pair<String, Int>>()
                paths.forEachIndexed { i, p ->
                    contents += LibraryModel.display(list[i]) to items.size
                    repeat(counts[i]!!) { pg -> items += if (i == 0) PageItem(PageSource.ORIGINAL, pg) else PageItem(PageSource.OTHER, pg, p) }
                }
                app.saver.create(list.first(), name.trim().ifBlank { "Merged" }) { out ->
                    Arranger.write(paths.first(), items, out.absolutePath, contents = contents).pages
                }.getOrThrow()
            }
            busy = false
            FileWork.clean(context)
            r.fold(onDone) { problem = it.message ?: "The files could not be merged." }
        }
    }

    BackHandler { if (!busy) onClose() }
    Column(
        Modifier.fillMaxSize().background(Ink.Black).pointerInput(Unit) { detectTapGestures { } }
            .windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Glyph.CLOSE, "Close", { if (!busy) onClose() })
            Column(Modifier.weight(1f)) {
                Text("Merge files", style = Type.title)
                Text(if (files.size == 1) "1 file · add at least one more" else "${files.size} files, in this order", style = Type.small)
            }
            AmberButton(if (busy) "Merging…" else "Merge", ::merge, enabled = files.size >= 2 && !busy, modifier = Modifier.padding(end = 6.dp))
        }
        Hairline(color = Ink.Rule)
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(files, key = { _, d -> d.id }) { i, d ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp).heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = Type.figure, modifier = Modifier.padding(end = 14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(LibraryModel.display(d), style = Type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(d.pages.takeIf { it > 0 }?.let { if (it == 1) "1 page" else "$it pages" }, if (i == 0) "its contents list and form are kept" else null).joinToString(" · "), style = Type.small)
                    }
                    IconButton(Glyph.UP, "Move ${LibraryModel.display(d)} up", { if (i > 0) files.add(i - 1, files.removeAt(i)) }, color = if (i > 0) Ink.Sand else Ink.Slate, size = 44.dp)
                    IconButton(Glyph.DOWN, "Move ${LibraryModel.display(d)} down", { if (i < files.lastIndex) files.add(i + 1, files.removeAt(i)) }, color = if (i < files.lastIndex) Ink.Sand else Ink.Slate, size = 44.dp)
                    IconButton(Glyph.CLOSE, "Take ${LibraryModel.display(d)} off the list", { files.removeAt(i) }, color = Ink.Dust, size = 44.dp)
                }
                Hairline()
            }
            item {
                Row(Modifier.fillMaxWidth().clickable { adding = true }.padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.PLUS, Ink.Amber)
                    Text("Add files", style = Type.bodyStrong.copy(color = Ink.Amber), modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
        Hairline(color = Ink.Rule)
        Column(Modifier.padding(16.dp)) {
            InkField(name, { name = it }, "Name of the new file", label = "New file's name")
            Text(
                problem ?: "Saved beside the first file. The files you merge stay as they are.",
                style = Type.note.copy(color = if (problem != null) Ink.Amber else Ink.Dust), modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    if (adding) AddFiles(skip = files.map { it.id }.toSet(), onDismiss = { adding = false; if (files.isEmpty()) onClose() }) { picked ->
        adding = false
        files.addAll(picked)
        if (files.size == picked.size && picked.isNotEmpty()) name = LibraryModel.display(picked.first()) + " (merged)"
    }
}

/** Every PDF in the library, to tick the ones to add; the newest opened first, with a search. */
@Composable
private fun AddFiles(skip: Set<Long>, onDismiss: () -> Unit, onAdd: (List<Doc>) -> Unit) {
    val app = LocalContext.current.applicationContext as PdfxApp
    var all by remember { mutableStateOf<List<Doc>?>(null) }
    var query by remember { mutableStateOf("") }
    val ticked = remember { mutableStateListOf<Doc>() }
    LaunchedEffect(Unit) {
        all = withContext(Dispatchers.IO) { app.library.dao.all() }
            .filter { !it.inTrash && it.id !in skip }
            .sortedWith(compareByDescending<Doc> { it.lastOpened }.thenByDescending { it.modified })
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth().padding(14.dp).background(Ink.Field, RoundedCornerShape(14.dp)).border(1.dp, Ink.Edge, RoundedCornerShape(14.dp))) {
            Text("Add files", style = Type.title, modifier = Modifier.padding(start = 18.dp, top = 16.dp, end = 18.dp))
            InkField(query, { query = it }, "Search by name", modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
            val shown = all.orEmpty().filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(shown, key = { it.id }) { d ->
                    val on = d in ticked
                    Row(
                        Modifier.fillMaxWidth().clickable { if (on) ticked.remove(d) else ticked.add(d) }.padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.padding(end = 14.dp).border(1.5.dp, if (on) Ink.Amber else Ink.Edge, RoundedCornerShape(5.dp)).background(if (on) Ink.Amber else Ink.Field, RoundedCornerShape(5.dp)).padding(2.dp)) {
                            GlyphIcon(Glyph.CHECK, if (on) Ink.OnAmber else Ink.Field, size = 18.dp)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(LibraryModel.display(d), style = Type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(app.library.folderLabel(d.folder), style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (all != null && shown.isEmpty()) Text("No other files here.", style = Type.note, modifier = Modifier.padding(18.dp))
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                GhostButton("Cancel", onDismiss, minHeight = 48.dp)
                Spacer(Modifier.padding(4.dp))
                AmberButton(if (ticked.size <= 1) "Add" else "Add ${ticked.size}", { onAdd(ticked.toList()) }, enabled = ticked.isNotEmpty())
            }
        }
    }
}

private enum class SplitWay(val label: String) { EVERY("Every few pages"), RANGES("By pages"), SIZE("By size") }

/**
 * One file cut into several, saved beside it: every few pages, by page ranges written out
 * ("1-3, 4-10"), or into files no bigger than a size. The file itself stays as it is.
 */
@Composable
fun SplitDialog(doc: Doc, onDismiss: () -> Unit, onDone: (List<Doc>) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as PdfxApp
    val scope = rememberCoroutineScope()
    var way by remember { mutableStateOf(SplitWay.RANGES) }
    var every by remember { mutableStateOf("1") }
    var ranges by remember { mutableStateOf("") }
    var megabytes by remember { mutableStateOf("10") }
    var count by remember { mutableStateOf<Int?>(null) }
    var path by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(doc.id) {
        runCatching {
            val p = FileWork.localPath(context, doc)
            path = p
            count = withContext(Dispatchers.IO) { FileWork.pages(p) }
            if (count == null) problem = "This file is locked with a password or can't be read."
        }.onFailure { problem = it.message }
    }
    val n = count ?: 0
    // What the choice makes, before anything is written.
    val plan: Pair<List<List<Int>>?, String?> = when (way) {
        SplitWay.EVERY -> every.trim().toIntOrNull()?.takeIf { it >= 1 }?.let { Splits.every(it, n) to null } ?: (null to "Write how many pages go in each file.")
        SplitWay.RANGES -> Splits.ranges(ranges, n)
        SplitWay.SIZE -> null to null
    }
    val parts = plan.first
    val preview = when {
        n == 0 -> "Reading the file…"
        way == SplitWay.SIZE -> "Files of up to ${megabytes.trim().ifBlank { "?" }} MB each, as many as it takes."
        parts == null -> plan.second ?: ""
        parts.size == 1 -> "Makes 1 file: ${Splits.label(parts[0])}."
        else -> "Makes ${parts.size} files: " + parts.take(4).joinToString("; ") { Splits.label(it) } + (if (parts.size > 4) "; …" else ".")
    }
    fun split() {
        val p = path ?: return
        if (busy || n == 0) return
        busy = true
        problem = null
        val name = LibraryModel.display(doc)
        val mb = megabytes.trim().replace(',', '.').toDoubleOrNull()
        scope.launch {
            val r = runCatching {
                val cuts = if (way == SplitWay.SIZE) {
                    val limit = ((mb ?: throw IllegalArgumentException("Write a size in MB, like 10.")) * 1024 * 1024).toLong()
                    if (limit < 50_000) throw IllegalArgumentException("Choose at least 0.05 MB.")
                    withContext(Dispatchers.IO) {
                        Splits.bySize(n, limit, File(context.cacheDir, "tools/size-test.pdf").also { it.parentFile?.mkdirs() }) { pages, f ->
                            Arranger.write(p, pages.map { PageItem(PageSource.ORIGINAL, it) }, f.absolutePath)
                        }
                    }
                } else parts ?: throw IllegalArgumentException(plan.second ?: "Those pages can't be read.")
                if (cuts.size < 2 && cuts.firstOrNull()?.size == n) throw IllegalArgumentException("That keeps the file whole: there is nothing to split.")
                cuts.map { pages ->
                    app.saver.create(doc, "$name (${Splits.label(pages)})") { out ->
                        Arranger.write(p, pages.map { PageItem(PageSource.ORIGINAL, it) }, out.absolutePath).pages
                    }.getOrThrow()
                }
            }
            busy = false
            FileWork.clean(context)
            r.fold(onDone) { problem = it.message ?: "The file could not be split." }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Ink.Field,
        shape = RectangleShape,
        title = { Text("Split into files", style = Type.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(if (n > 0) "${LibraryModel.display(doc)} · $n pages" else LibraryModel.display(doc), style = Type.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SplitWay.entries.forEach { w -> Chip(w.label, { way = w }, selected = way == w) }
                }
                when (way) {
                    SplitWay.EVERY -> InkField(every, { every = it.filter(Char::isDigit).take(4) }, "Pages in each file", label = "Pages in each new file")
                    SplitWay.RANGES -> InkField(ranges, { ranges = it }, "1-3, 4-10, 11-", label = "Pages for each new file, separated by commas")
                    SplitWay.SIZE -> InkField(megabytes, { megabytes = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6) }, "10", label = "Largest size of each file, in MB")
                }
                Text(problem ?: preview, style = Type.note.copy(color = if (problem != null) Ink.Amber else Ink.Dust), modifier = Modifier.padding(top = 10.dp))
                Text("Saved beside the file, which stays as it is.", style = Type.small, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { AmberButton(if (busy) "Splitting…" else "Split", ::split, enabled = !busy && n > 0 && (way == SplitWay.SIZE || parts != null)) },
        dismissButton = { GhostButton("Cancel", { if (!busy) onDismiss() }, minHeight = 52.dp) },
    )
}

/**
 * Trimming the white margins around the pages, so the words are bigger on a phone: the same
 * trim on every page (the pages stay alike as you turn them), or each page to its own ink.
 * Nothing is cut out of the file: only what shows changes, and Show whole pages puts it back.
 */
@Composable
fun TrimDialog(hasTrims: Boolean, busy: Boolean, progress: String?, onDismiss: () -> Unit, onTrim: (sameForAll: Boolean) -> Unit, onRestore: () -> Unit) {
    var same by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Ink.Field,
        shape = RectangleShape,
        title = { Text("Trim white margins", style = Type.title) },
        text = {
            Column {
                Text("The pages show only the part with something on it, with a little white around. Nothing is taken out of the file.", style = Type.note)
                Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Same on every page", { same = true }, selected = same)
                    Chip("Each page its own", { same = false }, selected = !same)
                }
                Text(
                    progress ?: if (same) "Every page keeps the same size, so they line up as you read." else "Each page is cut to its own words; pages may differ in size.",
                    style = Type.small,
                )
                if (hasTrims && !busy) Text(
                    "Show whole pages again", style = Type.bodyStrong.copy(color = Ink.Amber),
                    modifier = Modifier.padding(top = 14.dp).clickable(onClick = onRestore).padding(vertical = 6.dp),
                )
            }
        },
        confirmButton = { AmberButton(if (busy) "Trimming…" else "Trim", { onTrim(same) }, enabled = !busy) },
        dismissButton = { GhostButton("Cancel", { if (!busy) onDismiss() }, minHeight = 52.dp) },
    )
}

@Composable
fun ReadDialog(busy: Boolean, progress: String?, onDismiss: () -> Unit, onRead: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Field,
        shape = RectangleShape,
        title = { Text("Make searchable", style = Type.title) },
        text = {
            Column {
                Text(
                    "Pages that are only pictures, like scans and photos, get their words read and laid invisibly over them. The pages look the same; you can then search, copy and highlight. Pages that already have words are left as they are.",
                    style = Type.note,
                )
                Text(
                    progress ?: "English. It is all done on the phone; nothing leaves it.",
                    style = Type.small,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { AmberButton(if (busy) "Reading…" else "Read the words", onRead, enabled = !busy) },
        dismissButton = { GhostButton(if (busy) "Wait" else "Cancel", onDismiss, minHeight = 52.dp) },
    )
}

/** "12.4 MB", "830 KB". */
fun sizeText(bytes: Long): String = com.fadysaeed.pdfx.ui.library.humanSize(bytes)

/** Where a changed file goes: over this one (its old state stays in Versions) or beside it as a new file. */
@Composable
private fun SaveChoice(asNew: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip("New file beside it", { if (enabled) onChange(true) }, selected = asNew)
        Chip("Replace this file", { if (enabled) onChange(false) }, selected = !asNew)
    }
}

/** What Make smaller shows: the level, and once tried, the sizes before and after. */
class ShrinkUi {
    var level by mutableStateOf(com.fadysaeed.pdfx.engine.Shrink.Level.BALANCED)
    var busy by mutableStateOf(false)
    var note by mutableStateOf<String?>(null)
    var before by mutableStateOf(0L)
    /** The smaller file, made but not yet kept, and its size. */
    var made by mutableStateOf<File?>(null)
    var after by mutableStateOf(0L)
    var asNew by mutableStateOf(true)
}

@Composable
fun ShrinkDialog(ui: ShrinkUi, signed: Boolean, onTry: () -> Unit, onKeep: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!ui.busy) onDismiss() },
        containerColor = Ink.Field,
        shape = RectangleShape,
        title = { Text("Make smaller", style = Type.title) },
        text = { ShrinkBody(ui, signed) },
        confirmButton = {
            val ready = ui.made != null && ui.after < ui.before
            AmberButton(
                when { ui.busy -> "Working…"; ready -> "Keep it"; else -> "Make smaller" },
                { if (ready) onKeep() else onTry() },
                enabled = !ui.busy,
            )
        },
        dismissButton = { GhostButton("Cancel", { if (!ui.busy) onDismiss() }, minHeight = 52.dp) },
    )
}

@Composable
internal fun ShrinkBody(ui: ShrinkUi, signed: Boolean) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(if (ui.before > 0) "Now ${sizeText(ui.before)}." else "Measuring…", style = Type.bodyStrong)
        Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (l in com.fadysaeed.pdfx.engine.Shrink.Level.entries)
                Chip(l.label, { if (!ui.busy) { ui.level = l; ui.made?.delete(); ui.made = null } }, selected = ui.level == l)
        }
        Text(
            when (ui.level) {
                com.fadysaeed.pdfx.engine.Shrink.Level.TIDY -> "Writes the file again without waste. Nothing on the pages changes."
                com.fadysaeed.pdfx.engine.Shrink.Level.BALANCED -> "Also makes big pictures and scans lighter. Still sharp on a phone and when printed."
                com.fadysaeed.pdfx.engine.Shrink.Level.SMALLEST -> "Pictures and scans much lighter. Fine for reading on a screen; print may look soft."
            },
            style = Type.note,
        )
        ui.made?.let {
            val saved = ui.before - ui.after
            Text(
                if (saved > 0) "${sizeText(ui.before)} → ${sizeText(ui.after)}, ${(saved * 100 / ui.before.coerceAtLeast(1))}% smaller."
                else "This file is already as small as this level makes it.",
                style = Type.bodyStrong.copy(color = if (saved > 0) Ink.Amber else Ink.Bone),
                modifier = Modifier.padding(top = 14.dp),
            )
            if (saved > 0) SaveChoice(ui.asNew, { ui.asNew = it }, enabled = !ui.busy)
        }
        if (signed) Text("This file is signed: a smaller copy no longer carries valid signatures.", style = Type.small, modifier = Modifier.padding(top = 10.dp))
        ui.note?.let { Text(it, style = Type.small, modifier = Modifier.padding(top = 10.dp)) }
    }
}

@Composable
fun PasswordDialog(
    hasPassword: Boolean, busy: Boolean, note: String?,
    onLock: (password: String, asNew: Boolean) -> Unit, onRemove: (asNew: Boolean) -> Unit, onDismiss: () -> Unit,
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var asNew by remember { mutableStateOf(!hasPassword) }
    var problem by remember { mutableStateOf<String?>(null) }
    fun lock() {
        problem = com.fadysaeed.pdfx.engine.Secure.problem(first) ?: if (first != second) "The two passwords are not the same." else null
        if (problem == null) onLock(first, asNew)
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Ink.Field,
        shape = RectangleShape,
        title = { Text(if (hasPassword) "Password" else "Lock with a password", style = Type.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    (if (hasPassword) "Give it a new password, or take the password off. " else "") +
                        "Locked with AES-256: any PDF reader asks for the password, and without it nobody can open the file. There is no way back in if it is forgotten.",
                    style = Type.note,
                )
                InkField(first, { first = it; problem = null }, "Password", modifier = Modifier.padding(top = 14.dp), label = if (hasPassword) "New password" else "Password", secret = true)
                InkField(second, { second = it; problem = null }, "The same again", modifier = Modifier.padding(top = 10.dp), label = "Once more", secret = true, onDone = ::lock)
                SaveChoice(asNew, { asNew = it }, enabled = !busy)
                if (hasPassword && !busy) Text(
                    "Take the password off", style = Type.bodyStrong.copy(color = Ink.Amber),
                    modifier = Modifier.padding(top = 14.dp).clickable { onRemove(asNew) }.padding(vertical = 6.dp),
                )
                (problem ?: note)?.let { Text(it, style = Type.small, modifier = Modifier.padding(top = 10.dp)) }
            }
        },
        confirmButton = { AmberButton(if (busy) "Working…" else "Lock", ::lock, enabled = !busy) },
        dismissButton = { GhostButton("Cancel", { if (!busy) onDismiss() }, minHeight = 52.dp) },
    )
}

@Composable
fun DetailsDialog(details: List<Pair<String, String>>?, busy: Boolean, note: String?, onRemove: (asNew: Boolean) -> Unit, onDismiss: () -> Unit) {
    var asNew by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Ink.Field,
        shape = RectangleShape,
        title = { Text("Remove hidden details", style = Type.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when {
                    details == null -> Text("Looking…", style = Type.note)
                    details.isEmpty() -> Text("This file carries no hidden details about who made it or with what.", style = Type.note)
                    else -> {
                        Text("Anyone you send the file to can see these:", style = Type.note)
                        for ((k, v) in details) Column(Modifier.padding(top = 10.dp)) {
                            Text(k, style = Type.small)
                            Text(v, style = Type.body, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        SaveChoice(asNew, { asNew = it }, enabled = !busy)
                    }
                }
                note?.let { Text(it, style = Type.small, modifier = Modifier.padding(top = 10.dp)) }
            }
        },
        confirmButton = {
            if (!details.isNullOrEmpty()) AmberButton(if (busy) "Removing…" else "Remove them", { onRemove(asNew) }, enabled = !busy)
            else GhostButton("Close", onDismiss, minHeight = 52.dp)
        },
        dismissButton = { if (!details.isNullOrEmpty()) GhostButton("Cancel", { if (!busy) onDismiss() }, minHeight = 52.dp) },
    )
}
