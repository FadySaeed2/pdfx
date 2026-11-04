package com.fadysaeed.pdfx.ui.viewer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import com.fadysaeed.pdfx.ui.theme.PlexArabic
import com.fadysaeed.pdfx.ui.theme.ReadingSerif
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import com.fadysaeed.pdfx.engine.BoxAlign
import com.fadysaeed.pdfx.engine.BoxDir
import com.fadysaeed.pdfx.engine.BoxFont
import com.fadysaeed.pdfx.engine.BoxSpacings
import com.fadysaeed.pdfx.engine.BoxStyle
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.unit.em
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fadysaeed.pdfx.PdfxApp
import com.fadysaeed.pdfx.data.Doc
import com.fadysaeed.pdfx.data.Saver
import com.fadysaeed.pdfx.engine.Mark
import com.fadysaeed.pdfx.engine.MarkKind
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.fadysaeed.pdfx.engine.PdfSession
import com.fadysaeed.pdfx.ui.components.AmberButton
import com.fadysaeed.pdfx.ui.components.Chip
import com.fadysaeed.pdfx.ui.components.Corner
import com.fadysaeed.pdfx.ui.components.GhostButton
import com.fadysaeed.pdfx.ui.components.Glyph
import com.fadysaeed.pdfx.ui.components.GlyphIcon
import com.fadysaeed.pdfx.ui.components.Hairline
import com.fadysaeed.pdfx.ui.components.IconButton
import com.fadysaeed.pdfx.ui.components.SheetRow
import com.fadysaeed.pdfx.ui.components.InkField
import com.fadysaeed.pdfx.ui.library.LibraryModel
import com.fadysaeed.pdfx.ui.theme.Ink
import com.fadysaeed.pdfx.ui.theme.Type
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private sealed interface Load {
    data object Opening : Load
    data class Locked(val session: PdfSession, val wrong: Boolean) : Load
    data class Ready(val session: PdfSession) : Load
    data class Failed(val reason: String) : Load
}

/**
 * A document to open, the page to start on when it should not be the last one read, and words to
 * find and highlight straight away (when opened from a search in the library).
 */
data class OpenRequest(val uri: Uri, val page: Int? = null, val query: String? = null, val id: Long = System.nanoTime()) {
    companion object {
        /** Saves the request as plain values; reopening starts at the last page read. */
        val Saver: androidx.compose.runtime.saveable.Saver<OpenRequest?, Any> = androidx.compose.runtime.saveable.Saver(
            save = { r -> r?.let { arrayListOf(it.uri.toString(), it.id.toString()) } ?: arrayListOf<String>() },
            restore = { v ->
                @Suppress("UNCHECKED_CAST")
                val l = v as List<String>
                if (l.size < 2) null else OpenRequest(Uri.parse(l[0]), null, null, l[1].toLongOrNull() ?: System.nanoTime())
            },
        )
    }
}

/**
 * Opens a document, asks for a password when needed, and shows it. Changes are saved when the
 * document is closed and when the app goes to the background; [onClose] gets a message to show
 * in the library, or null.
 */
