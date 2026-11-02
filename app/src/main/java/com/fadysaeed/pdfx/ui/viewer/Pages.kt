package com.fadysaeed.pdfx.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.fadysaeed.pdfx.engine.PageItem
import com.fadysaeed.pdfx.engine.PageSource
import com.fadysaeed.pdfx.engine.PdfSession
import com.fadysaeed.pdfx.engine.originalPages
import com.fadysaeed.pdfx.ui.components.AmberButton
import com.fadysaeed.pdfx.ui.components.Glyph
import com.fadysaeed.pdfx.ui.components.GlyphIcon
import com.fadysaeed.pdfx.ui.components.Hairline
import com.fadysaeed.pdfx.ui.components.IconButton
import com.fadysaeed.pdfx.ui.theme.Ink
import com.fadysaeed.pdfx.ui.theme.Type
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The pages of one file being put in order: the arrangement, which pages are picked, and undo.
 * Nothing touches the file until it is saved; [items] is what the saved file will hold.
 */
class Organizer(val count: Int, private val scope: CoroutineScope, private val session: PdfSession?) {
    val items = mutableStateListOf<PageItem>().apply { addAll(originalPages(count)) }
    val picked = mutableStateListOf<Long>()
    private val undos = ArrayList<List<PageItem>>()
    private val redos = ArrayList<List<PageItem>>()
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    /** A short note on what just happened, shown at the foot of the screen. */
    var note by mutableStateOf<String?>(null)

    /** True when the arrangement is no longer the file as it is. */
    val changed: Boolean
        get() = items.size != count || items.withIndex().any { (i, p) -> p.source != PageSource.ORIGINAL || p.page != i || p.turn != 0 }

    private fun flags() { canUndo = undos.isNotEmpty(); canRedo = redos.isNotEmpty() }

    /** Remembers the arrangement as it is, so the next change can be undone. */
    fun record() { undos += items.toList(); if (undos.size > 100) undos.removeAt(0); redos.clear(); flags() }

    fun undo() {
        val last = undos.removeLastOrNull() ?: return
        redos += items.toList(); set(last); flags()
    }

    fun redo() {
        val next = redos.removeLastOrNull() ?: return
        undos += items.toList(); set(next); flags()
    }

    private fun set(list: List<PageItem>) {
        items.clear(); items.addAll(list)
        picked.retainAll(list.map { it.id }.toSet())
    }

    fun toggle(id: Long) { if (id in picked) picked.remove(id) else picked.add(id) }
    fun pickAll() { picked.clear(); picked.addAll(items.map { it.id }) }
    fun pickNone() = picked.clear()

    /** The picked pages in the order they stand. */
    val pickedItems: List<PageItem> get() = items.filter { it.id in picked }

    /** Moves the page at [from] to [to] while it is being dragged (the drag records once, at its start). */
    fun move(from: Int, to: Int) {
        if (from == to || from !in items.indices || to !in items.indices) return
        items.add(to, items.removeAt(from))
    }

    fun turn(by: Int) {
        if (picked.isEmpty()) return
        record()
        for (i in items.indices) if (items[i].id in picked) items[i] = items[i].turned(by)
    }

    /** Takes the picked pages out. At least one page always stays. */
    fun delete(): Boolean {
        if (picked.isEmpty()) return false
        if (picked.size >= items.size) { note = "A file needs at least one page, so the last one stays."; return false }
        record()
        val n = picked.size
        items.removeAll { it.id in picked }
        picked.clear()
        note = if (n == 1) "Page taken out" else "$n pages taken out"
        return true
    }

    /** A copy of each picked page, right after it; the copies are picked instead. */
    fun duplicate() {
        if (picked.isEmpty()) return
        record()
        val copies = ArrayList<Long>()
        var i = 0
        while (i < items.size) {
            if (items[i].id in picked) { val c = items[i].twin(); items.add(i + 1, c); copies += c.id; i++ }
            i++
        }
        picked.clear(); picked.addAll(copies)
        note = if (copies.size == 1) "Page copied" else "${copies.size} pages copied"
    }

