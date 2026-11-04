package com.fadysaeed.pdfx.ui.viewer

import android.graphics.Bitmap
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.fadysaeed.pdfx.engine.PdfSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.withFrameNanos
import kotlin.math.abs

/**
 * What the screen needs drawn right now: [range] the pages to have ready, [onScreen] the ones
 * showing. [fast]: scrolling quickly, so soft pictures will do until it slows.
 */
data class Want(val range: IntRange, val focus: Int, val widthPx: Int, val seq: Int = 0, val fast: Boolean = false, val onScreen: IntRange = range)

/**
 * Whether the pages are going by quickly, measured over the last [WINDOW_NS] rather than frame to
 * frame: single frames arrive unevenly, and a slow scroll measured that way read as fast now and
 * then, which swapped sharp pictures for soft ones and back while reading. Fast past [UP] screens
 * a second, slow again under [DOWN], so it never wavers between the two.
 */
class ScrollSpeed {
    private val times = ArrayDeque<Long>()
    private val places = ArrayDeque<Float>()
    var fast = false
        private set

    /** One look at the scroll: when ([nanos]), how far down ([pos], pixels) and the screen's height. Returns [fast]. */
    fun add(nanos: Long, pos: Float, screen: Float): Boolean {
        times.addLast(nanos); places.addLast(pos)
        while (times.size > 2 && nanos - times.first() > WINDOW_NS) { times.removeFirst(); places.removeFirst() }
        val span = nanos - times.first()
        if (span < MIN_SPAN_NS) return fast
        val speed = kotlin.math.abs(pos - places.first()) / (span / 1e9f) / screen.coerceAtLeast(1f)
        fast = if (fast) speed > DOWN else speed > UP
        return fast
    }

    /** The scroll stopped. */
    fun stop() { times.clear(); places.clear(); fast = false }

    companion object {
        const val WINDOW_NS = 200_000_000L
        const val MIN_SPAN_NS = 80_000_000L
        const val UP = 4f
        const val DOWN = 2f
    }
}

/**
 * Pictures of pages, each page in a state of its own: a new picture for page 7 redraws page 7 and
 * nothing else. (One shared map made every page on screen draw again for any page's new picture,
 * many times a second in a quick scroll.)
 */
class PageSlots<T : Any> {
    private val slots = HashMap<Int, MutableState<T?>>()
    private fun slot(i: Int) = slots.getOrPut(i) { mutableStateOf(null) }

    operator fun get(i: Int): T? = slot(i).value
    operator fun set(i: Int, v: T) { slot(i).value = v }
    fun remove(i: Int): T? { val s = slots[i] ?: return null; val v = s.value; s.value = null; return v }
    fun containsKey(i: Int): Boolean = slots[i]?.value != null
    val keys: Set<Int> get() = slots.filterValues { it.value != null }.keys
    val size: Int get() = slots.values.count { it.value != null }
    fun toMap(): Map<Int, T> = slots.mapNotNull { (k, v) -> v.value?.let { k to it } }.toMap()
    fun putAll(m: Map<Int, T>) = m.forEach { (k, v) -> set(k, v) }
    fun clear() = slots.values.forEach { it.value = null }
}

/** A sharp patch of a page for deep zoom: the part on screen, drawn at the column's full size. */
data class TileReq(val page: Int, val columnPx: Int, val x: Int, val y: Int, val w: Int, val h: Int)
data class Tile(val req: TileReq, val image: ImageBitmap)

/**
 * Keeps rendered pages for the part of the document on screen. Pages nearest the one being read
 * are drawn first; when the reader jumps ahead, unfinished work for old pages is dropped.
 * Memory is held to a fixed pixel budget so a 2,000-page file costs the same as a 10-page one.
 */