@OptIn(FlowPreview::class)
@Composable
fun ViewerScreen(
    request: OpenRequest,
    /** For tests: the reader in use (a new one after each save and reopen), and the page in the middle of the screen. */
    onReader: ((Reader, () -> Int) -> Unit)? = null,
    /** Opens another file in place of this one (a file just made by merging). */
    onOpenOther: (Uri) -> Unit = {},
    onClose: (message: String?) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as PdfxApp
    val library = app.library
    var doc by remember(request) { mutableStateOf<Doc?>(null) }
    var load by remember(request) { mutableStateOf<Load>(Load.Opening) }
    var password by remember(request) { mutableStateOf<String?>(null) }
    var saving by remember(request) { mutableStateOf(false) }
    var saveError by remember(request) { mutableStateOf<String?>(null) }
    /** A message to show once the file is open again after saving. */
    var notice by remember(request) { mutableStateOf<String?>(null) }
    /** Carried across a save and reopen: the pages on screen, and the place being read (page, pixels into it). */
    val carry = remember(request) { arrayOfNulls<PageImages>(1) }
    var spot by remember(request) { mutableStateOf<Pair<Int, Int>?>(null) }
    /** The pages screen: open, and asked for while the file was being saved first. */
    var organizer by remember(request) { mutableStateOf<Organizer?>(null) }
    var wantPages by remember(request) { mutableStateOf(false) }
    /** A file tool asked for while the file was being saved first, and the one open now. */
    var wantTool by remember(request) { mutableStateOf<String?>(null) }
    var tool by remember(request) { mutableStateOf<String?>(null) }
    var trimBusy by remember(request) { mutableStateOf(false) }
    var trimNote by remember(request) { mutableStateOf<String?>(null) }
    var hasTrims by remember(request) { mutableStateOf(false) }
    /** Reading the words on pages that are pictures: busy, and what it is doing or found. */
    var readBusy by remember(request) { mutableStateOf(false) }
    var readNote by remember(request) { mutableStateOf<String?>(null) }
    /** Make smaller, password and hidden details: what they show while open. */
    val shrinkUi = remember(request) { com.fadysaeed.pdfx.ui.tools.ShrinkUi() }
    var toolBusy by remember(request) { mutableStateOf(false) }
    var toolNote by remember(request) { mutableStateOf<String?>(null) }
    var details by remember(request) { mutableStateOf<List<Pair<String, String>>?>(null) }
    /** Save as Word, Excel or text: the file made, to open or send. */
    var exportMade by remember(request) { mutableStateOf<java.io.File?>(null) }
    var picturesMade by remember(request) { mutableStateOf<List<java.io.File>>(emptyList()) }
    var organizing by remember(request) { mutableStateOf(false) }
    var leavePages by remember(request) { mutableStateOf(false) }
    var signedPages by remember(request) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fallbackName = remember(request) { library.describe(request.uri).first.removeSuffix(".pdf") }
    val name = doc?.let { LibraryModel.display(it) } ?: fallbackName

    LaunchedEffect(request) {
        doc = runCatching { library.resolve(request.uri) }.getOrNull()
        load = try {
            val s = try {
                PdfSession.open(context, request.uri)
            } catch (t: Throwable) {
                // A file shared from another app that no longer lets PDFx read it: the copy kept from before.
                val kept = doc?.let { library.keptCopy(it) }?.takeIf { it.isFile && it.length() > 0 } ?: throw t
                PdfSession.openFile(kept.absolutePath)
            }
            if (s.needsPassword) Load.Locked(s, wrong = false) else Load.Ready(s)
        } catch (t: Throwable) {
            Load.Failed(t.message?.takeIf { it.isNotBlank() } ?: "The file is not a readable PDF.")
        }
        // While the other app still lets PDFx read it, keep a copy of a shared file.
        doc?.let { d -> if (!d.isFile) library.keepCopy(d, request.uri) }
    }
    DisposableEffect(request) {
        onDispose {
            when (val l = load) {
                is Load.Ready -> l.session.close()
                is Load.Locked -> l.session.close()
                else -> Unit
            }
        }
    }

    when (val l = load) {
        Load.Opening -> { BackHandler { onClose(null) }; Box(Modifier.fillMaxSize().background(Ink.Black)) }
        is Load.Failed -> LaunchedEffect(l) { onClose("Couldn't open $name. ${l.reason} If it came from another app, open it again from there.") }
        is Load.Locked -> {
            BackHandler { onClose(null) }
            LockedPanel(name, l.wrong, onCancel = { onClose(null) }) { pw ->
                scope.launch {
                    load = if (l.session.unlock(pw)) { password = pw; Load.Ready(l.session) } else Load.Locked(l.session, wrong = true)
                }
            }
        }
        is Load.Ready -> {
            val session = l.session
            val last = (session.pageCount - 1).coerceAtLeast(0)
            val start = remember(request) { (request.page ?: doc?.lastPage ?: 0).coerceIn(0, last) }
            val prefs = remember { context.getSharedPreferences("reader", Context.MODE_PRIVATE) }
            val reader = remember(session) { Reader(session, session.pageCount, scope, library.bookmarks, doc?.id, prefs, seed = carry[0]).also { carry[0] = null } }
            val pages = reader.pages!!
            LaunchedEffect(reader) {
                val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.signatures.list() }.getOrDefault(emptyList()) }
                reader.signs.clear(); reader.signs.addAll(saved)
            }
            LaunchedEffect(request, doc?.id) {
                doc?.let { library.recordOpen(it.id, session.pageCount, locked = false) }
            }
            LaunchedEffect(request) {
                val q = request.query?.trim()
                if (!q.isNullOrEmpty()) { reader.searchOpen = true; reader.search(q, start) }
            }
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = start)
            LaunchedEffect(listState, doc?.id) {
                val id = doc?.id ?: return@LaunchedEffect
                snapshotFlow { centreItem(listState) }
                    .distinctUntilChanged()
                    .debounce(400)
                    .collect { library.updatePage(id, it) }
            }

            fun save(then: (Saver.Saved) -> Unit) {
                if (saving) return
                saving = true
                scope.launch {
                    val r = app.saver.save(session, doc, request.uri)
                    saving = false
                    r.fold(then) { saveError = it.message ?: "The file could not be saved." }
                }
            }
            fun leave() {
                if (!session.dirty) { onClose(null); return }
                save { saved ->
                    onClose(if (saved is Saver.Saved.AsCopy) "${saved.doc.name.removeSuffix(".pdf")} belongs to another app, so your changes are saved as a copy in Documents/PDFx." else null)
                }
            }
            /** Saves, then carries on from the saved file; [message] shows once it is open again. */
            /** Carries on from a saved file; [keepPlace] when its pages are the same ones as before. */
            fun reopen(saved: Saver.Saved, message: String?, copyNote: String, keepPlace: Boolean) {
                doc = saved.doc
                scope.launch {
                    val uri = if (saved is Saver.Saved.AsCopy) Uri.fromFile(java.io.File(saved.doc.path!!)) else request.uri
                    val fresh = runCatching { PdfSession.open(context, uri) }.getOrNull() ?: return@launch
                    if (fresh.needsPassword) password?.let { fresh.unlock(it) }
                    if (fresh.needsPassword) { fresh.close(); return@launch }
                    notice = when {
                        message == null -> null
                        saved is Saver.Saved.AsCopy -> "$message $copyNote"
                        else -> message
                    }
                    if (keepPlace) {
                        // Keep the place: the new copy starts with these pages and goes back to this spot.
                        carry[0] = pages
                        spot = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                    }
                    val old = session
                    load = Load.Ready(fresh)
                    old.close()
                }
            }
            fun saveAndReopen(message: String?) {
                save { saved -> reopen(saved, message, "This file belongs to another app, so the signed copy is in Documents/PDFx.", keepPlace = true) }
            }

            // ---------------------------------------------------------------- pages screen
            /** The file as a path to read its pages from: itself, or a copy when it lives inside another app. */
            suspend fun basePath(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                doc?.path?.takeIf { java.io.File(it).canRead() } ?: run {
                    val f = java.io.File(context.cacheDir, "organise/base-${System.nanoTime()}.pdf")
                    f.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(request.uri)?.use { i -> f.outputStream().use { i.copyTo(it) } } ?: error("The file could not be read.")
                    f.absolutePath
                }
            }
            fun openPages() {
                // Changes not yet saved go into the file first, so the pages show them and keep them.
                if (session.dirty) { wantPages = true; saveAndReopen(null); return }
                organizer = Organizer(session.pageCount, scope, session)
            }
            fun openTool(t: String) {
                // The tools read the file on the phone, so changes not yet saved go into it first.
                if (session.dirty) { wantTool = t; saveAndReopen(null); return }
                trimNote = if (t == "trim" && reader.docSignatures.isNotEmpty()) "This file is signed: trimming counts as a change after signing." else null
                toolNote = if ((t == "password" || t == "details") && reader.docSignatures.isNotEmpty()) "This file is signed: the new file no longer carries valid signatures." else null
                if (t == "shrink") shrinkUi.apply { made?.delete(); made = null; note = null; before = 0L }
                if (t == "details") details = null
                tool = t
            }
            fun trim(same: Boolean) {
                if (trimBusy) return
                trimBusy = true
                scope.launch {
                    val n = session.pageCount
                    val found = ArrayList<Pair<Int, com.fadysaeed.pdfx.engine.Box>>()
                    for (i in 0 until n) {
                        trimNote = "Looking at page ${i + 1} of $n…"
                        runCatching { session.contentBox(i) }.getOrNull()?.let { found += i to it }
                    }
                    if (found.isEmpty()) { trimBusy = false; trimNote = "There is nothing on the pages to trim to."; return@launch }
                    val pad = 12f
                    val all = found.fold(found[0].second) { a, (_, b) -> com.fadysaeed.pdfx.engine.Box(minOf(a.x0, b.x0), minOf(a.y0, b.y0), maxOf(a.x1, b.x1), maxOf(a.y1, b.y1)) }
                    val boxes = HashMap<Int, com.fadysaeed.pdfx.engine.Box?>()
                    for ((i, own) in found) {
                        val b = if (same) all else own
                        val size = runCatching { session.pageSize(i) }.getOrNull() ?: continue
                        boxes[i] = com.fadysaeed.pdfx.engine.Box(
                            (b.x0 - pad).coerceAtLeast(0f), (b.y0 - pad).coerceAtLeast(0f),
                            (b.x1 + pad).coerceAtMost(size.width), (b.y1 + pad).coerceAtMost(size.height),
                        )
                    }
                    val ok = runCatching { session.cropPages(boxes) }.getOrDefault(false)
                    trimBusy = false
                    if (!ok) { trimNote = "The margins could not be trimmed."; return@launch }
                    tool = null
                    saveAndReopen(if (boxes.size == 1) "Margins trimmed." else "Margins trimmed on ${boxes.size} pages. More, then Trim white margins, shows whole pages again.")
                }
            }
            fun untrim() {
                if (trimBusy) return
                trimBusy = true
                scope.launch {
                    val ok = runCatching { session.cropPages((0 until session.pageCount).associateWith { null }) }.getOrDefault(false)
                    trimBusy = false
                    tool = null
                    if (ok) saveAndReopen("Whole pages shown again.")
                }
            }
            fun readWords() {
                if (readBusy) return
                readBusy = true
                scope.launch {
                    readNote = "Looking for pages that are pictures…"
                    val todo = runCatching { session.pagesWithoutText() }.getOrDefault(emptyList())
                    if (todo.isEmpty()) { readBusy = false; readNote = "Every page already has words you can search and copy."; return@launch }
                    val found = HashMap<Int, List<com.fadysaeed.pdfx.engine.ReadWord>>()
                    var failed: Throwable? = null
                    for ((k, i) in todo.withIndex()) {
                        readNote = if (todo.size == 1) "Reading page ${i + 1}…" else "Reading page ${i + 1} (${k + 1} of ${todo.size})…"
                        runCatching {
                            val (bmp, scale) = session.renderForReading(i)
                            val ws = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                try { com.fadysaeed.pdfx.engine.Reading.words(bmp) } finally { bmp.recycle() }
                            }
                            if (ws.isNotEmpty()) found[i] = com.fadysaeed.pdfx.engine.Reading.toPage(ws, scale)
                        }.onFailure { failed = it }
                    }
                    if (found.isEmpty()) {
                        readBusy = false
                        readNote = if (failed != null) "The words could not be read: ${failed?.message ?: "the reader did not start"}." else "No words could be found on these pages."
                        return@launch
                    }
                    val ok = runCatching { session.addReadText(found) > 0 }.getOrDefault(false)
                    readBusy = false
                    if (!ok) { readNote = "The words were read but could not be added to the file."; return@launch }
                    tool = null
                    readNote = null
                    val n = found.size
                    saveAndReopen(if (n == 1) "Words read on 1 page: you can search, copy and highlight them now." else "Words read on $n pages: you can search, copy and highlight them now.")
                }
            }
            /**
             * Keeps a file a tool writes from this one: as a new file beside it, which then opens, or
             * over it (its old state stays in Versions), carrying on from it with [passwordAfter].
             */
            fun keepMade(
                asNew: Boolean, suffix: String, message: String, passwordAfter: String?,
                after: () -> Unit, make: (base: String, out: java.io.File) -> Int,
            ) {
                scope.launch {
                    val base = runCatching { basePath() }.getOrElse { after(); toolNote = it.message ?: "The file could not be read."; return@launch }
                    if (asNew) {
                        val r = app.saver.create(doc, "$name ($suffix)") { out -> make(base, out) }
                        after()
                        r.fold({ made ->
                            tool = null
                            val path = made.path
                            if (path != null) onOpenOther(Uri.fromFile(java.io.File(path)))
                            else reader.problem = "$message Saved as ${made.name.removeSuffix(".pdf")}."
                        }) { toolNote = it.message ?: "The file could not be saved." }
                    } else {
                        val r = app.saver.replace(doc, request.uri) { out -> make(base, out) }
                        after()
                        r.fold({ saved ->
                            tool = null
                            password = passwordAfter
                            reopen(saved, message, "This file belongs to another app, so the new one is in Documents/PDFx.", keepPlace = true)
                        }) { toolNote = it.message ?: "The file could not be saved." }
                    }
                }
            }
            fun shrinkTry() {
                val ui = shrinkUi
                if (ui.busy) return
                ui.busy = true
                ui.note = null
                val level = ui.level
                scope.launch {
                    val r = runCatching {
                        val base = basePath()
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val dir = java.io.File(context.cacheDir, "shrink").also { it.deleteRecursively(); it.mkdirs() }
                            val out = java.io.File(dir, "try-${System.nanoTime()}.pdf")
                            com.fadysaeed.pdfx.engine.Shrink.write(base, out.absolutePath, level, password)
                            out
                        }
                    }
                    ui.busy = false
                    r.fold({ f -> ui.made?.delete(); ui.made = f; ui.after = f.length() }) { ui.note = it.message ?: "The file could not be made smaller." }
                }
            }
            fun shrinkKeep() {
                val ui = shrinkUi
                val f = ui.made ?: return
                if (ui.busy) return
                ui.busy = true
                val pages = session.pageCount
                keepMade(
                    ui.asNew, "smaller", "Made smaller: ${com.fadysaeed.pdfx.ui.tools.sizeText(ui.before)} → ${com.fadysaeed.pdfx.ui.tools.sizeText(ui.after)}.",
                    password, after = { ui.busy = false },
                ) { _, out -> f.copyTo(out, overwrite = true); pages }
            }
            fun lockWith(newPassword: String, asNew: Boolean) {
                if (toolBusy) return
                toolBusy = true
                toolNote = null
                val old = password
                keepMade(asNew, "locked", "Locked with a password.", newPassword, after = { toolBusy = false }) { base, out ->
                    com.fadysaeed.pdfx.engine.Secure.protect(base, out.absolutePath, newPassword, old)
                }
            }
            fun unlockFile(asNew: Boolean) {
                val old = password ?: return
                if (toolBusy) return
                toolBusy = true
                toolNote = null
                keepMade(asNew, "unlocked", "Password taken off.", null, after = { toolBusy = false }) { base, out ->
                    com.fadysaeed.pdfx.engine.Secure.unprotect(base, out.absolutePath, old)
                }
            }
            fun scrubDetails(asNew: Boolean) {
                if (toolBusy) return
                toolBusy = true
                toolNote = null
                val pw = password
                keepMade(asNew, "clean", "Hidden details removed.", pw, after = { toolBusy = false }) { base, out ->
                    com.fadysaeed.pdfx.engine.Secure.scrub(base, out.absolutePath, pw)
                }
            }
            /** Reads the words on [todo] (pages that are pictures) into the file; [say] hears what it is doing. Returns pages read. */
            suspend fun readPictures(todo: List<Int>, say: (String) -> Unit): Int {
                val found = HashMap<Int, List<com.fadysaeed.pdfx.engine.ReadWord>>()
                for ((k, i) in todo.withIndex()) {
                    say(if (todo.size == 1) "Reading the words on page ${i + 1}…" else "Reading the words on page ${i + 1} (${k + 1} of ${todo.size})…")
                    runCatching {
                        val (bmp, scale) = session.renderForReading(i)
                        val ws = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            try { com.fadysaeed.pdfx.engine.Reading.words(bmp) } finally { bmp.recycle() }
                        }
                        if (ws.isNotEmpty()) found[i] = com.fadysaeed.pdfx.engine.Reading.toPage(ws, scale)
                    }
                }
                if (found.isNotEmpty()) runCatching { session.addReadText(found) }
                return found.size
            }
            fun exportAs(kind: com.fadysaeed.pdfx.engine.ExportPages.Kind, pagesText: String) {
                if (toolBusy) return
                toolBusy = true
                toolNote = null
                exportMade = null
                scope.launch {
                    val (pages, why) = com.fadysaeed.pdfx.engine.ExportPages.pagesFrom(pagesText, session.pageCount)
                    if (pages == null || pages.isEmpty()) { toolBusy = false; toolNote = why ?: "Pick at least one page."; return@launch }
                    // Pages that are only pictures have no words to write: read them first.
                    val bare = runCatching { session.pagesWithoutText() }.getOrDefault(emptyList()).filter { it in pages }
                    val read = if (bare.isNotEmpty()) readPictures(bare) { toolNote = it } else 0
                    val say: (Int, Int) -> Unit = { k, n -> toolNote = if (n == 1) "Working…" else "Going through page $k of $n…" }
                    val result = runCatching {
                        val dir = doc?.path?.let { java.io.File(it).parentFile }?.takeIf { it.isDirectory && it.canWrite() }
                            ?: app.library.homeDir.also { it.mkdirs() }
                        val out = com.fadysaeed.pdfx.data.Library.uniqueTarget(dir, "${name.removeSuffix(".pdf")}.${kind.ext}")
                        val tmp = java.io.File(context.cacheDir, "export").also { it.deleteRecursively(); it.mkdirs() }
                        when (kind) {
                            com.fadysaeed.pdfx.engine.ExportPages.Kind.EXCEL -> {
                                val tables = com.fadysaeed.pdfx.engine.ExportPages.tables(session, pages, say)
                                if (tables.isEmpty()) return@runCatching null
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { out.outputStream().use { com.fadysaeed.pdfx.engine.XlsxExport.write(tables, it) } }
                                out to tables.size
                            }
                            else -> {
                                val blocks = com.fadysaeed.pdfx.engine.ExportPages.blocks(session, pages, tmp, withPictures = kind == com.fadysaeed.pdfx.engine.ExportPages.Kind.WORD, progress = say)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    if (kind == com.fadysaeed.pdfx.engine.ExportPages.Kind.WORD) out.outputStream().use { com.fadysaeed.pdfx.engine.DocxExport.write(blocks, it, name.removeSuffix(".pdf")) }
                                    else out.bufferedWriter().use { com.fadysaeed.pdfx.engine.TextExport.write(blocks, it) }
                                }
                                tmp.deleteRecursively()
                                out to blocks.size
                            }
                        }
                    }
                    toolBusy = false
                    result.fold({ made ->
                        if (made == null) { toolNote = "No tables found on these pages, so there is nothing to put in Excel."; return@fold }
                        val (f, count) = made
                        android.media.MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), null, null)
                        exportMade = f
                        toolNote = "Saved as ${f.name} in ${f.parentFile?.name ?: "your files"}" +
                            (if (kind == com.fadysaeed.pdfx.engine.ExportPages.Kind.EXCEL) ", ${if (count == 1) "1 table" else "$count tables"}, a sheet each." else ".") +
                            (if (read > 0) " The words on ${if (read == 1) "1 scanned page" else "$read scanned pages"} were read first; save this file to keep them searchable too." else "")
                    }) { toolNote = it.message ?: "The file could not be made." }
                }
            }
            fun savePictures(format: com.fadysaeed.pdfx.engine.PagePictures.Format, dpi: Int, pagesText: String) {
                if (toolBusy) return
                toolBusy = true
                toolNote = null
                picturesMade = emptyList()
                scope.launch {
                    val (pages, why) = com.fadysaeed.pdfx.engine.ExportPages.pagesFrom(pagesText, session.pageCount)
                    if (pages == null || pages.isEmpty()) { toolBusy = false; toolNote = why ?: "Pick at least one page."; return@launch }
                    val r = runCatching {
                        val base = name.removeSuffix(".pdf")
                        val parent = doc?.path?.let { java.io.File(it).parentFile }?.takeIf { it.isDirectory && it.canWrite() }
                            ?: app.library.homeDir.also { it.mkdirs() }
                        var dir = java.io.File(parent, "$base pages")
                        var n = 2
                        while (dir.exists()) { dir = java.io.File(parent, "$base pages ($n)"); n++ }
                        val files = com.fadysaeed.pdfx.engine.PagePictures.write(session, pages, dir, base, format, dpi) { k, all ->
                            toolNote = if (all == 1) "Drawing the page…" else "Drawing page $k of $all…"
                        }
                        android.media.MediaScannerConnection.scanFile(context, files.map { it.absolutePath }.toTypedArray(), null, null)
                        files
                    }
                    toolBusy = false
                    r.fold({ files ->
                        picturesMade = files
                        toolNote = "Saved ${if (files.size == 1) "1 picture" else "${files.size} pictures"} in the folder ${files.first().parentFile?.name}, beside the file."
                    }) { toolNote = it.message ?: "The pictures could not be made." }
                }
            }
            fun findToRedact(text: String) {
                if (toolBusy || text.isBlank()) return
                toolBusy = true
                toolNote = null
                reader.markAllToRedact(text.trim()) { (places, pages) ->
                    toolBusy = false
                    toolNote = when {
                        places == 0 -> "\"${text.trim()}\" is not in the file's words. Pages that are only pictures need Make searchable first."
                        else -> "Marked ${if (places == 1) "1 place" else "$places places"} on ${if (pages == 1) "1 page" else "$pages pages"}. They show as red boxes: look them over, then black them out."
                    }
                }
            }
            fun applyRedactions(scrub: Boolean) {
                if (toolBusy) return
                toolBusy = true
                toolNote = null
                scope.launch {
                    val n = runCatching { session.applyRedactions() }.getOrElse { toolBusy = false; toolNote = it.message ?: "They could not be blacked out."; return@launch }
                    if (scrub) runCatching { session.scrubDetails() }
                    toolBusy = false
                    if (n == 0) { toolNote = "Nothing is marked yet."; return@launch }
                    tool = null
                    reader.redactions = 0
                    saveAndReopen(if (n == 1) "Blacked out for good. What was under it is gone from the file." else "Blacked out for good in $n places. What was under them is gone from the file.")
                }
            }
            fun closePages() {
                organizer?.close(); organizer = null
                leavePages = false
                runCatching { java.io.File(context.cacheDir, "organise").deleteRecursively() }
            }
            /** "pages 3–5, 8": the picked pages where they now stand. */
            fun pageList(o: Organizer): String {
                val at = o.items.withIndex().filter { it.value.id in o.picked }.map { it.index + 1 }
                val runs = ArrayList<String>()
                var i = 0
                while (i < at.size) {
                    var j = i
                    while (j + 1 < at.size && at[j + 1] == at[j] + 1) j++
                    runs += if (j > i) "${at[i]}–${at[j]}" else "${at[i]}"
                    i = j + 1
                }
                return (if (at.size == 1) "page " else "pages ") + runs.joinToString(", ")
            }
            fun savePages(asNewFile: Boolean) {
                val o = organizer ?: return
                if (organizing) return
                val items = o.items.toList()
                organizing = true
                scope.launch {
                    val base = runCatching { basePath() }.getOrElse { organizing = false; o.note = it.message ?: "The file could not be read."; return@launch }
                    val make: (java.io.File) -> Int = { out -> com.fadysaeed.pdfx.engine.Arranger.write(base, items, out.absolutePath, password).pages }
                    if (asNewFile) {
                        val r = app.saver.create(doc, "$name (pages)", make = make)
                        organizing = false
                        r.fold({ made ->
                            closePages()
                            reader.problem = "Saved the new arrangement as ${made.name.removeSuffix(".pdf")}. The signed original is unchanged."
                        }) { o.note = it.message ?: "The pages could not be saved." }
                    } else {
                        val r = app.saver.replace(doc, request.uri, make)
                        organizing = false
                        r.fold({ saved ->
                            closePages()
                            reopen(saved, "Pages saved.", "This file belongs to another app, so the new arrangement is in Documents/PDFx.", keepPlace = false)
                        }) { o.note = it.message ?: "The pages could not be saved." }
                    }
                }
            }
            fun extractPages() {
                val o = organizer ?: return
                val items = o.pickedItems
                if (items.isEmpty() || organizing) return
                val label = pageList(o)
                organizing = true
                scope.launch {
                    val base = runCatching { basePath() }.getOrElse { organizing = false; o.note = it.message ?: "The file could not be read."; return@launch }
                    val r = app.saver.create(doc, "$name ($label)") { out -> com.fadysaeed.pdfx.engine.Arranger.write(base, items, out.absolutePath, password).pages }
                    organizing = false
                    r.fold({ made ->
                        val where = made.path?.let { java.io.File(it).parentFile?.name } ?: "your files"
                        o.note = "Saved ${label} as ${made.name.removeSuffix(".pdf")} in $where"
                    }) { o.note = it.message ?: "The new file could not be made." }
                }
            }
            SideEffect {
                reader.saveNow = { msg -> saveAndReopen(msg) }
                reader.openPages = ::openPages
                reader.openTool = ::openTool
                reader.hasPassword = password != null
                onReader?.invoke(reader) { centreItem(listState) }
            }
            LaunchedEffect(reader) {
                val (i, o) = spot ?: return@LaunchedEffect
                spot = null
                kotlinx.coroutines.withTimeoutOrNull(3000) { snapshotFlow { pages.aspects.size }.first { it > i } }
                if (listState.firstVisibleItemIndex != i || listState.firstVisibleItemScrollOffset != o) listState.scrollToItem(i, o)
            }
            LaunchedEffect(reader) { notice?.let { reader.problem = it; notice = null } }
            LaunchedEffect(reader) { reader.countRedactions() }
            LaunchedEffect(reader) { if (wantPages) { wantPages = false; openPages() } }
            LaunchedEffect(reader) { wantTool?.let { wantTool = null; openTool(it) } }
            LaunchedEffect(tool) { if (tool == "trim") hasTrims = runCatching { session.hasCrops() }.getOrDefault(false) }
            // Going to the background: save, then carry on from the saved file when back.
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
                if (session.dirty) saveAndReopen(null)
            }

            ViewerContent(
                name = name,
                reader = reader,
                aspects = pages.aspects,
                widths = pages.widths,
                image = { pages.images[it] },
                tile = { pages.tiles[it] },
                listState = listState,
                onWant = pages::want,
                onTiles = pages::wantTiles,
                onBack = ::leave,
            )
            when (tool) {
                "trim" -> com.fadysaeed.pdfx.ui.tools.TrimDialog(hasTrims, trimBusy, trimNote, onDismiss = { tool = null }, onTrim = ::trim, onRestore = ::untrim)
                "shrink" -> {
                    LaunchedEffect(Unit) {
                        shrinkUi.before = runCatching { val b = basePath(); java.io.File(b).length() }.getOrDefault(0L)
                    }
                    com.fadysaeed.pdfx.ui.tools.ShrinkDialog(
                        shrinkUi, signed = reader.docSignatures.isNotEmpty(), onTry = ::shrinkTry, onKeep = ::shrinkKeep,
                        onDismiss = { tool = null; shrinkUi.made?.delete(); shrinkUi.made = null },
                    )
                }
                "password" -> com.fadysaeed.pdfx.ui.tools.PasswordDialog(
                    hasPassword = password != null, busy = toolBusy, note = toolNote,
                    onLock = ::lockWith, onRemove = ::unlockFile, onDismiss = { tool = null },
                )
                "watermark" -> com.fadysaeed.pdfx.ui.tools.WatermarkDialog(
                    busy = toolBusy, note = toolNote,
                    onMake = { m, asNew ->
                        if (!toolBusy) {
                            toolBusy = true; toolNote = null
                            val pw = password
                            keepMade(asNew, "watermark", "Watermark added.", pw, after = { toolBusy = false }) { base, out ->
                                com.fadysaeed.pdfx.engine.Stamp.watermark(base, out.absolutePath, m, pw)
                            }
                        }
                    },
                    onDismiss = { if (!toolBusy) { tool = null; toolNote = null } },
                )
                "numbers" -> com.fadysaeed.pdfx.ui.tools.PageNumbersDialog(
                    pageCount = session.pageCount, busy = toolBusy, note = toolNote,
                    onMake = { n, asNew ->
                        if (!toolBusy) {
                            toolBusy = true; toolNote = null
                            val pw = password
                            keepMade(asNew, "numbered", "Pages numbered.", pw, after = { toolBusy = false }) { base, out ->
                                com.fadysaeed.pdfx.engine.Stamp.pageNumbers(base, out.absolutePath, n, pw)
                            }
                        }
                    },
                    onDismiss = { if (!toolBusy) { tool = null; toolNote = null } },
                )
                "details" -> {
                    LaunchedEffect(Unit) {
                        details = runCatching {
                            val b = basePath()
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.fadysaeed.pdfx.engine.Secure.details(b, password) }
                        }.getOrElse { toolNote = it.message ?: "The file could not be read."; emptyList() }
                    }
                    com.fadysaeed.pdfx.ui.tools.DetailsDialog(details, toolBusy, toolNote, onRemove = ::scrubDetails, onDismiss = { tool = null })
                }
                "redact" -> {
                    LaunchedEffect(Unit) { reader.countRedactions() }
                    com.fadysaeed.pdfx.ui.tools.RedactDialog(
                        marked = reader.redactions, busy = toolBusy, note = toolNote, signed = reader.docSignatures.isNotEmpty(),
                        onFind = ::findToRedact, onApply = ::applyRedactions,
                        onByHand = { tool = null; reader.pickTool(Tool.REDACT); reader.startMarking() },
                        onDismiss = { if (!toolBusy) tool = null },
                    )
                }
                "export" -> com.fadysaeed.pdfx.ui.tools.ExportDialog(
                    busy = toolBusy, note = toolNote, made = exportMade, pageCount = session.pageCount,
                    onExport = ::exportAs,
                    onOpen = { f -> com.fadysaeed.pdfx.ui.tools.openWith(context, f)?.let { toolNote = it } },
                    onShare = { f -> com.fadysaeed.pdfx.ui.tools.shareFile(context, f) },
                    onDismiss = { if (!toolBusy) { tool = null; exportMade = null; toolNote = null } },
                )
                "pictures" -> com.fadysaeed.pdfx.ui.tools.PicturesDialog(
                    busy = toolBusy, note = toolNote, made = picturesMade, pageCount = session.pageCount,
                    onMake = ::savePictures,
                    onShare = { com.fadysaeed.pdfx.ui.tools.shareFiles(context, it) },
                    onDismiss = { if (!toolBusy) { tool = null; picturesMade = emptyList(); toolNote = null } },
                )
                "read" -> com.fadysaeed.pdfx.ui.tools.ReadDialog(readBusy, readNote, onDismiss = { if (!readBusy) { tool = null; readNote = null } }, onRead = ::readWords)
                "split" -> doc?.let { d ->
                    com.fadysaeed.pdfx.ui.tools.SplitDialog(d, onDismiss = { tool = null }) { made ->
                        tool = null
                        reader.problem = "Split into ${made.size} files, beside this one. They are in your library."
                    }
                }
                "merge" -> doc?.let { d ->
                    com.fadysaeed.pdfx.ui.tools.MergeScreen(d, onClose = { tool = null }) { made ->
                        tool = null
                        // Straight into the new file; this one is already saved.
                        val path = made.path
                        if (path != null) onOpenOther(Uri.fromFile(java.io.File(path)))
                        else reader.problem = "Merged into ${made.name.removeSuffix(".pdf")}. It is in your library."
                    }
                }
            }
            organizer?.let { o ->
                val pickPdf = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        val f = java.io.File(context.cacheDir, "organise/other-${System.nanoTime()}.pdf")
                        val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { f.parentFile?.mkdirs(); context.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { i.copyTo(it) } } != null }.getOrDefault(false)
                        }
                        val n = if (ok) o.openOther(f.absolutePath) else null
                        if (n == null || n <= 0) { o.note = "That file could not be opened: it may be locked with a password, or not a PDF."; return@launch }
                        o.insert(List(n) { com.fadysaeed.pdfx.engine.PageItem(com.fadysaeed.pdfx.engine.PageSource.OTHER, it, f.absolutePath) })
                        o.note = if (n == 1) "1 page added" else "$n pages added"
                    }
                }
                val pickPhotos = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(30)) { uris ->
                    if (uris.isEmpty()) return@rememberLauncherForActivityResult
                    scope.launch {
                        val paths = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            uris.mapNotNull { u -> com.fadysaeed.pdfx.data.Pictures.prepare(context, u, java.io.File(context.cacheDir, "organise/photo-${System.nanoTime()}.jpg"))?.absolutePath }
                        }
                        if (paths.isEmpty()) { o.note = "Those photos could not be read."; return@launch }
                        o.insert(paths.map { com.fadysaeed.pdfx.engine.PageItem(com.fadysaeed.pdfx.engine.PageSource.PICTURE, path = it) })
                        o.note = if (paths.size == 1) "1 photo added" else "${paths.size} photos added"
                    }
                }
                val scanPages = com.fadysaeed.pdfx.ui.tools.rememberScanner(
                    onPages = { files ->
                        o.insert(files.map { com.fadysaeed.pdfx.engine.PageItem(com.fadysaeed.pdfx.engine.PageSource.PICTURE, path = it.absolutePath) })
                        o.note = if (files.size == 1) "1 scanned page added" else "${files.size} scanned pages added"
                    },
                    onProblem = { o.note = it },
                )
                BackHandler { if (o.changed) leavePages = true else closePages() }
                Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } }) {
                    PagesScreen(
                        o, name, organizing,
                        onClose = { if (o.changed) leavePages = true else closePages() },
                        onSave = { if (reader.docSignatures.isNotEmpty()) signedPages = true else savePages(asNewFile = false) },
                        onExtract = ::extractPages,
                        onAddBlank = { scope.launch { val (w, h) = o.blankSize(); o.insert(listOf(com.fadysaeed.pdfx.engine.PageItem(com.fadysaeed.pdfx.engine.PageSource.BLANK, width = w, height = h))) } },
                        onAddPdf = { pickPdf.launch(arrayOf("application/pdf")) },
                        onAddScan = scanPages,
                        onAddPictures = { pickPhotos.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    )
                }
                if (leavePages) AlertDialog(
                    onDismissRequest = { leavePages = false },
                    containerColor = Ink.Field,
                    shape = RectangleShape,
                    title = { Text("Leave without saving?", style = Type.title) },
                    text = { Text("The pages go back to how they are in the file.", style = Type.note) },
                    confirmButton = { AmberButton("Keep working", { leavePages = false }) },
                    dismissButton = { GhostButton("Leave", { closePages() }, minHeight = 52.dp) },
                )
                if (signedPages) AlertDialog(
                    onDismissRequest = { signedPages = false },
                    containerColor = Ink.Field,
                    shape = RectangleShape,
                    title = { Text("This file is signed", style = Type.title) },
                    text = { Text("Changing its pages breaks its digital signatures: they would no longer check out. You can save the new arrangement as a new file and keep the signed one as it is.", style = Type.note) },
                    confirmButton = { AmberButton("Save as a new file", { signedPages = false; savePages(asNewFile = true) }) },
                    dismissButton = { GhostButton("Change it anyway", { signedPages = false; savePages(asNewFile = false) }, minHeight = 52.dp) },
                )
            }
            if (saving) {
                Box(Modifier.fillMaxSize().background(Ink.Black.copy(alpha = 0.55f)).pointerInput(Unit) { detectTapGestures { } }, contentAlignment = Alignment.Center) {
                    Text("Saving…", style = Type.title, modifier = Modifier.background(Ink.Field, Corner).border(1.dp, Ink.Edge, Corner).padding(horizontal = 20.dp, vertical = 14.dp))
                }
            }
            saveError?.let { err ->
                AlertDialog(
                    onDismissRequest = { saveError = null },
                    containerColor = Ink.Field,
                    shape = RectangleShape,
                    title = { Text("Couldn't save", style = Type.title) },
                    text = { Text("$err The original file is unchanged.", style = Type.note) },
                    confirmButton = { AmberButton("Keep working", { saveError = null }) },
                    dismissButton = { GhostButton("Close without saving", { saveError = null; onClose("Changes to $name were not saved.") }, minHeight = 52.dp) },
                )
            }
        }
    }
}