    /** Puts [new] pages after the last picked page, or at the end, and picks them. */
    fun insert(new: List<PageItem>) {
        if (new.isEmpty()) return
        record()
        val after = items.indexOfLast { it.id in picked }
        val at = if (after < 0) items.size else after + 1
        items.addAll(at, new)
        picked.clear(); picked.addAll(new.map { it.id })
    }

    /** Where a new blank page goes, and the size of the page before it (A4 when there is none). */
    suspend fun blankSize(): Pair<Float, Float> {
        val after = items.indexOfLast { it.id in picked }.let { if (it < 0) items.lastIndex else it }
        val near = items.getOrNull(after) ?: return 595f to 842f
        if (near.source == PageSource.ORIGINAL && session != null) {
            val s = runCatching { session.pageSize(near.page) }.getOrNull() ?: return 595f to 842f
            return s.width to s.height
        }
        return near.width to near.height
    }

    // ------------------------------------------------------------ small pictures of the pages

    val thumbs = mutableStateMapOf<String, ImageBitmap>()
    private val loading = HashSet<String>()
    private val others = HashMap<String, PdfSession?>()

    fun key(p: PageItem) = "${p.source}:${p.path}:${p.page}:${p.turn}"

    fun want(p: PageItem, widthPx: Int) {
        if (p.source == PageSource.BLANK) return
        val k = key(p)
        if (k in thumbs || !loading.add(k)) return
        scope.launch {
            val bmp = runCatching {
                when (p.source) {
                    PageSource.ORIGINAL -> session?.render(p.page, widthPx)
                    PageSource.OTHER -> {
                        val path = p.path ?: return@runCatching null
                        val s = others.getOrPut(path) { runCatching { PdfSession.openFile(path) }.getOrNull()?.takeIf { !it.needsPassword } }
                        s?.render(p.page, widthPx)
                    }
                    PageSource.PICTURE -> withContext(Dispatchers.IO) { decodeSmall(p.path ?: return@withContext null, widthPx) }
                    PageSource.BLANK -> null
                }
            }.getOrNull()
            loading.remove(k)
            if (bmp != null) thumbs[k] = withContext(Dispatchers.Default) { turned(bmp, p.turn) }.asImageBitmap()
        }
    }

    /** The pages of another PDF, opened to show and count them. Null when it cannot be read. */
    suspend fun openOther(path: String): Int? {
        val s = others.getOrPut(path) { runCatching { PdfSession.openFile(path) }.getOrNull()?.takeIf { !it.needsPassword } } ?: return null
        return s.pageCount
    }

    fun close() { others.values.forEach { it?.close() }; others.clear() }