class PageImages(private val session: PdfSession, scope: CoroutineScope, seed: PageImages? = null) {
    /**
     * When the same file is opened again after saving, [seed] is the copy on screen until then: its
     * page shapes and pictures carry over, so the list of pages never empties for a moment (which
     * threw the reader back to page 1) and nothing blinks while the pages are drawn again.
     */
    private val carried: PageImages? = seed?.takeIf { it.aspects.size == session.pageCount && it.widths.size == session.pageCount }
    val images: PageSlots<ImageBitmap> = PageSlots<ImageBitmap>().apply { carried?.images?.toMap()?.let { putAll(it) } }
    /** Width over height for every page; starts as page 1's shape and settles as pages are measured. */
    val aspects: SnapshotStateList<Float> = mutableStateListOf<Float>().apply { carried?.aspects?.toList()?.let { addAll(it) } }
    /** Page widths in PDF points, to place highlights and selections on the page. */
    val widths: SnapshotStateList<Float> = mutableStateListOf<Float>().apply { carried?.widths?.toList()?.let { addAll(it) } }
    /** At most one sharp patch per page, for whatever is on screen when zoomed in deep. */
    val tiles = PageSlots<Tile>()
    private val wantedTiles = MutableStateFlow<Pair<Int, List<TileReq>>>(0 to emptyList())
    private var seq = 0
    /** Counts redraws per page, so a change can wait for its page to be drawn again. */
    private val drawn = MutableStateFlow<Map<Int, Int>>(emptyMap())

    private val renderedWidth = HashMap<Int, Int>()
    private val bytes = HashMap<Int, Long>()
    /** The pictures behind [images] that this drew, so they can be drawn into again once off screen. */
    private val raw = HashMap<Int, Bitmap>()
    /** Pictures no longer shown, with when they left, kept to draw new pages into. */
    private val spare = ArrayDeque<Pair<Bitmap, Long>>()
    private val wanted = MutableStateFlow<Want?>(null)

    init {
        scope.launch {
            // These lists may have been made while the screen was being composed; wait for that
            // to land before writing to them, or the writes can be lost.
            runCatching { withFrameNanos { } }
            val n = session.pageCount
            if (n == 0) return@launch
            if (aspects.size != n) {
                val firstSize = session.pageSize(0)
                val first = firstSize.aspect
                aspects.clear(); widths.clear()
                aspects.addAll(List(n) { first })
                widths.addAll(List(n) { firstSize.width })
            }
            // Measure the rest in chunks so rendering can slip in between.
            var start = 0
            while (start < n) {
                val end = minOf(n, start + 40) - 1
                val sizes = session.pageSizes(start..end)
                sizes.forEachIndexed { k, s ->
                    val i = start + k
                    if (i < aspects.size) aspects[i] = s.aspect
                    if (i < widths.size) widths[i] = s.width
                }
                start = end + 1
            }
        }
        scope.launch {
            wanted.collectLatest { w -> if (w != null) renderFor(w) }
        }
        scope.launch {
            wantedTiles.collectLatest { (_, reqs) ->
                for (r in reqs) {
                    if (session.closed) return@collectLatest
                    if (tiles[r.page]?.req == r) continue
                    val bmp = runCatching { session.renderRegion(r.page, r.columnPx, r.x, r.y, r.w, r.h) }.getOrNull() ?: continue
                    tiles[r.page] = Tile(r, bmp.asImageBitmap())
                }
            }
        }
    }

    fun want(w: Want) { wanted.value = w }

    /** Asks for sharp patches; patches drawn for another zoom level or page are dropped at once. */
    fun wantTiles(reqs: List<TileReq>) {
        val pages = reqs.associateBy { it.page }
        tiles.keys.toList().forEach { p -> if (pages[p]?.columnPx != tiles[p]?.req?.columnPx) tiles.remove(p) }
        wantedTiles.value = wantedTiles.value.first to reqs
    }

    /** Draws pages again after their content changed (a new mark, an undo). Null means every page. */
    fun invalidate(pages: Collection<Int>?) {
        if (pages == null) { renderedWidth.clear(); tiles.clear() } else pages.forEach { renderedWidth.remove(it); tiles.remove(it) }
        seq++
        wanted.value = wanted.value?.copy(seq = seq)
        wantedTiles.value = seq to wantedTiles.value.second
    }

    /** Waits (briefly) until [page] has been drawn again after [invalidate]. */
    suspend fun awaitRedraw(page: Int, after: Int) {
        kotlinx.coroutines.withTimeoutOrNull(2000) { drawn.first { (it[page] ?: 0) > after } }
    }

    fun drawCount(page: Int): Int = drawn.value[page] ?: 0

