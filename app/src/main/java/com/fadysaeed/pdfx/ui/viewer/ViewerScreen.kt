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
fun ViewerScreen(request: OpenRequest, onClose: (message: String?) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as PdfxApp
    val library = app.library
    var doc by remember(request) { mutableStateOf<Doc?>(null) }
    var load by remember(request) { mutableStateOf<Load>(Load.Opening) }
    var password by remember(request) { mutableStateOf<String?>(null) }
    var saving by remember(request) { mutableStateOf(false) }
    var saveError by remember(request) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val fallbackName = remember(request) { library.describe(request.uri).first.removeSuffix(".pdf") }
    val name = doc?.let { LibraryModel.display(it) } ?: fallbackName

    LaunchedEffect(request) {
        doc = runCatching { library.resolve(request.uri) }.getOrNull()
        load = try {
            val s = PdfSession.open(context, request.uri)
            if (s.needsPassword) Load.Locked(s, wrong = false) else Load.Ready(s)
        } catch (t: Throwable) {
            Load.Failed(t.message?.takeIf { it.isNotBlank() } ?: "The file is not a readable PDF.")
        }
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
            val reader = remember(session) { Reader(session, session.pageCount, scope, library.bookmarks, doc?.id, prefs) }
            val pages = reader.pages!!
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
            // Going to the background: save, then carry on from the saved file when back.
            LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
                if (session.dirty) save { saved ->
                    doc = saved.doc
                    scope.launch {
                        val uri = if (saved is Saver.Saved.AsCopy) Uri.fromFile(java.io.File(saved.doc.path!!)) else request.uri
                        val fresh = runCatching { PdfSession.open(context, uri) }.getOrNull() ?: return@launch
                        if (fresh.needsPassword) password?.let { fresh.unlock(it) }
                        if (fresh.needsPassword) { fresh.close(); return@launch }
                        val old = session
                        load = Load.Ready(fresh)
                        old.close()
                    }
                }
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

private enum class ViewerSheet { CONTENTS, DISPLAY }

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

    LaunchedEffect(flash) { if (flash != null) { delay(1600); flash = null } }
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
                        Column(Modifier.weight(1f)) {
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
                        Column(Modifier.weight(1f).padding(end = 4.dp)) {
                            Text(name, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (pageCount == 1) "1 page" else "$pageCount pages", style = Type.small)
                        }
                        IconButton(Glyph.SEARCH, "Find in document", { reader.searchOpen = true }, color = Ink.Sand, size = 42.dp)
                        IconButton(Glyph.REFLOW, if (reader.reflow) "Show the pages" else "Reflow text for reading", ::toggleReflow, color = if (reader.reflow) Ink.Amber else Ink.Sand, size = 42.dp)
                        if (reader.canMark && !reader.reflow) IconButton(Glyph.PEN, "Mark up", reader::startMarking, color = Ink.Sand, size = 42.dp)
                        val marked = current in reader.bookmarks
                        IconButton(
                            if (marked) Glyph.BOOKMARK_FILLED else Glyph.BOOKMARK,
                            if (marked) "Remove bookmark" else "Bookmark this page",
                            { reader.toggleBookmark(current); flash = if (marked) "Bookmark removed" else "Page ${current + 1} bookmarked" },
                            color = if (marked) Ink.Amber else Ink.Sand, size = 42.dp,
                        )
                        IconButton(Glyph.LIST, "Contents and bookmarks", { reader.loadOutline(); sheet = ViewerSheet.CONTENTS }, color = Ink.Sand, size = 42.dp)
                        IconButton(Glyph.MOON, "Page colours and reading mode", { sheet = ViewerSheet.DISPLAY }, color = Ink.Sand, size = 42.dp)
                    }
                }
                Hairline(color = Ink.Rule)
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
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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
            if (picked != null) {
                MarkBar(picked, reader)
            } else if (reader.marking) {
                MarkupBar(reader)
            } else if (sel != null) {
                Unit
            } else {
                AnimatedVisibility(showBars, enter = fadeIn(), exit = fadeOut()) {
                    Box(
                        Modifier
                            .background(Ink.Black, Corner)
                            .border(1.dp, Ink.Edge, Corner)
                            .clickable { askPage = true }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text("${current + 1} / $pageCount", style = Type.figure)
                    }
                }
            }
        }
    }

    reader.textAsk?.let { ask -> WordsAsk(ask, onDismiss = { reader.textAsk = null }, onDone = reader::submitText) }
    reader.colorPick?.let { pick -> ColorPickerDialog(pick.initial, reader.customColors, onDismiss = { reader.colorPick = null }) { c -> reader.applyPicked(pick, c) } }
    reader.zoomFigure?.let { (pg, bx) -> FigureViewer(reader, pg, bx) { reader.zoomFigure = null } }
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
                when (open) {
                    ViewerSheet.CONTENTS -> ContentsSheetOnTab(reader, null, current, name) { p, y -> sheet = null; reader.go(p, y) }
                    ViewerSheet.DISPLAY -> DisplaySheet(reader, onFlatten = { sheet = null; askFlatten = true }, onReflow = ::toggleReflow)
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
                                        return@forEach
                                    }
                                    val rows = p.table
                                    if (rows != null) {
                                        ReflowTable(rows, p.tableHeader, size, ink, latin, query, mark)
                                        val tb = p.tableBox
                                        if (tb != null) DisableSelection {
                                            Text(
                                                "See this table as on the page", style = Type.small.copy(color = ink.copy(alpha = 0.55f), textDecoration = TextDecoration.Underline),
                                                modifier = Modifier.clickable { reader.zoomFigure = i to tb }.padding(vertical = 4.dp),
                                            )
                                        }
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

/**
 * A table from the page as a real table: the same columns left to right as on the page (whatever
 * the language), cells that wrap, a bold header row, thin rules, and sideways scrolling when it is
 * wider than the screen.
 */
@Composable
private fun ReflowTable(rows: List<List<String>>, header: Boolean, size: Int, ink: Color, latin: androidx.compose.ui.text.font.FontFamily, query: String?, mark: Color) {
    val cols = rows.maxOf { it.size }
    val cell = size * 0.82f
    val widths = (0 until cols).map { c ->
        val longest = rows.maxOf { r -> r.getOrNull(c)?.length ?: 0 }
        val chars = longest.coerceIn(5, 24)
        (chars * cell * 0.56f + 18f).dp
    }
    val rule = ink.copy(alpha = 0.22f)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr) {
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column(Modifier.border(1.dp, rule)) {
                rows.forEachIndexed { ri, r ->
                    val head = header && ri == 0
                    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min).background(if (head) ink.copy(alpha = 0.07f) else Color.Transparent)) {
                        (0 until cols).forEach { c ->
                            val t = r.getOrNull(c) ?: ""
                            Box(Modifier.width(widths[c]).fillMaxHeight().border(0.5.dp, rule).padding(horizontal = 8.dp, vertical = 6.dp)) {
                                Text(
                                    styled(com.fadysaeed.pdfx.engine.Para(t), query, mark),
                                    style = TextStyle(
                                        fontFamily = if (isArabic(t)) PlexArabic else latin,
                                        fontWeight = if (head) FontWeight.SemiBold else FontWeight.Normal,
                                        fontSize = cell.sp, lineHeight = (cell * 1.35f).sp, color = ink,
                                        textDirection = TextDirection.Content,
                                    ),
                                )
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
                else -> {
                    val colors = if (reader.usesPenColors(t)) PenColors else MarkColors
                    (colors + reader.customColors.filter { it !in colors }).forEach { c -> Swatch(c, reader.colorFor(t) == c) { reader.pickColor(c) } }
                    AddColor { reader.colorPick = ColorPick(reader.colorFor(t)) }
                    if (t == Tool.HIGHLIGHT) {
                        Box(Modifier.width(1.dp).height(28.dp).background(Ink.Rule))
                        StrengthLabels.forEachIndexed { i, l -> Chip(l, { reader.pickStrength(i) }, selected = reader.strength == i) }
                    }
                    if (t == Tool.PEN || t == Tool.SHAPE || t == Tool.MARKER || t == Tool.TEXT) {
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
            Tool.NOTE -> "Tap where the note should sit."
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
