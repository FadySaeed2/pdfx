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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fadysaeed.pdfx.PdfxApp
import com.fadysaeed.pdfx.data.Doc
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
data class OpenRequest(val uri: Uri, val page: Int? = null, val query: String? = null, val id: Long = System.nanoTime())

/** Opens a document, asks for a password when needed, and shows it. */
@OptIn(FlowPreview::class)
@Composable
fun ViewerScreen(request: OpenRequest, onClose: (failure: String?) -> Unit) {
    val context = LocalContext.current
    val library = (context.applicationContext as PdfxApp).library
    var doc by remember(request) { mutableStateOf<Doc?>(null) }
    var load by remember(request) { mutableStateOf<Load>(Load.Opening) }
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
        is Load.Failed -> LaunchedEffect(l) { onClose("Couldn't open $name. ${l.reason}") }
        is Load.Locked -> {
            BackHandler { onClose(null) }
            LockedPanel(name, l.wrong, onCancel = { onClose(null) }) { pw ->
                scope.launch {
                    load = if (l.session.unlock(pw)) Load.Ready(l.session) else Load.Locked(l.session, wrong = true)
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
                onBack = { onClose(null) },
            )
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
    var flash by remember { mutableStateOf<String?>(null) }
    val showBars = reader.chrome || reader.searchOpen

    BackHandler {
        when {
            reader.selection != null -> reader.clearSelection()
            reader.searchOpen -> reader.closeSearch()
            else -> onBack()
        }
    }
    LaunchedEffect(flash) { if (flash != null) { delay(1600); flash = null } }

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
            )
        }

        AnimatedVisibility(showBars, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Column(Modifier.fillMaxWidth().background(Ink.Black).windowInsetsPadding(WindowInsets.statusBars)) {
                if (reader.searchOpen) {
                    SearchBar(reader, current)
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(Glyph.BACK, "Back to library", onBack)
                        Column(Modifier.weight(1f).padding(end = 4.dp)) {
                            Text(name, style = Type.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (pageCount == 1) "1 page" else "$pageCount pages", style = Type.small)
                        }
                        IconButton(Glyph.SEARCH, "Find in document", { reader.searchOpen = true }, color = Ink.Sand, size = 44.dp)
                        val marked = current in reader.bookmarks
                        IconButton(
                            if (marked) Glyph.BOOKMARK_FILLED else Glyph.BOOKMARK,
                            if (marked) "Remove bookmark" else "Bookmark this page",
                            { reader.toggleBookmark(current); flash = if (marked) "Bookmark removed" else "Page ${current + 1} bookmarked" },
                            color = if (marked) Ink.Amber else Ink.Sand, size = 44.dp,
                        )
                        IconButton(Glyph.LIST, "Contents and bookmarks", { reader.loadOutline(); sheet = ViewerSheet.CONTENTS }, color = Ink.Sand, size = 44.dp)
                        IconButton(Glyph.MOON, "Page colours and reading mode", { sheet = ViewerSheet.DISPLAY }, color = Ink.Sand, size = 44.dp)
                    }
                }
                Hairline(color = Ink.Rule)
            }
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

        AnimatedVisibility(showBars && pageCount > 1, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterEnd)) {
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
            if (sel != null) {
                SelectionBar(
                    words = sel.text.trim().split(Regex("\\s+")).count { it.isNotBlank() },
                    onCopy = { if (reader.copySelection(context)) flash = "Copied"; reader.clearSelection() },
                    onFind = { val t = sel.text.trim(); reader.clearSelection(); reader.searchOpen = true; reader.search(t, current) },
                    onShare = {
                        val t = sel.text.trim()
                        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), null)) }
                    },
                    onClose = reader::clearSelection,
                )
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

    if (askPage) {
        GoToPage(pageCount, onDismiss = { askPage = false }) { p -> askPage = false; reader.go(p - 1) }
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
                    ViewerSheet.CONTENTS -> ContentsSheet(reader, current) { p -> sheet = null; reader.go(p) }
                    ViewerSheet.DISPLAY -> DisplaySheet(reader)
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
        IconButton(Glyph.CLOSE, "Close search", reader::closeSearch, color = Ink.Sand)
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
    }
}