    private fun turned(b: Bitmap, deg: Int): Bitmap {
        if (deg % 360 == 0) return b
        val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun decodeSmall(path: String, widthPx: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= widthPx) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/**
 * Every page of the file as a small picture, to put in order: long-press and drag to move a
 * page, tap to pick pages, then turn, copy, take out or save them as a file of their own. Pages
 * can be added: a blank one, pages of another PDF, or photos. Nothing changes in the file until
 * Save; Close without saving leaves it as it was.
 */
@Composable
fun PagesScreen(
    o: Organizer,
    name: String,
    busy: Boolean,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onExtract: () -> Unit,
    onAddBlank: () -> Unit,
    onAddPdf: () -> Unit,
    onAddPictures: () -> Unit,
) {
    val grid = rememberLazyGridState()
    var adding by remember { mutableStateOf(false) }
    // Dragging: which page, and how far it has come from where it now stands.
    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragDelta by remember { mutableStateOf(Offset.Zero) }
    var edge by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val thumbPx = with(density) { 132.dp.roundToPx() }

    LaunchedEffect(o.note) { if (o.note != null) { delay(2600); o.note = null } }
    // Near the top or bottom while dragging: the grid scrolls on its own.
    LaunchedEffect(dragId, edge) {
        while (dragId != null && edge != 0f) { grid.scrollBy(edge); delay(16) }
    }

    Column(Modifier.fillMaxSize().background(Ink.Black).windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Glyph.CLOSE, "Close the pages", onClose)
            Column(Modifier.weight(1f)) {
                Text("Pages", style = Type.title)
                Text(
                    when {
                        o.picked.isNotEmpty() -> "${o.picked.size} of ${o.items.size} picked"
                        else -> "${o.items.size} pages · hold and drag to move"
                    },
                    style = Type.small, maxLines = 1,
                )
            }
            IconButton(Glyph.UNDO, "Undo", o::undo, color = if (o.canUndo) Ink.Bone else Ink.Slate, size = 44.dp)
            IconButton(Glyph.REDO, "Redo", o::redo, color = if (o.canRedo) Ink.Bone else Ink.Slate, size = 44.dp)
            IconButton(Glyph.PLUS, "Add pages", { adding = true }, color = Ink.Sand, size = 44.dp)
            AmberButton(if (busy) "Saving…" else "Save", onSave, enabled = o.changed && !busy, modifier = Modifier.padding(start = 4.dp, end = 6.dp))
        }
        Hairline(color = Ink.Rule)
        Box(Modifier.weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                state = grid,
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize().pointerInput(o) {
                    fun at(pos: Offset) = grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
                        pos.x >= item.offset.x && pos.x <= item.offset.x + item.size.width &&
                            pos.y >= item.offset.y && pos.y <= item.offset.y + item.size.height
                    }
                    detectDragGesturesAfterLongPress(
                        onDragStart = { pos ->
                            at(pos)?.let { item ->
                                o.record()
                                dragId = o.items.getOrNull(item.index)?.id
                                dragDelta = Offset.Zero
                            }
                        },
                        onDrag = { change, amount ->
                            val id = dragId ?: return@detectDragGesturesAfterLongPress
                            change.consume()
                            dragDelta += amount
                            val info = grid.layoutInfo
                            val me = info.visibleItemsInfo.firstOrNull { it.key == id } ?: return@detectDragGesturesAfterLongPress
                            val centre = Offset(me.offset.x + me.size.width / 2f, me.offset.y + me.size.height / 2f) + dragDelta
                            val over = at(centre)
                            // Only once the grid has caught up with the last move, or a page jumps twice.
                            val settled = o.items.getOrNull(me.index)?.id == id
                            if (settled && over != null && over.index != me.index) {
                                val from = me.index
                                o.move(from, over.index)
                                // Stay under the finger: the page now stands where the other one was.
                                dragDelta += Offset((me.offset.x - over.offset.x).toFloat(), (me.offset.y - over.offset.y).toFloat())
                            }
                            val h = info.viewportSize.height
                            val y = change.position.y
                            edge = when {
                                y < 80f -> -14f
                                y > h - 80f -> 14f
                                else -> 0f
                            }
                        },
                        onDragEnd = { dragId = null; dragDelta = Offset.Zero; edge = 0f },
                        onDragCancel = { dragId = null; dragDelta = Offset.Zero; edge = 0f },
                    )
                },
            ) {
                itemsIndexed(o.items, key = { _, p -> p.id }) { i, p ->
                    val dragging = p.id == dragId
                    val itemMod = if (dragging) {
                        Modifier.zIndex(1f).graphicsLayer { translationX = dragDelta.x; translationY = dragDelta.y; scaleX = 1.06f; scaleY = 1.06f; shadowElevation = 18f }
                    } else Modifier.animateItem()
                    PageCell(o, p, i, p.id in o.picked, thumbPx, itemMod) { o.toggle(p.id) }
                }
            }
            o.note?.let { msg ->
                Text(
                    msg, style = Type.note.copy(color = Ink.Bone),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
                        .background(Ink.Field, RoundedCornerShape(20.dp)).border(1.dp, Ink.Edge, RoundedCornerShape(20.dp))
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
        }
        Hairline(color = Ink.Rule)
        if (o.picked.isEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Tap pages to pick them; turn, copy, take out or save them as a new file.", style = Type.note, modifier = Modifier.weight(1f))
                Text("Pick all", style = Type.bodyStrong.copy(color = Ink.Amber), modifier = Modifier.clickable(onClick = o::pickAll).padding(horizontal = 10.dp, vertical = 8.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                ActionButton(Glyph.TURN_LEFT, "Turn left") { o.turn(-90) }
                ActionButton(Glyph.TURN_RIGHT, "Turn right") { o.turn(90) }
                ActionButton(Glyph.COPY, "Copy") { o.duplicate() }
                ActionButton(Glyph.EXTRACT, "New file", onExtract)
                ActionButton(Glyph.TRASH, "Take out") { o.delete() }
                ActionButton(Glyph.CLOSE, "Unpick", o::pickNone)
            }
        }
    }

    if (adding) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { adding = false }) {
            Column(Modifier.fillMaxWidth().background(Ink.Field, RoundedCornerShape(14.dp)).border(1.dp, Ink.Edge, RoundedCornerShape(14.dp)).padding(vertical = 10.dp)) {
                Text(
                    if (o.picked.isEmpty()) "Add pages at the end" else "Add pages after the picked ones",
                    style = Type.title, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
                com.fadysaeed.pdfx.ui.components.SheetRow(Glyph.PAGE, "A blank page", { adding = false; onAddBlank() }, note = "The same size as the page before it")
                com.fadysaeed.pdfx.ui.components.SheetRow(Glyph.FOLDER, "Pages from another PDF", { adding = false; onAddPdf() }, note = "All its pages; take out the ones you don't need")
                com.fadysaeed.pdfx.ui.components.SheetRow(Glyph.PLUS, "Photos", { adding = false; onAddPictures() }, note = "Each photo on a page of its own")
            }
        }
    }
}