private enum class ViewerSheet { CONTENTS, DISPLAY, MORE, TOOLS }

/** The reader: pages or reflowed text, with its bars, search, selection, contents and display menus. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerContent(
    name: String,
    reader: Reader,
    aspects: List<Float>,
    widths: List<Float>,
    image: (Int) -> ImageBitmap?,
    tile: (Int) -> Tile?,
    listState: LazyListState,
    onWant: (Want) -> Unit,
    onTiles: (List<TileReq>) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pageCount = reader.pageCount
    val current by remember { derivedStateOf { centreItem(listState) } }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var sheet by remember { mutableStateOf<ViewerSheet?>(null) }
    var askPage by remember { mutableStateOf(false) }
    var askFlatten by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf<String?>(null) }
    val showBars = reader.chrome || reader.searchOpen || reader.marking

    BackHandler {
        when {
            reader.colorPick != null -> reader.colorPick = null
            reader.resultsOpen -> reader.resultsOpen = false
            reader.selection != null -> reader.clearSelection()
            reader.selectedMark != null -> reader.selectMark(null)
            reader.marking -> reader.stopMarking()
            reader.searchOpen -> reader.closeSearch()
            else -> onBack()
        }
    }
    /** The page under the reading line (a third of the way down the screen) and how far down it that line is. */
    fun spot(): Pair<Int, Float>? {
        val info = listState.layoutInfo
        val line = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) / 3
        val item = info.visibleItemsInfo.firstOrNull { it.offset <= line && it.offset + it.size > line }
            ?: info.visibleItemsInfo.firstOrNull() ?: return null
        return item.index to ((line - item.offset).toFloat() / item.size.coerceAtLeast(1)).coerceIn(0f, 1f)
    }
    fun toggleReflow() = reader.toggleReflow(spot())

    /**
     * Like tapping the top of the screen on an iPhone: a tap on the file's name glides to the very
     * start of the file, and offers the way back for a few seconds in case it was a slip.
     */
    fun goToStart() {
        val i = listState.firstVisibleItemIndex
        val o = listState.firstVisibleItemScrollOffset
        if (i == 0 && o == 0) return
        reader.backTo = if (i >= 1) i to o else null
        scope.launch { listState.animateScrollToItem(0) }
    }

    // After switching between pages and reflowed text, put the reading line back where it was.
    LaunchedEffect(reader.reflow) {
        val (page, frac) = reader.readingSpot ?: return@LaunchedEffect
        reader.readingSpot = null
        listState.scrollToItem(page)
        if (reader.reflow) snapshotFlow { reader.paragraphs[page] }.filterNotNull().first()
        withFrameNanos { }
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == page } ?: return@LaunchedEffect
        val info = listState.layoutInfo
        val line = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) / 3
        listState.scrollBy(item.offset + item.size * frac - line)
    }

    LaunchedEffect(reader.backTo) { if (reader.backTo != null) { delay(6000); reader.backTo = null } }
    LaunchedEffect(flash) { val f = flash; if (f != null) { delay(1600L + (f.length - 20).coerceAtLeast(0) * 45L); flash = null } }
    LaunchedEffect(reader.undoNotice) { if (reader.undoNotice != null) { delay(4500); reader.undoNotice = null } }
    LaunchedEffect(reader.problem) { reader.problem?.let { flash = it; reader.problem = null } }

    Box(Modifier.fillMaxSize().background(Ink.Black)) {
        val padding = PaddingValues(top = top + 64.dp, bottom = bottom + 72.dp)
        if (reader.reflow) {
            ReflowView(reader, listState, padding)
        } else {
            DocumentView(
                reader = reader,
                aspects = aspects,
                widths = widths,
                image = image,
                tile = tile,
                listState = listState,
                onWant = onWant,
                onTiles = onTiles,
                onOpenUri = { uri -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) } },
                contentPadding = padding,
                selectionBar = {
                    val sel = reader.selection
                    if (sel != null) SelectionFloat(
                        reader,
                        onCopy = { if (reader.copySelection(context)) flash = "Copied"; reader.clearSelection() },
                        onFind = { val t = sel.text.trim(); reader.clearSelection(); reader.searchOpen = true; reader.search(t, current) },
                        onShare = {
                            val t = sel.text.trim()
                            runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), null)) }
                        },
                    )
                },
            )
        }

        AnimatedVisibility(showBars, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Column(Modifier.fillMaxWidth().background(Ink.Black).windowInsetsPadding(WindowInsets.statusBars)) {
                if (reader.marking) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Done", style = Type.bodyStrong.copy(color = Ink.Amber, fontSize = 15.sp), modifier = Modifier.clickable { reader.stopMarking() }.padding(horizontal = 14.dp, vertical = 12.dp))
                        Column(Modifier.weight(1f).clickable(onClickLabel = "Go to the start of the file") { goToStart() }) {
                            Text("Mark up", style = Type.title)
                            Text(if (reader.dirty) "Saved when you close the file" else name, style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(Glyph.UNDO, "Undo", reader::undo, color = if (reader.canUndo) Ink.Bone else Ink.Slate, size = 44.dp)
                        IconButton(Glyph.REDO, "Redo", reader::redo, color = if (reader.canRedo) Ink.Bone else Ink.Slate, size = 44.dp)
                    }
                } else if (reader.searchOpen) {
                    SearchBar(reader, current)
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(Glyph.BACK, "Back to library", onBack)
                        Column(Modifier.weight(1f).clickable(onClickLabel = "Go to the start of the file") { goToStart() }.padding(end = 4.dp)) {
                            Text(name, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (pageCount == 1) "1 page" else "$pageCount pages", style = Type.small)
                        }
                        IconButton(Glyph.SEARCH, "Find in document", { reader.searchOpen = true }, color = Ink.Sand, size = 42.dp)
                        IconButton(Glyph.REFLOW, if (reader.reflow) "Show the pages" else "Reflow text for reading", ::toggleReflow, color = if (reader.reflow) Ink.Amber else Ink.Sand, size = 42.dp)
                        if (reader.canMark && !reader.reflow) IconButton(Glyph.PEN, "Mark up", reader::startMarking, color = Ink.Sand, size = 42.dp)
                        // The rest live under one button, so the file's name has room.
                        IconButton(Glyph.MORE, "More: bookmark, contents, pages, page colours", { sheet = ViewerSheet.MORE }, color = Ink.Sand, size = 42.dp)
                    }
                }
                Hairline(color = Ink.Rule)
                if (!reader.marking && !reader.searchOpen && reader.docSignatures.isNotEmpty()) {
                    SignaturesBanner(reader.docSignatures) { reader.signaturesOpen = true }
                    Hairline(color = Ink.Rule)
                }
            }
        }

        // Every search result, with the words around it, in a panel from the right.
        AnimatedVisibility(
            reader.searchOpen && reader.resultsOpen,
            enter = fadeIn() + slideInHorizontally { it / 3 },
            exit = fadeOut() + slideOutHorizontally { it / 3 },
            modifier = Modifier.fillMaxSize().padding(top = top + 60.dp),
        ) {
            SearchResults(reader, onClose = { reader.resultsOpen = false })
        }

        // Where you are in the document: a thin amber line along the bottom edge, always visible.
        val fraction = if (pageCount <= 1) 1f else current / (pageCount - 1f)
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .fillMaxWidth(fraction.coerceIn(0.01f, 1f))
                .height(2.dp)
                .background(Ink.Amber),
        )

        AnimatedVisibility(showBars && pageCount > 1 && !reader.marking, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterEnd)) {
            Scrubber(current, pageCount, Modifier.padding(top = top + 72.dp, bottom = bottom + 80.dp)) { p ->
                scope.launch { listState.scrollToItem(p) }
            }
        }

        val sel = reader.selection
        Column(
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).imePadding().padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val back = reader.backTo
            if (back != null) {
                Row(
                    Modifier.background(Ink.Field, Corner).border(1.dp, Ink.Amber.copy(alpha = 0.6f), Corner)
                        .clickable(onClickLabel = "Go back to where you were") {
                            reader.backTo = null
                            scope.launch { listState.animateScrollToItem(back.first, back.second) }
                        }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Back to page ${back.first + 1}", style = Type.bodyStrong.copy(color = Ink.Amber, fontSize = 14.sp))
                }
                Spacer(Modifier.height(10.dp))
            }
            val msg = flash
            if (msg != null) {
                Text(msg, style = Type.note.copy(color = Ink.Bone), modifier = Modifier.background(Ink.Field, Corner).border(1.dp, Ink.Edge, Corner).padding(horizontal = 14.dp, vertical = 9.dp))
                Spacer(Modifier.height(10.dp))
            }
            val undo = reader.undoNotice
            if (undo != null) {
                Row(
                    Modifier.background(Ink.Field, Corner).border(1.dp, Ink.Edge, Corner).padding(start = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(undo, style = Type.note.copy(color = Ink.Bone))
                    Spacer(Modifier.width(6.dp))
                    BarAction("Undo") { reader.undoNotice = null; reader.undo() }
                }
                Spacer(Modifier.height(10.dp))
            }
            val picked = reader.selectedMark
            val field = reader.editingField
            if (field != null) {
                FieldPanel(field, reader)
            } else if (picked != null) {
                MarkBar(picked, reader)
            } else if (reader.marking) {
                MarkupBar(reader)
            } else if (sel != null) {
                Unit
            } else {
                AnimatedVisibility(showBars, enter = fadeIn(), exit = fadeOut()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .background(Ink.Black, Corner)
                                .border(1.dp, Ink.Edge, Corner)
                                .clickable { askPage = true }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text("${current + 1} / $pageCount", style = Type.figure)
                        }
                        if (reader.hasForm && !reader.reflow) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Fill form", style = Type.bodyStrong.copy(color = Ink.Black, fontSize = 14.sp),
                                modifier = Modifier.background(Ink.Amber, Corner).clickable { reader.startFilling() }.padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    // A picture to put on the pages: made a phone-sized JPEG first, kept for the session.
    val pickPicture = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val out = java.io.File(context.cacheDir, "pictures/p-${System.nanoTime()}.jpg").apply { parentFile?.mkdirs() }
            val made = com.fadysaeed.pdfx.data.Pictures.prepare(context, uri, out)
            if (made == null) reader.problem = "That picture could not be read."
            else {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(made.absolutePath, o)
                reader.pictureAspect = if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else 1f
                reader.picture = made.absolutePath
            }
        }
    }
    LaunchedEffect(reader) {
        reader.pickPicture = { pickPicture.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }
    reader.editRun?.let { run -> EditWordsDialog(run, onDismiss = { reader.editRun = null }, onSave = reader::saveEdit) }
    reader.textAsk?.let { ask -> WordsAsk(ask, onDismiss = { reader.textAsk = null }, onDone = reader::submitText) }
    reader.colorPick?.let { pick -> ColorPickerDialog(pick.initial, reader.customColors, onDismiss = { reader.colorPick = null }) { c -> reader.applyPicked(pick, c) } }
    reader.zoomFigure?.let { (pg, bx) -> FigureViewer(reader, pg, bx) { reader.zoomFigure = null } }
    reader.signPad?.let { initials -> SignPadHost(reader, initials) }
    DigitalSignHost(reader)
    if (reader.stampAsk) StampAsk(onDismiss = { reader.stampAsk = false }) { k -> reader.stampAsk = false; reader.addStamp(k) }
    reader.deleteSignAsk?.let { sg ->
        AlertDialog(
            onDismissRequest = { reader.deleteSignAsk = null },
            containerColor = Ink.Field, shape = RectangleShape,
            title = { Text(if (sg.initials) "Delete these initials?" else "Delete this signature?", style = Type.title) },
            text = { Text("It is removed from this phone. Files you have already signed keep it.", style = Type.note) },
            confirmButton = { AmberButton("Delete", { reader.deleteSignAsk = null; reader.deleteSign(sg, (context.applicationContext as PdfxApp).signatures) }) },
            dismissButton = { GhostButton("Keep", { reader.deleteSignAsk = null }, minHeight = 52.dp) },
        )
    }
    reader.boxEdit?.let { e ->
        BoxEditor(e.mark?.note ?: "", e.mark?.style ?: reader.boxStyle, isNew = e.mark == null, recent = reader.customColors, onDismiss = { reader.boxEdit = null }) { t, st -> reader.submitBox(e, t, st) }
    }

    if (askPage) {
        GoToPage(pageCount, onDismiss = { askPage = false }) { p -> askPage = false; reader.go(p - 1) }
    }
    if (askFlatten) {
        AlertDialog(
            onDismissRequest = { askFlatten = false },
            containerColor = Ink.Field,
            shape = RectangleShape,
            title = { Text("Flatten every mark?", style = Type.title) },
            text = { Text("Highlights, drawings, text and notes become part of the pages. After the file is saved, no app (PDFx included) can move, change or remove them. Undo still works until then.", style = Type.note) },
            confirmButton = { AmberButton("Flatten", { askFlatten = false; reader.flatten(); flash = "Marks are now part of the pages" }) },
            dismissButton = { GhostButton("Cancel", { askFlatten = false }, minHeight = 52.dp) },
        )
    }

    val open = sheet
    if (open != null) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { sheet = null },
            sheetState = state,
            containerColor = Ink.Field,
            scrimColor = Ink.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
            dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).width(36.dp).height(3.dp).background(Ink.Edge)) },
        ) {
            Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 8.dp)) {
                // Close the sheet first, then open what was picked: a screen opened while the
                // sheet is still being taken down can upset the composition.
                fun then(action: () -> Unit) {
                    scope.launch { state.hide() }.invokeOnCompletion { sheet = null; action() }
                }
                when (open) {
                    ViewerSheet.CONTENTS -> ContentsSheetOnTab(reader, null, current, name) { p, y -> sheet = null; reader.go(p, y) }
                    ViewerSheet.DISPLAY -> DisplaySheet(reader, onFlatten = { sheet = null; askFlatten = true }, onReflow = ::toggleReflow)
                    ViewerSheet.MORE -> {
                        val marked = current in reader.bookmarks
                        SheetRow(
                            if (marked) Glyph.BOOKMARK_FILLED else Glyph.BOOKMARK,
                            if (marked) "Remove the bookmark on page ${current + 1}" else "Bookmark page ${current + 1}",
                            { then { reader.toggleBookmark(current); flash = if (marked) "Bookmark removed" else "Page ${current + 1} bookmarked" } },
                            color = if (marked) Ink.Amber else Ink.Bone,
                        )
                        SheetRow(Glyph.LIST, "Contents and bookmarks", { reader.loadOutline(); sheet = ViewerSheet.CONTENTS })
                        if (reader.canMark && !reader.reflow) SheetRow(Glyph.GRID, "Pages", { then { reader.openPages?.invoke() } }, note = "Put them in order, turn, add or take out, or save some as a new file")
                        if (reader.canMark) SheetRow(Glyph.LOCK, "File tools", { sheet = ViewerSheet.TOOLS }, note = "Searchable, smaller, password, watermark, page numbers, split, merge")
                        SheetRow(Glyph.MOON, "Page colours and reading mode", { sheet = ViewerSheet.DISPLAY })
                    }
                    ViewerSheet.TOOLS -> {
                        if (!reader.reflow) SheetRow(Glyph.SEARCH, "Make searchable", { then { reader.openTool?.invoke("read") } }, note = "Reads the words on scanned pages, on the phone")
                        SheetRow(Glyph.SHARE, "Save as Word, Excel or text", { then { reader.openTool?.invoke("export") } }, note = "Word to edit, the tables in Excel, or plain text")
                        SheetRow(Glyph.PAGE, "Save pages as pictures", { then { reader.openTool?.invoke("pictures") } }, note = "PNG or JPEG, one a page")
                        SheetRow(Glyph.DOWN, "Make smaller", { then { reader.openTool?.invoke("shrink") } }, note = "Shows the size before and after")
                        SheetRow(Glyph.LOCK, if (reader.hasPassword) "Password" else "Lock with a password", { then { reader.openTool?.invoke("password") } }, note = if (reader.hasPassword) "Change it or take it off" else "AES-256; it opens only with the password")
                        SheetRow(Glyph.PEN, "Black out (redact)", { then { reader.openTool?.invoke("redact") } }, note = if (reader.redactions > 0) "${reader.redactions} marked; black them out for good" else "Names, numbers or areas, taken out of the file for good")
                        SheetRow(Glyph.TRASH, "Remove hidden details", { then { reader.openTool?.invoke("details") } }, note = "Author, programs, dates and editing history")
                        SheetRow(Glyph.RENAME, "Watermark", { then { reader.openTool?.invoke("watermark") } }, note = "CONFIDENTIAL, DRAFT or your own words across the pages")
                        SheetRow(Glyph.LIST, "Page numbers", { then { reader.openTool?.invoke("numbers") } }, note = "Where you want them, from the page you want")
                        if (!reader.reflow) SheetRow(Glyph.PAGE, "Trim white margins", { then { reader.openTool?.invoke("trim") } }, note = "Bigger words on a phone; whole pages can come back")
                        SheetRow(Glyph.GRID, "Split into files…", { then { reader.openTool?.invoke("split") } })
                        SheetRow(Glyph.PLUS, "Merge with other files…", { then { reader.openTool?.invoke("merge") } })
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(reader: Reader, current: Int) {
    var text by remember { mutableStateOf(reader.query) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (reader.query.isEmpty()) runCatching { focus.requestFocus() } }
    LaunchedEffect(text) {
        if (text == reader.query) return@LaunchedEffect
        delay(350)
        reader.search(text, current)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(Glyph.CLOSE, "Close search", reader::closeSearch, color = Ink.Sand, size = 44.dp)
        val style = Type.body.copy(fontSize = 16.sp)
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = style.copy(color = Ink.Bone),
            cursorBrush = SolidColor(Ink.Amber),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { reader.search(text, current) }),
            modifier = Modifier.weight(1f).focusRequester(focus),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).background(Ink.Field, Corner).border(1.dp, Ink.Edge, Corner).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) Text("Find in this document", style = style.copy(color = Ink.Ash), maxLines = 1)
                    inner()
                }
            },
        )
        Spacer(Modifier.width(10.dp))
        val count = when {
            reader.query.isBlank() -> ""
            reader.order.isEmpty() && reader.searching -> "Searching…"
            reader.order.isEmpty() -> "None"
            else -> "${reader.current + 1} of ${reader.order.size}" + if (reader.searching) "…" else ""
        }
        Text(count, style = Type.figure, maxLines = 1)
        IconButton(Glyph.UP, "Previous match", reader::previous, color = if (reader.order.isEmpty()) Ink.Slate else Ink.Bone, size = 44.dp)
        IconButton(Glyph.DOWN, "Next match", reader::next, color = if (reader.order.isEmpty()) Ink.Slate else Ink.Bone, size = 44.dp)
        IconButton(
            Glyph.LIST, if (reader.resultsOpen) "Hide the list of results" else "Show every result",
            { if (reader.order.isNotEmpty() || reader.resultsOpen) reader.resultsOpen = !reader.resultsOpen },
            color = when { reader.resultsOpen -> Ink.Amber; reader.order.isEmpty() -> Ink.Slate; else -> Ink.Bone }, size = 44.dp,
        )
    }
}