@Composable
private fun SelectionBar(words: Int, onCopy: () -> Unit, onFind: () -> Unit, onShare: () -> Unit, onClose: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .background(Ink.Field, Corner)
            .border(1.dp, Ink.Edge, Corner)
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (words == 1) "1 word" else "$words words", style = Type.small, modifier = Modifier.padding(end = 6.dp))
        BarAction("Copy", onCopy)
        BarAction("Find", onFind)
        BarAction("Share", onShare)
        IconButton(Glyph.CLOSE, "Clear selection", onClose, color = Ink.Dust, size = 44.dp)
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
internal fun ContentsSheet(reader: Reader, current: Int, onPage: (Int) -> Unit) {
    var tab by remember { mutableStateOf(if (reader.outline.isNullOrEmpty() && reader.bookmarks.isNotEmpty()) 1 else 0) }
    Row(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Chip("Contents", { tab = 0 }, selected = tab == 0)
        Chip(if (reader.bookmarks.isEmpty()) "Bookmarks" else "Bookmarks  ${reader.bookmarks.size}", { tab = 1 }, selected = tab == 1)
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
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = e.page >= 0) { onPage(e.page) }
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
                        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onPage(p) }.padding(start = 20.dp, end = 4.dp),
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
internal fun DisplaySheet(reader: Reader) {
    Column(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 8.dp)) {
        Text("PAGES", style = Type.eyebrow)
        Spacer(Modifier.height(4.dp))
    }
    PaperMode.entries.forEach { m ->
        val sel = reader.mode == m
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { reader.setMode(m) }.padding(horizontal = 20.dp),
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
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { reader.setReflow(!reader.reflow) }.padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Reflow text", style = Type.body.copy(color = if (reader.reflow) Ink.Amber else Ink.Bone))
            Text("Just the words, sized for the phone. Handy for small print.", style = Type.small)
        }
        Chip(if (reader.reflow) "On" else "Off", { reader.setReflow(!reader.reflow) }, selected = reader.reflow)
    }
    if (reader.reflow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Text size", style = Type.note, modifier = Modifier.weight(1f))
            GhostButton("A−", { reader.setReflowSize(reader.reflowSize - 2) }, minHeight = 44.dp)
            Text("${reader.reflowSize}", style = Type.figure, modifier = Modifier.padding(horizontal = 14.dp))
            GhostButton("A+", { reader.setReflowSize(reader.reflowSize + 2) }, minHeight = 44.dp)
        }
    }
}

fun reflowInk(mode: PaperMode): Color = when (mode) {
    PaperMode.PAPER -> Color(0xFF1E1B16)
    PaperMode.NIGHT -> Ink.Bone
    PaperMode.SEPIA -> Color(0xFF3B2C1C)
}

/** Each page's words as paragraphs, in the chosen size and colours. */
@Composable
private fun ReflowView(reader: Reader, listState: LazyListState, padding: PaddingValues) {
    val ink = reflowInk(reader.mode)
    val bg = if (reader.mode == PaperMode.NIGHT) Ink.Black else paperColor(reader.mode)
    val size = reader.reflowSize
    LazyColumn(
        state = listState,
        contentPadding = padding,
        modifier = Modifier.fillMaxSize().background(bg).pointerInput(Unit) { detectTapGestures(onTap = { reader.chrome = !reader.chrome }) },
    ) {
        items(reader.pageCount, key = { it }) { i ->
            LaunchedEffect(i) { reader.loadParagraphs(i) }
            val paras = reader.paragraphs[i]
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy((size * 0.6f).dp)) {
                Text("PAGE ${i + 1}", style = Type.eyebrow.copy(color = ink.copy(alpha = 0.55f)))
                when {
                    paras == null -> Spacer(Modifier.height(200.dp))
                    paras.isEmpty() -> Text("No text on this page. It may be a picture or a scan.", style = Type.note.copy(color = ink.copy(alpha = 0.6f)))
                    else -> paras.forEach { p ->
                        Text(p, style = Type.body.copy(color = ink, fontSize = size.sp, lineHeight = (size * 1.55f).sp))
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