@Composable
private fun ActionButton(glyph: Glyph, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp).width(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlyphIcon(glyph, Ink.Bone, size = 24.dp)
        Text(label, style = Type.tiny.copy(color = Ink.Sand), textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun PageCell(o: Organizer, p: PageItem, index: Int, picked: Boolean, px: Int, modifier: Modifier, onTap: () -> Unit) {
    LaunchedEffect(o.key(p)) { o.want(p, px) }
    val img = o.thumbs[o.key(p)]
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.74f).clip(RoundedCornerShape(6.dp))
                .border(if (picked) 2.5.dp else 1.dp, if (picked) Ink.Amber else Ink.Edge, RoundedCornerShape(6.dp))
                .background(Ink.Field).clickable(onClick = onTap).padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                p.source == PageSource.BLANK -> Box(
                    Modifier.aspectRatio((if (p.turn % 180 == 0) p.width / p.height else p.height / p.width).coerceIn(0.3f, 3f)).background(Color.White),
                )
                img != null -> Image(img, "Small picture of page ${index + 1}", Modifier.fillMaxSize())
            }
            if (picked) Box(
                Modifier.align(Alignment.TopEnd).size(22.dp).background(Ink.Amber, CircleShape),
                contentAlignment = Alignment.Center,
            ) { GlyphIcon(Glyph.CHECK, Ink.OnAmber, size = 16.dp) }
        }
        Text(
            buildString {
                append(index + 1)
                when (p.source) {
                    PageSource.ORIGINAL -> if (p.page != index) append(" · was ${p.page + 1}")
                    PageSource.OTHER -> append(" · added")
                    PageSource.BLANK -> append(" · blank")
                    PageSource.PICTURE -> append(" · photo")
                }
            },
            style = Type.tiny.copy(color = if (picked) Ink.Amber else Ink.Dust), maxLines = 1, modifier = Modifier.padding(top = 4.dp),
        )
    }
}