@Composable
private fun BarAction(label: String, onClick: () -> Unit) {
    Text(label, style = Type.bodyStrong.copy(color = Ink.Amber, fontSize = 14.sp), modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 13.dp))
}

/** A thin rail on the right: drag it to move through a long document fast. */
@Composable
private fun Scrubber(current: Int, pageCount: Int, modifier: Modifier = Modifier, onPage: (Int) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragPage by remember { mutableStateOf(current) }
    BoxWithConstraints(modifier.fillMaxHeight().width(40.dp)) {
        val h = constraints.maxHeight.toFloat()
        val thumbH = 44.dp
        val shown = if (dragging) dragPage else current
        val frac = if (pageCount <= 1) 0f else shown / (pageCount - 1f)
        val travel = h - with(androidx.compose.ui.platform.LocalDensity.current) { thumbH.toPx() }
        fun pageAt(y: Float) = ((y / h) * (pageCount - 1)).roundToInt().coerceIn(0, pageCount - 1)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(pageCount) {
                    detectVerticalDragGestures(
                        onDragStart = { o -> dragging = true; dragPage = pageAt(o.y); onPage(dragPage) },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        val p = pageAt(change.position.y)
                        if (p != dragPage) { dragPage = p; onPage(p) }
                        change.consume()
                    }
                },
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, (frac * travel).roundToInt()) }
                    .padding(end = 6.dp)
                    .width(if (dragging) 6.dp else 4.dp)
                    .height(thumbH)
                    .background(if (dragging) Ink.Amber else Ink.Sand.copy(alpha = 0.7f), RoundedCornerShape(2.dp)),
            )
            if (dragging) {
                Text(
                    "p. ${dragPage + 1}",
                    style = Type.figure.copy(color = Ink.OnAmber),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(-56.dp.roundToPx(), (frac * travel).roundToInt() + 8.dp.roundToPx()) }
                        .background(Ink.Amber, Corner)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun GoToPage(pageCount: Int, onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    val n = text.toIntOrNull()
    val ok = n != null && n in 1..pageCount
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Field,
        shape = RectangleShape,
        title = { Text("Go to page", style = Type.title) },
        text = {
            Column {
                Text("1 to $pageCount", style = Type.note)
                Spacer(Modifier.height(10.dp))
                InkField(text, { v -> text = v.filter { it.isDigit() }.take(6) }, "Page number", onDone = { if (ok) onGo(n!!) })
            }
        },
        confirmButton = { AmberButton("Go", { if (ok) onGo(n!!) }, enabled = ok) },
        dismissButton = { GhostButton("Cancel", onDismiss, minHeight = 52.dp) },
    )
}