    /** Puts a picture on page [i] in place of the one there, which goes to [spare]. */
    private fun show(i: Int, bmp: Bitmap, sharp: Boolean) {
        ScrollCheck.event((if (sharp) "sharp " else "soft ") + (if (bmp === reusedLast) "reused" else "new") + " p$i")
        // Sent to the graphics memory now, in the background, rather than in the frame it first shows.
        if (ScrollCheck.prepare) bmp.prepareToDraw()
        images[i] = bmp.asImageBitmap()
        raw.put(i, bmp)?.let { if (it !== bmp) retire(it) }
        bytes[i] = bmp.allocationByteCount.toLong()
    }

    private fun retire(b: Bitmap) = synchronized(spare) {
        spare.addLast(b to System.currentTimeMillis())
        while (spare.size > SPARE_MAX) spare.removeFirst()
    }

    /**
     * A picture of exactly [w] × [h] that left the screen long enough ago that nothing still draws
     * it (asked on the document's thread).
     */
    private fun reuse(w: Int, h: Int): Bitmap? = synchronized(spare) {
        if (!ScrollCheck.reuse) return@synchronized null
        val now = System.currentTimeMillis()
        val k = spare.indexOfFirst { (b, at) -> b.width == w && b.height == h && now - at >= SPARE_AFTER_MS }
        if (k < 0) null else spare.removeAt(k).first.also { reusedLast = it }
    }

    /** The last picture handed out to be drawn into again, for the scroll check's report. */
    @Volatile private var reusedLast: Bitmap? = null

    private suspend fun renderFor(w: Want) {
        val order = w.range.sortedBy { abs(it - w.focus) }
        // A quick soft picture first for pages with none, so a fast scroll never shows a blank page;
        // the sharp one follows. A third of the width draws in about a ninth of the time.
        for (i in order) {
            if (session.closed) return
            // Soft pictures only where one is needed now: a page showing with none, or any page while
            // scrolling quickly. Pages being got ready off screen get their sharp picture straight
            // away, so a slow scroll never sees a soft one turn sharp.
            if (images.containsKey(i) || (!w.fast && i !in w.onScreen)) continue
            val soft = (widthFor(i, w.widthPx) / 3).coerceAtLeast(64)
            val bmp = runCatching { session.render(i, soft, ::reuse) }.getOrNull() ?: continue
            show(i, bmp, sharp = false)
            renderedWidth[i] = soft
            trim(w)
        }
        // Scrolling quickly: the pages go by too fast to read, so the sharp pictures wait until it slows.
        if (w.fast) return
        for (i in order) {
            if (session.closed) return
            val target = widthFor(i, w.widthPx)
            if (renderedWidth[i] == target) continue
            val bmp = runCatching { session.render(i, target, ::reuse) }.getOrNull() ?: continue
            show(i, bmp, sharp = true)
            renderedWidth[i] = target
            drawn.value = drawn.value + (i to ((drawn.value[i] ?: 0) + 1))
            trim(w)
        }
    }

    /** Very tall or very large pages are capped so one page can never exhaust memory. */
    private fun widthFor(i: Int, widthPx: Int): Int {
        val aspect = aspects.getOrNull(i)?.takeIf { it > 0f } ?: 0.707f
        val height = widthPx / aspect
        val pixels = widthPx * height
        if (pixels <= MAX_PAGE_PIXELS) return widthPx
        return (widthPx * kotlin.math.sqrt(MAX_PAGE_PIXELS / pixels)).toInt().coerceAtLeast(64)
    }

    private fun trim(w: Want) {
        var total = bytes.values.sum()
        if (total <= BUDGET_BYTES) return
        val victims = images.keys.filter { it !in w.range }.sortedByDescending { abs(it - w.focus) }
        for (v in victims) {
            if (total <= BUDGET_BYTES) break
            total -= bytes.remove(v) ?: 0
            ScrollCheck.event("dropped p$v")
            images.remove(v)
            raw.remove(v)?.let(::retire)
            renderedWidth.remove(v)
        }
    }

    private companion object {
        const val BUDGET_BYTES = 220L * 1024 * 1024
        /** Pictures kept to draw into again, and how long after leaving the screen one may be. */
        const val SPARE_MAX = 6
        const val SPARE_AFTER_MS = 400L
        const val MAX_PAGE_PIXELS = 18_000_000f
    }
}
