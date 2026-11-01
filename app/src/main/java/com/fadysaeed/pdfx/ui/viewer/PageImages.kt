package com.fadysaeed.pdfx.ui.viewer

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
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

/** What the screen needs drawn right now. */
data class Want(val range: IntRange, val focus: Int, val widthPx: Int, val seq: Int = 0)

/** A sharp patch of a page for deep zoom: the part on screen, drawn at the column's full size. */
data class TileReq(val page: Int, val columnPx: Int, val x: Int, val y: Int, val w: Int, val h: Int)
data class Tile(val req: TileReq, val image: ImageBitmap)

/**
 * Keeps rendered pages for the part of the document on screen. Pages nearest the one being read
 * are drawn first; when the reader jumps ahead, unfinished work for old pages is dropped.
 * Memory is held to a fixed pixel budget so a 2,000-page file costs the same as a 10-page one.
 */
class PageImages(private val session: PdfSession, scope: CoroutineScope) {
    val images = mutableStateMapOf<Int, ImageBitmap>()
    /** Width over height for every page; starts as page 1's shape and settles as pages are measured. */
    val aspects = mutableStateListOf<Float>()
    /** Page widths in PDF points, to place highlights and selections on the page. */
    val widths = mutableStateListOf<Float>()
    /** At most one sharp patch per page, for whatever is on screen when zoomed in deep. */
    val tiles = mutableStateMapOf<Int, Tile>()
    private val wantedTiles = MutableStateFlow<Pair<Int, List<TileReq>>>(0 to emptyList())
    private var seq = 0
    /** Counts redraws per page, so a change can wait for its page to be drawn again. */
    private val drawn = MutableStateFlow<Map<Int, Int>>(emptyMap())

    private val renderedWidth = HashMap<Int, Int>()
    private val bytes = HashMap<Int, Long>()
    private val wanted = MutableStateFlow<Want?>(null)

    init {
        scope.launch {
            // These lists may have been made while the screen was being composed; wait for that
            // to land before writing to them, or the writes can be lost.
            runCatching { withFrameNanos { } }
            val n = session.pageCount
            if (n == 0) return@launch
            val firstSize = session.pageSize(0)
            val first = firstSize.aspect
            aspects.addAll(List(n) { first })
            widths.addAll(List(n) { firstSize.width })
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

    private suspend fun renderFor(w: Want) {
        val order = w.range.sortedBy { abs(it - w.focus) }
        for (i in order) {
            if (session.closed) return
            val target = widthFor(i, w.widthPx)
            if (renderedWidth[i] == target) continue
            val bmp = runCatching { session.render(i, target) }.getOrNull() ?: continue
            images[i] = bmp.asImageBitmap()
            renderedWidth[i] = target
            drawn.value = drawn.value + (i to ((drawn.value[i] ?: 0) + 1))
            bytes[i] = bmp.allocationByteCount.toLong()
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
            images.remove(v)
            renderedWidth.remove(v)
        }
    }

    private companion object {
        const val BUDGET_BYTES = 220L * 1024 * 1024
        const val MAX_PAGE_PIXELS = 18_000_000f
    }
}