@Composable
internal fun ContentsSheet(reader: Reader, current: Int, onPage: (Int, Float) -> Unit) = ContentsSheetOnTab(reader, null, current, onPage = onPage)

/** The contents sheet opened on a given tab (screenshot tests); null picks the sensible one. */
@Composable
internal fun ContentsSheetOnTab(reader: Reader, startTab: Int?, current: Int = 0, name: String = "", onPage: (Int, Float) -> Unit = { _, _ -> }) {
    var tab by remember { mutableStateOf(startTab ?: if (reader.outline.isNullOrEmpty() && reader.bookmarks.isNotEmpty()) 1 else 0) }
    LaunchedEffect(tab) { if (tab == 2) reader.loadAllMarks() }
    Row(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Contents", { tab = 0 }, selected = tab == 0)
        Chip(if (reader.bookmarks.isEmpty()) "Bookmarks" else "Bookmarks  ${reader.bookmarks.size}", { tab = 1 }, selected = tab == 1)
        if (reader.canMark) Chip("Marks", { tab = 2 }, selected = tab == 2)
    }
    if (tab == 2) {
        Hairline(color = Ink.Rule)
        MarksList(reader, reader.allMarks, name, onPage)
        return
    }
    Hairline(color = Ink.Rule)
    if (tab == 0) {
        val outline = reader.outline
        when {
            outline == null -> Text("Reading the contents…", style = Type.note, modifier = Modifier.padding(20.dp))
            outline.isEmpty() -> Text("This file has no table of contents. Bookmarks you add show up in the other tab.", style = Type.note, modifier = Modifier.padding(20.dp))
            else -> {
                // The section being read is the last entry that starts at or before this page.
                val here = outline.indexOfLast { it.page in 0..current }
                LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    items(outline.size) { i ->
                        val e = outline[i]
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = e.page >= 0) { onPage(e.page, 0f) }
                                .padding(start = 20.dp + (16 * e.depth.coerceAtMost(4)).dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                e.title,
                                style = (if (e.depth == 0) Type.bodyStrong else Type.body).copy(color = if (i == here) Ink.Amber else if (e.depth == 0) Ink.Bone else Ink.Sand),
                                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            if (e.page >= 0) Text("${e.page + 1}", style = Type.figure, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            }
        }
    } else {
        if (reader.bookmarks.isEmpty()) {
            Text("No bookmarks yet. Tap the bookmark at the top while reading to mark a page.", style = Type.note, modifier = Modifier.padding(20.dp))
        } else {
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(reader.bookmarks.toList()) { p ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onPage(p, 0f) }.padding(start = 20.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GlyphIcon(Glyph.BOOKMARK_FILLED, Ink.Amber, size = 18.dp)
                        Spacer(Modifier.width(16.dp))
                        Text("Page ${p + 1}", style = Type.body.copy(color = if (p == current) Ink.Amber else Ink.Bone), modifier = Modifier.weight(1f))
                        IconButton(Glyph.CLOSE, "Remove bookmark on page ${p + 1}", { reader.toggleBookmark(p) }, color = Ink.Dust, size = 44.dp)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DisplaySheet(reader: Reader, onFlatten: () -> Unit = {}, onReflow: () -> Unit = { reader.toggleReflow(null) }) {
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 8.dp)) {
        Text("PAGES", style = Type.eyebrow)
        Spacer(Modifier.height(4.dp))
    }
    PaperMode.entries.forEach { m ->
        val sel = reader.mode == m
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { reader.changeMode(m) }.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(34.dp).background(paperColor(m)).border(1.dp, if (sel) Ink.Amber else Ink.Edge), contentAlignment = Alignment.Center) {
                Text("Aa", style = Type.bodyStrong.copy(color = reflowInk(m), fontSize = 13.sp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(m.label, style = Type.body.copy(color = if (sel) Ink.Amber else Ink.Bone))
                Text(m.note, style = Type.small)
            }
            if (sel) GlyphIcon(Glyph.CHECK, Ink.Amber, size = 20.dp)
        }
    }
    Spacer(Modifier.height(8.dp))
    Hairline(color = Ink.Rule)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onReflow).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Reflow text", style = Type.body.copy(color = if (reader.reflow) Ink.Amber else Ink.Bone))
            Text("Just the words, sized for the phone. Handy for small print.", style = Type.small)
        }
        Chip(if (reader.reflow) "On" else "Off", onReflow, selected = reader.reflow)
    }
    if (reader.reflow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Text size", style = Type.note, modifier = Modifier.weight(1f))
            GhostButton("A−", { reader.changeReflowSize(reader.reflowSize - 2) }, minHeight = 44.dp)
            Text("${reader.reflowSize}", style = Type.figure, modifier = Modifier.padding(horizontal = 14.dp))
            GhostButton("A+", { reader.changeReflowSize(reader.reflowSize + 2) }, minHeight = 44.dp)
        }
        ReflowChoice("Font", ReflowFont.entries.map { it.label }, reader.reflowFont.ordinal) { reader.changeReflowFont(ReflowFont.entries[it]) }
        ReflowChoice("Line spacing", listOf("Tight", "Normal", "Loose"), reader.reflowSpacing, reader::changeReflowSpacing)
        ReflowChoice("Margins", listOf("Narrow", "Normal", "Wide"), reader.reflowMargin, reader::changeReflowMargin)
        ReflowChoice("Lines", listOf("Ragged", "Justified"), if (reader.reflowJustify) 1 else 0) { reader.changeReflowJustify(it == 1) }
        ReflowChoice("Page marks", listOf("Hide", "Show"), if (reader.reflowPageMarks) 1 else 0) { reader.changeReflowPageMarks(it == 1) }
        Text("Arabic paragraphs use IBM Plex Sans Arabic and read right to left.", style = Type.small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    }
    if (reader.canMark) {
        Hairline(color = Ink.Rule)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onFlatten).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Flatten marks", style = Type.body)
                Text("Make every highlight, drawing and note part of the page, so no app can change it.", style = Type.small)
            }
        }
    }
}

@Composable
private fun ReflowChoice(label: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.note, modifier = Modifier.weight(1f))
        options.forEachIndexed { i, o ->
            Spacer(Modifier.width(6.dp))
            Chip(o, { onPick(i) }, selected = i == selected)
        }
    }
}

fun reflowInk(mode: PaperMode): Color = when (mode) {
    PaperMode.PAPER -> Color(0xFF1E1B16)
    PaperMode.NIGHT -> Ink.Bone
    PaperMode.SEPIA -> Color(0xFF3B2C1C)
}

/**
 * Each page's words as paragraphs, in the chosen size, font, spacing and colours: headings larger,
 * bold and italic kept, lists with hanging bullets, pictures in place, Arabic right to left in IBM
 * Plex Sans Arabic, lines no longer than is comfortable to read, search hits marked, and any of
 * it can be selected and copied.
 */
@Composable
private fun ReflowView(reader: Reader, listState: LazyListState, padding: PaddingValues) {
    val ink = reflowInk(reader.mode)
    val bg = if (reader.mode == PaperMode.NIGHT) Ink.Black else paperColor(reader.mode)
    val size = reader.reflowSize
    val spacing = ReflowSpacings[reader.reflowSpacing]
    val margin = ReflowMargins[reader.reflowMargin].dp
    val latin = if (reader.reflowFont == ReflowFont.SERIF) ReadingSerif else PlexArabic
    val query = reader.query.trim().takeIf { reader.searchOpen && it.isNotEmpty() }
    val mark = Ink.Amber.copy(alpha = if (reader.mode == PaperMode.NIGHT) 0.45f else 0.5f)
    val filter = remember(reader.mode) { pageFilter(reader.mode)?.let { androidx.compose.ui.graphics.ColorFilter.colorMatrix(it) } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Search, contents and bookmarks land on the page they point to.
    LaunchedEffect(reader.jump) {
        val j = reader.jump ?: return@LaunchedEffect
        listState.scrollToItem(j.page)
    }
    SelectionContainer {
        LazyColumn(
            state = listState,
            contentPadding = padding,
            modifier = Modifier.fillMaxSize().background(bg).pointerInput(Unit) { detectTapGestures(onTap = { reader.chrome = !reader.chrome }) },
        ) {
            items(reader.pageCount, key = { it }) { i ->
                LaunchedEffect(i) { reader.loadParagraphs(i) }
                val paras = reader.paragraphs[i]
                Box(Modifier.fillMaxWidth().padding(horizontal = margin), contentAlignment = Alignment.TopCenter) {
                    // About 70 characters to a line, however wide the screen.
                    BoxWithConstraints(Modifier.widthIn(max = (size * 36).dp).fillMaxWidth()) {
                        val colPx = with(density) { maxWidth.roundToPx() }
                        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy((size * 0.85f).dp)) {
                            if (reader.reflowPageMarks) DisableSelection {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.weight(1f).height(1.dp).background(ink.copy(alpha = 0.14f)))
                                    Text("${i + 1}", style = Type.small.copy(color = ink.copy(alpha = 0.45f)), modifier = Modifier.padding(start = 10.dp))
                                }
                            }
                            when {
                                paras == null -> Spacer(Modifier.height(240.dp))
                                paras.isEmpty() -> DisableSelection {
                                    Text("No text on this page. It may be a picture or a scan.", style = Type.note.copy(color = ink.copy(alpha = 0.6f)))
                                }
                                else -> paras.forEach { p ->
                                    val fig = p.figure
                                    if (fig != null) {
                                        val pw = reader.pages?.widths?.getOrNull(i)?.takeIf { it > 0f } ?: 595f
                                        val frac = (fig.width / pw * 1.15f).coerceIn(0.4f, 1f)
                                        val wPx = (colPx * frac).toInt().coerceAtLeast(1)
                                        LaunchedEffect(i, fig, wPx) { reader.loadFigure(i, fig, wPx) }
                                        val img = reader.figures[reader.figureKey(i, fig, wPx)]
                                        val aspect = (fig.width / fig.height.coerceAtLeast(1f)).coerceIn(0.2f, 6f)
                                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            Box(Modifier.fillMaxWidth(frac).aspectRatio(aspect).background(ink.copy(alpha = 0.05f)).clickable { reader.zoomFigure = i to fig }) {
                                                if (img != null) androidx.compose.foundation.Image(img, null, Modifier.fillMaxSize(), colorFilter = filter)
                                            }
                                        }
                                        p.figureInfo?.let { FigureCaption(it, size, ink, latin, query, mark) }
                                        return@forEach
                                    }
                                    val table = p.table
                                    if (table != null) {
                                        ReflowTableView(reader, i, table, size, ink, bg, latin, query, mark, filter)
                                        return@forEach
                                    }
                                    val arabic = isArabic(p.text)
                                    val scale = when (p.level) { 1 -> 1.45f; 2 -> 1.18f; else -> 1f }
                                    val style = TextStyle(
                                        fontFamily = if (arabic) PlexArabic else latin,
                                        fontWeight = if (p.level > 0) FontWeight.SemiBold else FontWeight.Normal,
                                        fontSize = (size * scale).sp,
                                        lineHeight = (size * scale * if (p.level > 0) 1.3f else spacing).sp,
                                        color = ink,
                                        textDirection = TextDirection.Content,
                                        textAlign = if (reader.reflowJustify && p.level == 0) TextAlign.Justify else TextAlign.Start,
                                        hyphens = if (reader.reflowJustify && !arabic) androidx.compose.ui.text.style.Hyphens.Auto else androidx.compose.ui.text.style.Hyphens.None,
                                        lineBreak = androidx.compose.ui.text.style.LineBreak.Paragraph,
                                    )
                                    val words = styled(p, query, mark)
                                    if (p.list) {
                                        Row(Modifier.fillMaxWidth()) {
                                            val marker = p.marker.let { m -> if (m.length == 1 && !m[0].isLetterOrDigit()) "•" else m }
                                            Text(marker, style = style.copy(textAlign = TextAlign.End, color = ink.copy(alpha = 0.7f)), modifier = Modifier.width((size * 1.9f).dp).padding(end = (size * 0.5f).dp))
                                            Text(words, style = style, modifier = Modifier.weight(1f))
                                        }
                                    } else {
                                        Text(words, style = style, modifier = Modifier.fillMaxWidth().padding(top = if (p.level > 0) (size * 0.6f).dp else 0.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A picture or table from the page, full screen: pinch to zoom, drag to look around, double tap to reset. */
@Composable
internal fun FigureViewer(reader: Reader, page: Int, box: com.fadysaeed.pdfx.engine.Box, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Ink.Black)) {
            val wPx = (constraints.maxWidth * 2).coerceAtMost(3200)
            LaunchedEffect(page, box, wPx) { reader.loadFigure(page, box, wPx) }
            val img = reader.figures[reader.figureKey(page, box, wPx)]
            var scale by remember { mutableFloatStateOf(1f) }
            var shift by remember { mutableStateOf(Offset.Zero) }
            val filter = remember(reader.mode) { pageFilter(reader.mode)?.let { androidx.compose.ui.graphics.ColorFilter.colorMatrix(it) } }
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            shift = if (scale == 1f) Offset.Zero else shift + pan
                        }
                    }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = 1f; shift = Offset.Zero }) },
                contentAlignment = Alignment.Center,
            ) {
                if (img != null) {
                    androidx.compose.foundation.Image(
                        img, contentDescription = "Picture from page ${page + 1}", colorFilter = filter,
                        modifier = Modifier.fillMaxWidth().aspectRatio((box.width / box.height.coerceAtLeast(1f)).coerceIn(0.1f, 10f))
                            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = shift.x; translationY = shift.y },
                    )
                } else {
                    Text("Drawing…", style = Type.note)
                }
            }
            IconButton(Glyph.CLOSE, "Close", onClose, color = Ink.Bone, modifier = Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.statusBars).padding(8.dp))
        }
    }
}

