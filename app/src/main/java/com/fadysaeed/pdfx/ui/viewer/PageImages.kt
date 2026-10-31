package com.fadysaeed.pdfx.ui.viewer

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.fadysaeed.pdfx.engine.PdfSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/** What the screen needs drawn right now. */
data class Want(val range: IntRange, val focus: Int, val widthPx: Int)

/**
 * Keeps rendered pages for the part of the document on screen. Pages nearest the one being read
 * are drawn first; when the reader jumps ahead, unfinished work for old pages is dropped.
 * Memory is held to a fixed pixel budget so a 2,000-page file costs the same as a 10-page one.
 */
class PageImages(private val session: PdfSession, scope: CoroutineScope) {
    val images = mutableStateMapOf<Int, ImageBitmap>()
    /** Width over height for every page; starts as page 1's shape and settles as pages are measured. */
    val aspects = mutableStateListOf<Float>()

    private val renderedWidth = HashMap<Int, Int>()
    private val bytes = HashMap<Int, Long>()
    private val wanted = MutableStateFlow<Want?>(null)

    init {
        scope.launch {
            val n = session.pageCount
            if (n == 0) return@launch
            val first = session.pageSize(0).aspect
            aspects.addAll(List(n) { first })
            // Measure the rest in chunks so rendering can slip in between.
            var start = 0
            while (start < n) {
                val end = minOf(n, start + 40) - 1
                val sizes = session.pageSizes(start..end)
                sizes.forEachIndexed { k, s -> aspects[start + k] = s.aspect }
                start = end + 1
            }
        }
        scope.launch {
            wanted.collectLatest { w -> if (w != null) renderFor(w) }
        }
    }

    fun want(w: Want) { wanted.value = w }

    private suspend fun renderFor(w: Want) {
        val order = w.range.sortedBy { abs(it - w.focus) }
        for (i in order) {
            if (session.closed) return
            val target = widthFor(i, w.widthPx)
            if (renderedWidth[i] == target) continue
            val bmp = runCatching { session.render(i, target) }.getOrNull() ?: continue
            images[i] = bmp.asImageBitmap()
            renderedWidth[i] = target
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