/** A paragraph's words with its bold and italic runs, and search hits marked. */
internal fun styled(p: com.fadysaeed.pdfx.engine.Para, query: String?, mark: Color): AnnotatedString = buildAnnotatedString {
    append(p.text)
    p.spans.forEach { sp ->
        val end = sp.end.coerceAtMost(p.text.length)
        if (sp.start < end) addStyle(
            SpanStyle(
                fontWeight = if (sp.bold) FontWeight.Bold else null,
                fontStyle = if (sp.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
            ),
            sp.start, end,
        )
    }
    if (!query.isNullOrEmpty()) {
        com.fadysaeed.pdfx.engine.findAll(p.text, query).forEach { r -> addStyle(SpanStyle(background = mark), r.first, r.last + 1) }
    }
}

/** True when the first letter of [text] is Arabic, so the paragraph reads right to left. */
internal fun isArabic(text: String): Boolean {
    for (ch in text) {
        if (!ch.isLetter()) continue
        val b = Character.UnicodeBlock.of(ch)
        return b == Character.UnicodeBlock.ARABIC || b == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A ||
            b == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B || b == Character.UnicodeBlock.ARABIC_SUPPLEMENT
    }
    return false
}

/** [text] with every match of [query] marked, ignoring case. */
internal fun highlighted(text: String, query: String?, color: Color): AnnotatedString {
    if (query.isNullOrEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        var from = 0
        while (true) {
            val at = text.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            addStyle(SpanStyle(background = color), at, at + query.length)
            from = at + query.length
        }
    }
}


private val MarkLabelColor = Ink.Sand

/** Tools on the first row; colours, widths or shapes for the chosen tool on the second. */
@Composable
private fun MarkupBar(reader: Reader) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Ink.Black)
            .padding(top = 8.dp),
    ) {
        Hairline(color = Ink.Rule)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Tool.entries.forEach { t -> Chip(t.label, { reader.pickTool(t) }, selected = reader.tool == t) }
        }
        val t = reader.tool
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 8.dp).heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (t) {
                Tool.ERASER -> Text(if (reader.stylusSeen) "Touch a mark with the pen to remove it, or hold the pen's button while drawing. Undo brings it back." else "Tap or drag over a mark to remove it. Undo brings it back.", style = Type.small)
                Tool.SELECT -> {
                    Text("A tap on handwriting picks", style = Type.small)
                    InkScope.entries.forEach { sc -> Chip(sc.label, { reader.pickInkScope(sc) }, selected = reader.inkScope == sc) }
                }
                Tool.SIGN -> {
                    Chip("Digital", { reader.pickDigital(!reader.digital) }, selected = reader.digital)
                    Box(Modifier.width(1.dp).height(28.dp).background(Ink.Rule))
                    if (reader.digital) {
                        reader.certs.forEach { c -> CertChip(c, reader.cert == c, { reader.pickCert(c.id) }, { reader.deleteCertAsk = c }) }
                        Chip("Add certificate", { reader.certSheet = true })
                    } else {
                        reader.signs.forEach { sg -> SignThumb(sg, reader.signId == sg.id || (reader.signId == null && sg == reader.signs.first()), { reader.pickSign(sg.id) }, { reader.deleteSignAsk = sg }) }
                        Chip("New signature", { reader.signPad = false })
                        Chip("New initials", { reader.signPad = true })
                        Box(Modifier.width(1.dp).height(28.dp).background(Ink.Rule))
                        Chip("With date", { reader.pickSignWithDate(!reader.signWithDate) }, selected = reader.signWithDate)
                        if (reader.signWithDate) DateStyleChips(reader)
                    }
                }
                Tool.REDACT -> {
                    Chip("Words", { reader.redactArea = false }, selected = !reader.redactArea)
                    Chip("Area", { reader.redactArea = true }, selected = reader.redactArea)
                    if (reader.redactions > 0) {
                        Box(Modifier.width(1.dp).height(28.dp).background(Ink.Rule))
                        Chip("Black out ${reader.redactions} now", { reader.openTool?.invoke("redact") })
                    }
                }
                Tool.EDIT -> Text("Changes go into the page itself; undo takes them back.", style = Type.small)
                Tool.PICTURE -> {
                    Chip(if (reader.picture == null) "Choose a picture" else "Another picture", { reader.pickPicture?.invoke() })
                    reader.picture?.let { path ->
                        val img = remember(path) { runCatching { android.graphics.BitmapFactory.decodeFile(path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap() }.getOrNull() }
                        if (img != null) androidx.compose.foundation.Image(img, "Chosen picture", modifier = Modifier.height(40.dp).border(1.dp, Ink.Edge))
                    }
                }
                Tool.STAMP -> {
                    (StandardStamps + reader.customStamps).forEach { k -> StampChip(k, reader.stamp == k) { reader.pickStamp(k) } }
                    Chip("New stamp", { reader.stampAsk = true })
                    Box(Modifier.width(1.dp).height(28.dp).background(Ink.Rule))
                    Chip("With date", { reader.pickStampWithDate(!reader.stampWithDate) }, selected = reader.stampWithDate)
                    if (reader.stampWithDate) DateStyleChips(reader)
                }
                else -> {
                    val colors = if (reader.usesPenColors(t)) PenColors else MarkColors
                    (colors + reader.customColors.filter { it !in colors }).forEach { c -> Swatch(c, reader.colorFor(t) == c) { reader.pickColor(c) } }
                    AddColor { reader.colorPick = ColorPick(reader.colorFor(t)) }
                    if (t == Tool.HIGHLIGHT) {
                        Box(Modifier.width(1.dp).height(28.dp).background(Ink.Rule))
                        StrengthLabels.forEachIndexed { i, l -> Chip(l, { reader.pickStrength(i) }, selected = reader.strength == i) }
                    }
                    if (t == Tool.DATE) {
                        Box(Modifier.width(1.dp).height(28.dp).background(Ink.Rule))
                        DateStyleChips(reader)
                    }
                    if (t == Tool.PEN || t == Tool.SHAPE || t == Tool.MARKER || t == Tool.TEXT || t == Tool.TICK || t == Tool.CROSS || t == Tool.DOT) {
                        Box(Modifier.width(1.dp).height(28.dp).background(Ink.Rule))
                        listOf(0, 1, 2).forEach { step ->
                            val sel = reader.widthStep == step
                            Box(
                                Modifier.size(36.dp).border(1.dp, if (sel) Ink.Bone else Ink.Rule, Corner).clickable { reader.pickWidth(step) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (t == Tool.TEXT) Text("A", style = Type.bodyStrong.copy(color = if (sel) Ink.Bone else Ink.Dust, fontSize = listOf(11, 15, 20)[step].sp))
                                else Box(Modifier.size(listOf(4, 7, 11)[step].dp).background(if (sel) Ink.Bone else Ink.Dust, RoundedCornerShape(50)))
                            }
                        }
                    }
                    if (t == Tool.SHAPE) {
                        Box(Modifier.width(1.dp).height(28.dp).background(Ink.Rule))
                        ShapeKind.entries.forEach { k -> Chip(k.label, { reader.pickShape(k) }, selected = reader.shape == k) }
                    }
                }
            }
        }
        val hint = when (t) {
            Tool.HIGHLIGHT, Tool.UNDERLINE, Tool.STRIKE -> "Drag across the words, or tap one word."
            Tool.PEN, Tool.MARKER -> if (reader.stylusSeen) "Draw with the pen. Fingers scroll and zoom." else "Draw with one finger. Two fingers scroll and zoom."
            Tool.SHAPE -> "Drag from one corner to the other."
            Tool.TEXT -> "Tap where the text should start."
            Tool.SELECT -> "Draw a loop round marks to pick them all, or tap one."
            Tool.SIGN -> when {
                reader.digital && reader.certs.isEmpty() -> "Add a certificate first: Add certificate."
                reader.digital -> "Tap where the digital signature goes. The file is saved as soon as it is signed."
                reader.signs.isEmpty() -> "Make a signature first: New signature."
                else -> "Tap where the signature goes. Then drag or resize it."
            }
            Tool.STAMP -> "Tap where the stamp goes."
            Tool.TICK -> "Tap to put a tick."
            Tool.CROSS -> "Tap to put a cross."
            Tool.DOT -> "Tap to put a dot."
            Tool.DATE -> "Tap where today's date goes."
            Tool.NOTE -> "Tap where the note should sit."
            Tool.EDIT -> "Tap a line to change its words: a name, a number, a date. They are written in the same font where it has the letters."
            Tool.PICTURE -> if (reader.picture == null) "Choose a picture, then tap where it goes." else "Tap where the picture goes. Then drag or resize it."
            Tool.REDACT -> if (reader.redactArea) "Drag a box over what must go: a photo, a signature, a stamp, anything." else "Drag across the words that must go, or tap one word. Nothing goes until you black them out."
            Tool.ERASER -> null
        }
        if (hint != null) Text(hint, style = Type.small, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
    }
}

@Composable
private fun Swatch(color: Int, selected: Boolean, size: Dp = 36.dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .border(2.dp, if (selected) Ink.Bone else Ink.Black, RoundedCornerShape(50))
            .padding(5.dp)
            .background(Color(color), RoundedCornerShape(50))
            .clickable(onClick = onClick),
    )
}

/** A mark someone tapped: change its colour or strength, add or edit its note or text, or delete it. */
@Composable
private fun MarkBar(m: Mark, reader: Reader) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(Ink.Field, Corner)
            .border(1.dp, Ink.Edge, Corner)
            .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
    ) {
        val all = reader.members.ifEmpty { listOf(m) }
        val many = all.size > 1
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val title = when {
                    !many -> m.kind.label
                    all.all { it.kind == MarkKind.INK } && m.kind == MarkKind.INK && reader.inkScope != InkScope.STROKE ->
                        "${reader.inkScope.label} · ${all.size} strokes"
                    else -> "${all.size} marks"
                }
                Text(title, style = Type.bodyStrong)
                val words = if (many) "" else m.note.ifBlank { m.quoted }
                if (words.isNotBlank()) Text(words, style = Type.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val how = when {
                    reader.membersResizable -> if (many) "Drag to move them together; drag a corner to resize." else "Drag it to move it; drag a corner to resize it."
                    reader.membersMovable -> "Drag it to move it."
                    else -> null
                }
                if (how != null) Text(how, style = Type.small.copy(color = Ink.Teal))
            }
            IconButton(Glyph.CLOSE, "Close", { reader.selectMark(null) }, color = Ink.Dust, size = 44.dp)
        }
        if (m.kind == MarkKind.INK) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Pick", style = Type.small, modifier = Modifier.padding(end = 4.dp))
                InkScope.entries.forEach { sc -> Chip(sc.label, { reader.pickInkScope(sc) }, selected = reader.inkScope == sc) }
            }
        }
        val widthKinds = setOf(MarkKind.INK, MarkKind.SQUARE, MarkKind.CIRCLE, MarkKind.LINE, MarkKind.ARROW)
        if (all.all { it.kind in widthKinds } && m.opacity >= 0.99f) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Width", style = Type.small, modifier = Modifier.padding(end = 4.dp))
                val near = PenWidths.indices.minByOrNull { kotlin.math.abs(PenWidths[it] - m.width) } ?: 1
                PenWidths.forEachIndexed { i, w ->
                    Box(
                        Modifier.size(36.dp).border(1.dp, if (i == near) Ink.Bone else Ink.Rule, Corner).clickable { reader.rewidth(w) }
                            .semantics { contentDescription = listOf("Thin", "Medium", "Thick")[i] },
                        contentAlignment = Alignment.Center,
                    ) { Box(Modifier.size(listOf(4, 7, 11)[i].dp).background(if (i == near) Ink.Bone else Ink.Dust, RoundedCornerShape(50))) }
                }
            }
        }
        if (m.kind != MarkKind.NOTE) {
            val colors = if (m.kind in setOf(MarkKind.INK, MarkKind.SQUARE, MarkKind.CIRCLE, MarkKind.LINE, MarkKind.ARROW, MarkKind.TEXT)) PenColors else MarkColors
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (colors + reader.customColors.filter { it !in colors }).forEach { c -> Swatch(c, (m.color and 0xFFFFFF) == (c and 0xFFFFFF)) { reader.recolor(m, c) } }
                AddColor { reader.colorPick = ColorPick(m.color, mark = m) }
            }
        }
        if (m.kind == MarkKind.HIGHLIGHT && !many) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Strength", style = Type.small, modifier = Modifier.padding(end = 4.dp))
                val step = strengthStep(m.opacity)
                StrengthLabels.forEachIndexed { i, l -> Chip(l, { reader.restrength(m, i) }, selected = step == i) }
            }
        }
        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!many) {
                if (m.kind == MarkKind.TEXT) BarAction("Edit text") { reader.editText(m) }
                else BarAction(if (m.note.isBlank()) "Add note" else "Edit note") { reader.editNote(m) }
            }
            BarAction(if (many) "Delete all ${all.size}" else "Delete") { reader.delete(m) }
        }
    }
}

/** A swatch with a plus: opens the colour picker. */
@Composable
private fun AddColor(onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(50)).border(1.dp, Ink.Edge, RoundedCornerShape(50)).clickable(onClick = onClick)
            .semantics { contentDescription = "More colours" },
        contentAlignment = Alignment.Center,
    ) { GlyphIcon(Glyph.PLUS, Ink.Sand, size = 18.dp) }
}

/**
 * The bar that floats over selected words: highlight (in the colour on the dot, which opens more
 * colours and strengths), underline, strike through, then copy, find and share.
 */
@Composable
internal fun SelectionFloat(reader: Reader, onCopy: () -> Unit, onFind: () -> Unit, onShare: () -> Unit, startOpen: Boolean = false) {
    var colours by remember { mutableStateOf(startOpen) }
    Column(
        Modifier
            .shadow(10.dp, Corner)
            .background(Ink.Field, Corner)
            .border(1.dp, Ink.Edge, Corner)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MarkWordButton("Highlight", { reader.markSelection(MarkKind.HIGHLIGHT) }) {
                Text("A", style = Type.bodyStrong.copy(color = Ink.Black, fontSize = 15.sp), modifier = Modifier.background(Color(reader.markColor).copy(alpha = 0.35f + 0.65f * HighlightStrengths[reader.strength]), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp))
            }
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(50)).clickable { colours = !colours }.semantics { contentDescription = "Highlight colour" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(16.dp).background(Color(reader.markColor), RoundedCornerShape(50)).border(1.5.dp, if (colours) Ink.Bone else Ink.Edge, RoundedCornerShape(50)))
            }
            MarkWordButton("Underline", { reader.markSelection(MarkKind.UNDERLINE) }) {
                Text("U", style = Type.bodyStrong.copy(color = Ink.Bone, fontSize = 16.sp, textDecoration = TextDecoration.Underline))
            }
            MarkWordButton("Strike through", { reader.markSelection(MarkKind.STRIKE) }) {
                Text("S", style = Type.bodyStrong.copy(color = Ink.Bone, fontSize = 16.sp, textDecoration = TextDecoration.LineThrough))
            }
            Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(Ink.Rule))
            FloatAction("Copy", onCopy)
            FloatAction("Find", onFind)
            FloatAction("Share", onShare)
        }
        if (colours) {
            Hairline(color = Ink.Rule)
            Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                (MarkColors + reader.customColors.filter { it !in MarkColors }.take(2)).forEach { c ->
                    Swatch(c, reader.markColor == c, size = 30.dp) { reader.pickWordsColor(c) }
                }
                AddColor { reader.colorPick = ColorPick(reader.markColor, forWords = true) }
            }
            Row(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StrengthLabels.forEachIndexed { i, l -> Chip(l, { reader.pickStrength(i) }, selected = reader.strength == i) }
            }
        }
    }
}

@Composable
private fun MarkWordButton(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(Corner).clickable(onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun FloatAction(label: String, onClick: () -> Unit) {
    Text(label, style = Type.bodyStrong.copy(color = Ink.Amber, fontSize = 14.sp), maxLines = 1, softWrap = false, modifier = Modifier.clip(Corner).clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 12.dp))
}

/** Every search result, page by page, each with the words around it. Tap one to go there. */
@Composable
internal fun SearchResults(reader: Reader, onClose: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Tapping beside the panel closes it.
        Box(Modifier.fillMaxSize().background(Ink.Black.copy(alpha = 0.35f)).pointerInput(Unit) { detectTapGestures { onClose() } })
        val width = minOf(maxWidth * 0.88f, 400.dp)
        Column(
            Modifier.align(Alignment.CenterEnd).width(width).fillMaxHeight().background(Ink.Field)
                .border(1.dp, Ink.Edge).pointerInput(Unit) { detectTapGestures { } },
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("RESULTS", style = Type.eyebrow)
                    val n = reader.order.size
                    val pages = reader.hits.size
                    Text(
                        (if (n == 1) "1 match" else "$n matches") + (if (pages > 0) " on " + (if (pages == 1) "1 page" else "$pages pages") else "") + if (reader.searching) " · still looking" else "",
                        style = Type.small,
                    )
                }
                IconButton(Glyph.CLOSE, "Hide results", onClose, color = Ink.Dust, size = 44.dp)
            }
            Hairline(color = Ink.Rule)
            val rows = remember(reader.order) {
                val out = ArrayList<Pair<Int, Int>>() // -1-page for a page heading, else index into order
                var last = -1
                reader.order.forEachIndexed { i, (p, _) ->
                    if (p != last) { out += -1 - p to -1; last = p }
                    out += p to i
                }
                out
            }
            val list = rememberLazyListState()
            LaunchedEffect(Unit) {
                val at = rows.indexOfFirst { it.second == reader.current }
                if (at > 0) list.scrollToItem((at - 1).coerceAtLeast(0))
            }
            val mark = Ink.Amber.copy(alpha = 0.35f)
            LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(rows.size, key = { rows[it].first * 100_000L + rows[it].second }) { r ->
                    val (p, i) = rows[r]
                    if (i < 0) {
                        Text("PAGE ${-p}", style = Type.eyebrow.copy(color = Ink.Sand), modifier = Modifier.padding(start = 18.dp, top = 16.dp, bottom = 4.dp))
                    } else {
                        val (page, h) = reader.order[i]
                        val hit = reader.snippets[page]?.getOrNull(h)
                        val now = i == reader.current
                        Row(
                            Modifier.fillMaxWidth().clickable { reader.showHit(i); onClose() }.background(if (now) Ink.Black else Color.Transparent),
                        ) {
                            Box(Modifier.width(3.dp).heightIn(min = 44.dp).background(if (now) Ink.Amber else Color.Transparent))
                            val text = if (hit == null) AnnotatedString(reader.query) else buildAnnotatedString {
                                append(hit.before)
                                withStyle(SpanStyle(background = mark, color = Ink.Bone, fontWeight = FontWeight.SemiBold)) { append(hit.match) }
                                append(hit.after)
                            }
                            Text(
                                text,
                                style = Type.note.copy(color = Ink.Sand, textDirection = TextDirection.Content, fontFamily = PlexArabic, lineHeight = 20.sp),
                                maxLines = 3, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 15.dp, end = 16.dp, top = 9.dp, bottom = 9.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Picking any colour: saturation and brightness in the square, hue on the strip below, recent colours under that. */
@Composable
internal fun ColorPickerDialog(initial: Int, recent: List<Int>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val start = remember(initial) { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember(initial) { mutableFloatStateOf(start[0]) }
    var sat by remember(initial) { mutableFloatStateOf(start[1]) }
    var value by remember(initial) { mutableFloatStateOf(start[2]) }
    val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Field,
        shape = RectangleShape,
        title = { Text("Colour", style = Type.title) },
        text = {
            ColorPickerBody(hue, sat, value, recent, onHsv = { h, s, v -> hue = h; sat = s; value = v }, onRecent = { c ->
                val f = FloatArray(3); android.graphics.Color.colorToHSV(c, f); hue = f[0]; sat = f[1]; value = f[2]
            })
        },
        confirmButton = { AmberButton("Use colour", { onPick(color) }) },
        dismissButton = { GhostButton("Cancel", onDismiss, minHeight = 52.dp) },
    )
}

@Composable
internal fun ColorPickerBody(hue: Float, sat: Float, value: Float, recent: List<Int>, onHsv: (Float, Float, Float) -> Unit, onRecent: (Int) -> Unit) {
    val pure = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))
    val now = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)))
    val h by rememberUpdatedState(hue)
    val sv by rememberUpdatedState(sat to value)
    Column {
        Canvas(
            Modifier.fillMaxWidth().height(170.dp).clip(Corner)
                .pointerInput(Unit) {
                    fun at(o: Offset) = onHsv(h, (o.x / size.width).coerceIn(0f, 1f), 1f - (o.y / size.height).coerceIn(0f, 1f))
                    awaitEachGesture {
                        val d = awaitFirstDown(); at(d.position); d.consume()
                        do { val e = awaitPointerEvent(); e.changes.forEach { at(it.position); it.consume() } } while (e.changes.any { it.pressed })
                    }
                },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, pure)))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val c = Offset(sv.first * size.width, (1f - sv.second) * size.height)
            drawCircle(Color.White, 9.dp.toPx(), c, style = Stroke(2.dp.toPx()))
            drawCircle(Color.Black, 10.5.dp.toPx(), c, style = Stroke(1.dp.toPx()))
        }
        Spacer(Modifier.height(12.dp))
        Canvas(
            Modifier.fillMaxWidth().height(26.dp).clip(Corner)
                .pointerInput(Unit) {
                    fun at(o: Offset) = onHsv((o.x / size.width).coerceIn(0f, 1f) * 360f, sv.first, sv.second)
                    awaitEachGesture {
                        val d = awaitFirstDown(); at(d.position); d.consume()
                        do { val e = awaitPointerEvent(); e.changes.forEach { at(it.position); it.consume() } } while (e.changes.any { it.pressed })
                    }
                },
        ) {
            drawRect(Brush.horizontalGradient((0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f))) }))
            val x = h / 360f * size.width
            drawRect(Color.White, Offset(x - 2.dp.toPx(), 0f), Size(4.dp.toPx(), size.height))
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(40.dp).background(now, Corner).border(1.dp, Ink.Edge, Corner))
            Text("#%06X".format(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value)) and 0xFFFFFF), style = Type.figure, modifier = Modifier.padding(end = 8.dp))
            recent.take(5).forEach { c -> Swatch(c, false, size = 32.dp) { onRecent(c) } }
        }
    }
}

/** Typing the words for a text box or a note. */
@Composable
private fun WordsAsk(ask: TextAsk, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember(ask) { mutableStateOf(ask.mark?.note ?: "") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(ask) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Field,
        shape = RectangleShape,
        title = { Text(if (ask.mark != null) "Note" else if (ask.note) "New note" else "Text on the page", style = Type.title) },
        text = {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = Type.body.copy(color = Ink.Bone, fontSize = 16.sp),
                cursorBrush = SolidColor(Ink.Amber),
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).focusRequester(focus),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).background(Ink.Black, Corner).border(1.dp, Ink.Edge, Corner).padding(12.dp)) {
                        if (text.isEmpty()) Text(if (ask.note || ask.mark != null) "Write the note" else "Write the text", style = Type.body.copy(color = Ink.Ash, fontSize = 16.sp))
                        inner()
                    }
                },
            )
        },
        confirmButton = { AmberButton(if (ask.mark != null) "Save note" else "Add", { onDone(text) }, enabled = text.isNotBlank() || ask.mark != null) },
        dismissButton = { GhostButton("Cancel", onDismiss, minHeight = 52.dp) },
    )
}

/** Soft backgrounds for text boxes, chosen to keep dark text readable. */
val BoxFills = listOf(0xFFFFFFFF, 0xFFFFF4C2, 0xFFDDEBFF, 0xFFFFE0EA, 0xFFDDF5DD).map { it.toInt() }

private fun nextSize(s: Float, up: Boolean): Float {
    val step = when { s < 12f -> 1f; s < 24f -> 2f; else -> 4f }
    return (if (up) s + step else s - (if (s <= 12f) 1f else if (s <= 24f) 2f else 4f)).coerceIn(6f, 72f)
}

/** Writing a new text box or changing one: its words and every part of its style, with a preview. */
@Composable
internal fun BoxEditor(startText: String, startStyle: BoxStyle, isNew: Boolean, recent: List<Int>, onDismiss: () -> Unit, onDone: (String, BoxStyle) -> Unit) {
    var text by remember { mutableStateOf(startText) }
    var style by remember { mutableStateOf(startStyle) }
    var picking by remember { mutableStateOf<Int?>(null) } // 0 for the words, 1 for the background
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(Ink.Field, Corner).border(1.dp, Ink.Edge, Corner).imePadding(),
        ) {
            Text(if (isNew) "New text" else "Edit text", style = Type.title, modifier = Modifier.padding(start = 18.dp, top = 16.dp, bottom = 8.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
                BoxStyleFields(text, style, recent, { text = it }, { style = it }, { picking = it })
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                GhostButton("Cancel", onDismiss, minHeight = 48.dp)
                Spacer(Modifier.width(10.dp))
                AmberButton(if (!isNew && text.isBlank()) "Remove box" else if (isNew) "Add" else "Save", { onDone(text, style) }, enabled = text.isNotBlank() || !isNew)
            }
        }
    }
    picking?.let { which ->
        val initial = if (which == 0) style.color else style.fill ?: BoxFills[1]
        ColorPickerDialog(initial, recent, onDismiss = { picking = null }) { c ->
            style = if (which == 0) style.copy(color = c) else style.copy(fill = c)
            picking = null
        }
    }
}

/** The preview, the words, and the style controls of a text box. [onPick] opens the picker: 0 words, 1 background. */
@Composable
internal fun BoxStyleFields(text: String, style: BoxStyle, recent: List<Int>, onText: (String) -> Unit, onStyle: (BoxStyle) -> Unit, onPick: (Int) -> Unit) {
    val family = when (style.font) {
        BoxFont.SANS -> androidx.compose.ui.text.font.FontFamily.SansSerif
        BoxFont.SERIF -> androidx.compose.ui.text.font.FontFamily.Serif
        BoxFont.MONO -> androidx.compose.ui.text.font.FontFamily.Monospace
    }
    val shown = TextStyle(
        fontFamily = family,
        fontSize = (style.size * 1.15f).coerceIn(9f, 40f).sp,
        lineHeight = (style.size * 1.15f * BoxSpacings[style.spacing]).coerceIn(9f, 70f).sp,
        fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (style.italic) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
        textDecoration = if (style.underline) TextDecoration.Underline else null,
        color = Color(style.color),
        textAlign = when (style.align) { BoxAlign.AUTO -> TextAlign.Start; BoxAlign.LEFT -> TextAlign.Left; BoxAlign.CENTER -> TextAlign.Center; BoxAlign.RIGHT -> TextAlign.Right },
        textDirection = when (style.dir) { BoxDir.AUTO -> TextDirection.Content; BoxDir.RTL -> TextDirection.Rtl; BoxDir.LTR -> TextDirection.Ltr },
    )
    Text("PREVIEW", style = Type.eyebrow)
    Spacer(Modifier.height(6.dp))
    Box(Modifier.fillMaxWidth().background(Ink.Paper, Corner).padding(14.dp)) {
        Box(
            Modifier.fillMaxWidth()
                .background(style.fill?.let { Color(it) } ?: Color.Transparent)
                .then(if (style.border) Modifier.border(1.dp, Color(style.color)) else Modifier)
                .padding(8.dp),
        ) {
            Text(text.ifBlank { "Your words, as they will sit on the page" }, style = shown, modifier = Modifier.fillMaxWidth())
        }
    }
    Spacer(Modifier.height(12.dp))
    BasicTextField(
        value = text,
        onValueChange = onText,
        textStyle = Type.body.copy(color = Ink.Bone, fontSize = 16.sp, textDirection = TextDirection.Content),
        cursorBrush = SolidColor(Ink.Amber),
        modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().heightIn(min = 84.dp).background(Ink.Black, Corner).border(1.dp, Ink.Edge, Corner).padding(12.dp)) {
                if (text.isEmpty()) Text("Write the text", style = Type.body.copy(color = Ink.Ash, fontSize = 15.sp))
                inner()
            }
        },
    )
    StyleRow("FONT") { BoxFont.entries.forEach { f -> Chip(f.label, { onStyle(style.copy(font = f)) }, selected = style.font == f) } }
    StyleRow("SIZE") {
        GhostButton("−", { onStyle(style.copy(size = nextSize(style.size, false))) }, minHeight = 44.dp)
        Text("${if (style.size % 1f == 0f) style.size.toInt() else style.size} pt", style = Type.figure, modifier = Modifier.padding(horizontal = 8.dp))
        GhostButton("+", { onStyle(style.copy(size = nextSize(style.size, true))) }, minHeight = 44.dp)
    }
    StyleRow("STYLE") {
        StyleToggle("Bold", style.bold, { onStyle(style.copy(bold = !style.bold)) }) { Text("B", style = Type.bodyStrong.copy(fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = it)) }
        StyleToggle("Italic", style.italic, { onStyle(style.copy(italic = !style.italic)) }) { Text("I", style = Type.body.copy(fontSize = 18.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, color = it)) }
        StyleToggle("Underline", style.underline, { onStyle(style.copy(underline = !style.underline)) }) { Text("U", style = Type.body.copy(fontSize = 17.sp, textDecoration = TextDecoration.Underline, color = it)) }
    }
    StyleRow("ALIGN") { BoxAlign.entries.forEach { a -> Chip(a.label, { onStyle(style.copy(align = a)) }, selected = style.align == a) } }
    StyleRow("DIRECTION") { BoxDir.entries.forEach { d -> Chip(d.label, { onStyle(style.copy(dir = d)) }, selected = style.dir == d) } }
    StyleRow("COLOUR") {
        (PenColors + recent.filter { it !in PenColors }.take(3)).forEach { c -> Swatch(c, (style.color and 0xFFFFFF) == (c and 0xFFFFFF), size = 34.dp) { onStyle(style.copy(color = c)) } }
        AddColor { onPick(0) }
    }
    StyleRow("BACKGROUND") {
        Chip("None", { onStyle(style.copy(fill = null)) }, selected = style.fill == null)
        BoxFills.forEach { c -> Swatch(c, style.fill != null && (style.fill and 0xFFFFFF) == (c and 0xFFFFFF), size = 34.dp) { onStyle(style.copy(fill = c)) } }
        AddColor { onPick(1) }
    }
    StyleRow("BORDER") {
        Chip("None", { onStyle(style.copy(border = false)) }, selected = !style.border)
        Chip("Thin line", { onStyle(style.copy(border = true)) }, selected = style.border)
    }
    StyleRow("LINE SPACING") { listOf("Tight", "Normal", "Loose").forEachIndexed { i, l -> Chip(l, { onStyle(style.copy(spacing = i)) }, selected = style.spacing == i) } }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun StyleRow(label: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(14.dp))
    Text(label, style = Type.eyebrow)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun StyleToggle(description: String, on: Boolean, onClick: () -> Unit, content: @Composable (Color) -> Unit) {
    Box(
        Modifier.size(44.dp).background(if (on) Ink.Bone else Color.Transparent, Corner).border(1.dp, if (on) Ink.Bone else Ink.Rule, Corner)
            .clickable(onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content(if (on) Ink.Black else Ink.Sand) }
}

/** The three date styles, written out for today so the choice is plain. */
@Composable
private fun DateStyleChips(reader: Reader) {
    com.fadysaeed.pdfx.engine.DatePatterns.forEachIndexed { i, p -> Chip(com.fadysaeed.pdfx.engine.formatToday(p), { reader.pickDateStyle(i) }, selected = reader.dateStyle == i) }
}

/** A saved signature drawn small, to pick; a long press offers to delete it. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SignThumb(sg: com.fadysaeed.pdfx.data.SavedSign, selected: Boolean, onPick: () -> Unit, onLongPress: () -> Unit) {
    val w = if (sg.initials) 56.dp else 104.dp
    Box(
        Modifier.width(w).height(44.dp).background(Ink.Paper, Corner).border(2.dp, if (selected) Ink.Amber else Ink.Edge, Corner)
            .combinedClickable(onClick = onPick, onLongClick = onLongPress).padding(5.dp)
            .semantics { contentDescription = if (sg.initials) "Initials" else "Signature" },
        contentAlignment = Alignment.Center,
    ) {
        val img = remember(sg.image) { sg.image?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() } }
        if (img != null) {
            androidx.compose.foundation.Image(img, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val k = minOf(size.width, size.height * sg.aspect)
                val ox = (size.width - k) / 2
                val oy = (size.height - k / sg.aspect) / 2
                sg.strokes.forEach { st ->
                    if (st.size < 2) return@forEach
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(ox + st[0].first * k, oy + st[0].second * k)
                        st.drop(1).forEach { lineTo(ox + it.first * k, oy + it.second * k) }
                    }
                    drawPath(path, Color(sg.color), style = Stroke(width = 1.4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
            }
        }
    }
}

/** A stamp shown as it will look: bold capitals in a coloured frame. */
@Composable
private fun StampChip(k: StampKind, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(40.dp).background(if (selected) Ink.Paper else Ink.Black, Corner).border(if (selected) 2.dp else 1.dp, if (selected) Ink.Amber else Ink.Edge, Corner)
            .clickable(onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            k.text, style = Type.bodyStrong.copy(color = if (selected) Color(k.color) else Color(k.color).copy(alpha = 0.9f), fontSize = 12.sp, letterSpacing = 0.06.em),
            modifier = Modifier.border(1.5.dp, Color(k.color)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Making a stamp of your own: its words and colour. */
@Composable
private fun StampAsk(onDismiss: () -> Unit, onDone: (StampKind) -> Unit) {
    var text by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(0xFFE5484D.toInt()) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Ink.Field, shape = RectangleShape,
        title = { Text("New stamp", style = Type.title) },
        text = {
            Column {
                InkField(text, { text = it.take(28) }, "Words on the stamp, e.g. SEEN BY DR SAEED")
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0xFFE5484D, 0xFF2E9E5B, 0xFF2F6FED, 0xFF6B6B6B, 0xFF1A1A1A).map { it.toInt() }.forEach { c -> Swatch(c, color == c, size = 34.dp) { color = c } }
                }
                if (text.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    StampChip(StampKind(text.trim().uppercase(), color), true) {}
                }
            }
        },
        confirmButton = { AmberButton("Save stamp", { onDone(StampKind(text.trim().uppercase(), color)) }, enabled = text.isNotBlank()) },
        dismissButton = { GhostButton("Cancel", onDismiss, minHeight = 52.dp) },
    )
}

/** Opens the signature pad and keeps what it makes; photos of a signature are cleaned up here too. */
@Composable
private fun SignPadHost(reader: Reader, initials: Boolean) {
    val context = LocalContext.current
    val app = context.applicationContext as PdfxApp
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun keep(sg: com.fadysaeed.pdfx.data.SavedSign?) {
        busy = false
        if (sg == null) { reader.problem = "No signature found in that picture. Try a darker pen on white paper."; return }
        reader.signs.add(sg)
        reader.pickSign(sg.id)
        reader.signPad = null
        reader.signField?.let { f -> reader.signField = null; reader.placeSign(f.page, 0f, 0f, into = f.box) }
    }
    fun fromPhoto(uri: Uri?) {
        if (uri == null) return
        busy = true
        scope.launch { keep(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.signatures.addPhoto(context, uri, initials) }) }
    }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { fromPhoto(it) }
    val photoUri = remember {
        val f = java.io.File(context.cacheDir, "signature-photo.jpg")
        androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
    }
    val shoot = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok -> if (ok) fromPhoto(photoUri) }
    SignPad(
        initials = initials,
        busy = busy,
        onDismiss = { reader.signPad = null; reader.signField = null },
        onSave = { strokes, color -> scope.launch { keep(kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.signatures.addDrawn(strokes, initials, color) }) } },
        onChoose = { pick.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onShoot = { runCatching { shoot.launch(photoUri) } },
    )
}

/** A wide white pad to sign on with the pen or a finger, like signing on paper. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun SignPad(initials: Boolean, busy: Boolean = false, onDismiss: () -> Unit, onSave: (List<List<Pair<Float, Float>>>, Int) -> Unit, onChoose: () -> Unit = {}, onShoot: () -> Unit = {}, startStrokes: List<List<Pair<Float, Float>>> = emptyList()) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        SignPadBody(initials, busy, onDismiss, onSave, onChoose, onShoot, startStrokes)
    }
}

/** What the signature pad shows; kept apart from the dialog so it can be pictured in tests. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun SignPadBody(initials: Boolean, busy: Boolean, onDismiss: () -> Unit, onSave: (List<List<Pair<Float, Float>>>, Int) -> Unit, onChoose: () -> Unit, onShoot: () -> Unit, startStrokes: List<List<Pair<Float, Float>>>) {
    val strokes = remember { mutableStateListOf<List<Pair<Float, Float>>>().apply { addAll(startStrokes) } }
    var live by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var color by remember { mutableStateOf(0xFF1A1A1A.toInt()) }
    run {
        Column(Modifier.fillMaxWidth().padding(10.dp).background(Ink.Field, Corner).border(1.dp, Ink.Edge, Corner).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (initials) "Your initials" else "Your signature", style = Type.title, modifier = Modifier.weight(1f))
                IconButton(Glyph.CLOSE, "Close", onDismiss, color = Ink.Dust, size = 44.dp)
            }
            Text("Sign with the pen or a finger. It stays on this phone.", style = Type.small)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(if (initials) 2.2f else 2.6f).background(Color.White, Corner)) {
                Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val d = awaitFirstDown()
                                val pts = arrayListOf(d.position.x to d.position.y)
                                live = pts.toList()
                                d.consume()
                                do {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.firstOrNull { it.id == d.id } ?: break
                                    c.historical.forEach { pts += it.position.x to it.position.y }
                                    pts += c.position.x to c.position.y
                                    live = pts.toList()
                                    c.consume()
                                } while (c.pressed)
                                if (pts.size == 1) pts += (pts[0].first + 0.5f) to (pts[0].second + 0.5f)
                                strokes += pts.toList()
                                live = emptyList()
                            }
                        },
                ) {
                    val base = size.height * 0.72f
                    drawLine(Color(0xFFBDB6A8), Offset(size.width * 0.06f, base), Offset(size.width * 0.94f, base), strokeWidth = 1.dp.toPx())
                    (strokes + listOf(live)).forEach { st ->
                        if (st.size < 2) return@forEach
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(st[0].first, st[0].second)
                            st.drop(1).forEach { lineTo(it.first, it.second) }
                        }
                        drawPath(path, Color(color), style = Stroke(width = 2.6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    }
                }
                if (strokes.isEmpty() && live.isEmpty()) Text("Sign here", style = Type.note.copy(color = Color(0xFFBDB6A8)), modifier = Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0xFF1A1A1A, 0xFF1F3F9F).map { it.toInt() }.forEach { c -> Swatch(c, color == c, size = 36.dp) { color = c } }
                Spacer(Modifier.weight(1f))
                BarAction("Undo") { if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1) }
                BarAction("Clear") { strokes.clear() }
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                BarAction("Take a photo", onShoot)
                BarAction("Choose a photo", onChoose)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GhostButton("Cancel", onDismiss, minHeight = 48.dp)
                Spacer(Modifier.width(10.dp))
                AmberButton(if (busy) "Working…" else "Save", { onSave(strokes.toList(), color) }, enabled = strokes.isNotEmpty() && !busy)
            }
        }
    }
}

/** Filling one form field: its name, a way to fill it, and Previous, Next and Done. */
@Composable
internal fun FieldPanel(f: com.fadysaeed.pdfx.engine.Field, reader: Reader) {
    var text by remember(f.page, f.index) { mutableStateOf(f.value) }
    val focus = remember { FocusRequester() }
    fun commit() { if (f.kind == com.fadysaeed.pdfx.engine.FieldKind.TEXT) reader.fillField(f, text) }
    Column(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().background(Ink.Field, Corner).border(1.dp, Ink.Edge, Corner).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.label, style = Type.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Page ${f.page + 1}", style = Type.small)
            }
            IconButton(Glyph.CLOSE, "Close", { commit(); reader.closeField() }, color = Ink.Dust, size = 44.dp)
        }
        when (f.kind) {
            com.fadysaeed.pdfx.engine.FieldKind.TEXT -> {
                LaunchedEffect(f.page, f.index) { runCatching { focus.requestFocus() } }
                BasicTextField(
                    value = text,
                    onValueChange = { text = if (f.maxLen > 0) it.take(f.maxLen) else it },
                    singleLine = !f.multiline,
                    textStyle = Type.body.copy(color = Ink.Bone, fontSize = 16.sp, textDirection = TextDirection.Content),
                    cursorBrush = SolidColor(Ink.Amber),
                    keyboardOptions = KeyboardOptions(imeAction = if (f.multiline) ImeAction.Default else ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { commit(); reader.moveField(f, true) }),
                    modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 6.dp).focusRequester(focus),
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth().heightIn(min = if (f.multiline) 88.dp else 46.dp).background(Ink.Black, Corner).border(1.dp, Ink.Edge, Corner).padding(horizontal = 12.dp, vertical = 10.dp)) {
                            if (text.isEmpty()) Text(if (f.isDate) reader.todayFor(f) else "Type here", style = Type.body.copy(color = Ink.Ash, fontSize = 16.sp))
                            inner()
                        }
                    },
                )
                if (f.isDate) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Today", { text = reader.todayFor(f) })
                    if (f.datePattern == null) DateStyleChips(reader)
                }
            }
            com.fadysaeed.pdfx.engine.FieldKind.CHECKBOX -> Row(Modifier.padding(top = 6.dp)) {
                Chip(if (f.on) "Ticked" else "Not ticked", { reader.toggleField(f) }, selected = f.on)
            }
            com.fadysaeed.pdfx.engine.FieldKind.RADIO -> Row(Modifier.padding(top = 6.dp)) {
                Chip(if (f.on) "Chosen" else "Choose this", { if (!f.on) reader.toggleField(f) }, selected = f.on)
            }
            com.fadysaeed.pdfx.engine.FieldKind.CHOICE -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                f.options.forEach { o -> Chip(o, { reader.chooseField(f, o) }, selected = o == f.value) }
            }
            com.fadysaeed.pdfx.engine.FieldKind.SIGNATURE -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                reader.signs.forEach { sg -> SignThumb(sg, false, { reader.pickSign(sg.id); reader.placeSign(f.page, 0f, 0f, into = f.box); reader.closeField() }, {}) }
                Chip("New signature", { reader.signField = f; reader.signPad = false; reader.closeField() })
                Box(Modifier.width(1.dp).height(28.dp).background(Ink.Rule))
                Chip("Sign digitally", { reader.closeField(); reader.askDigital(f.page, 0f, 0f, into = f.box, field = f.index) })
            }
            else -> Unit
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            BarAction("Previous") { commit(); reader.moveField(f, false) }
            Spacer(Modifier.weight(1f))
            BarAction("Next") { commit(); reader.moveField(f, true) }
            BarAction("Done") { commit(); reader.closeField() }
        }
    }
}

/** Kinds of marks to filter the list by. */
private enum class MarkFilter(val label: String, val kinds: Set<MarkKind>?) {
    ALL("All", null),
    WORDS("Highlights", setOf(MarkKind.HIGHLIGHT, MarkKind.UNDERLINE, MarkKind.STRIKE, MarkKind.SQUIGGLY)),
    NOTES("Notes", setOf(MarkKind.NOTE)),
    TEXT("Text boxes", setOf(MarkKind.TEXT)),
    DRAWINGS("Drawings", setOf(MarkKind.INK, MarkKind.SQUARE, MarkKind.CIRCLE, MarkKind.LINE, MarkKind.ARROW)),
}

/** Every mark in the file, in page order, with the words it covers or holds; filter by kind or colour, copy or share them. */
@Composable
private fun MarksList(reader: Reader, marks: List<Mark>?, name: String, onPage: (Int, Float) -> Unit) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf(MarkFilter.ALL) }
    var colour by remember { mutableStateOf<Int?>(null) }
    when {
        marks == null -> Text("Reading the marks…", style = Type.note, modifier = Modifier.padding(20.dp))
        marks.isEmpty() -> Text("No marks yet. Select words to highlight them, or tap the pencil at the top to draw, add text or notes.", style = Type.note, modifier = Modifier.padding(20.dp))
        else -> {
            val kinds = filter.kinds
            val only = colour
            val shown = marks.filter { m -> (kinds == null || m.kind in kinds) && (only == null || (m.color and 0xFFFFFF) == (only and 0xFFFFFF)) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MarkFilter.entries.filter { f -> f.kinds == null || marks.any { it.kind in f.kinds } }.forEach { f -> Chip(f.label, { filter = f }, selected = filter == f) }
            }
            val colours = marks.map { it.color and 0xFFFFFF or (0xFF shl 24) }.distinct()
            if (colours.size > 1) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Colour", style = Type.small, modifier = Modifier.padding(end = 4.dp))
                    colours.forEach { c -> Swatch(c, colour == c, size = 32.dp) { colour = if (colour == c) null else c } }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (shown.size == 1) "1 mark" else "${shown.size} marks", style = Type.small, modifier = Modifier.weight(1f))
                BarAction("Copy all") {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("PDFx marks", reader.marksText(name, shown)))
                    android.widget.Toast.makeText(context, "Marks copied", android.widget.Toast.LENGTH_SHORT).show()
                }
                BarAction("Share") {
                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "$name — marks").putExtra(Intent.EXTRA_TEXT, reader.marksText(name, shown)), null)) }
                }
            }
            Hairline(color = Ink.Rule)
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(shown) { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPage(m.page, m.box.y0) }.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(Modifier.padding(top = 4.dp).size(12.dp).background(Color(m.color)))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.kind.label, style = Type.bodyStrong.copy(fontSize = 14.sp))
                            val words = listOf(m.quoted, m.note).filter { it.isNotBlank() }.joinToString("  ·  ")
                            if (words.isNotBlank()) Text(words, style = Type.small.copy(color = MarkLabelColor, textDirection = TextDirection.Content), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        Text("p. ${m.page + 1}", style = Type.figure, modifier = Modifier.padding(start = 10.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun LockedPanel(name: String, wrong: Boolean, onCancel: () -> Unit, onUnlock: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Black)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        GlyphIcon(Glyph.LOCK, Ink.Amber, size = 32.dp)
        Spacer(Modifier.height(18.dp))
        Text("LOCKED", style = Type.eyebrow)
        Spacer(Modifier.height(6.dp))
        Text(name, style = Type.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Text(
            if (wrong) "That password didn't open it. Check capitals and try again." else "This PDF needs its password to open.",
            style = Type.note.copy(color = if (wrong) Ink.Amber else Ink.Dust),
        )
        Spacer(Modifier.height(20.dp))
        InkField(pw, { pw = it }, "Password", secret = true, onDone = { if (pw.isNotEmpty()) onUnlock(pw) })
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GhostButton("Cancel", onCancel, Modifier.weight(1f), minHeight = 52.dp)
            AmberButton("Unlock", { onUnlock(pw) }, Modifier.weight(1f), enabled = pw.isNotEmpty())
        }
        Spacer(Modifier.width(1.dp))
    }
}

/** The words picked to change, ready to edit, with how they will be written. */
@Composable
internal fun EditWordsDialog(run: com.fadysaeed.pdfx.engine.TextRun, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(run) { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(run.text, androidx.compose.ui.text.TextRange(run.text.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ink.Field,
        shape = RectangleShape,
        title = { Text("Change the words", style = Type.title) },
        text = {
            Column {
                BasicTextField(
                    value = text, onValueChange = { text = it },
                    textStyle = Type.body.copy(color = Ink.Bone),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Ink.Amber),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).border(1.dp, Ink.Edge).padding(12.dp)
                        .semantics { contentDescription = "New words" },
                )
                Spacer(Modifier.height(10.dp))
                Text("Written as before: ${run.look}." + if (run.onPicture) " On a scanned page the old words are painted over in the paper's colour." else "", style = Type.small)
                Text("For a line or a few words. A longer change does not move the lines after it.", style = Type.small, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = { AmberButton("Change", { onSave(text.text) }) },
        dismissButton = { GhostButton("Cancel", onDismiss, minHeight = 52.dp) },
    )
}
