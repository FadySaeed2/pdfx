package com.fadysaeed.pdfx.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Link
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.Executors

/** A rectangle on a page, in PDF points from the top-left corner. */
data class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val width get() = x1 - x0
    val height get() = y1 - y0
    fun contains(x: Float, y: Float) = x in x0..x1 && y in y0..y1
}

/** A link on a page: to another page of the same file, or out to a web address. */
data class PageLink(val box: Box, val page: Int?, val uri: String?)

/** One line of the table of contents. */
data class OutlineEntry(val title: String, val page: Int, val depth: Int)

/** A text selection on one page: its two ends, the boxes to highlight, and the text. */
data class Selection(val page: Int, val ax: Float, val ay: Float, val bx: Float, val by: Float, val boxes: List<Box>, val text: String)

/** Width and height of a page in PDF points (1/72 inch). */
data class PageSize(val width: Float, val height: Float) {
    val aspect: Float get() = if (height > 0f) width / height else 0.707f
}

/**
 * One open document. MuPDF is not thread-safe for a single document, so every call into it runs
 * on this session's own thread, one at a time. Callers just suspend; nothing blocks the UI.
 */
class PdfSession private constructor(
    private val doc: Document,
    private val source: Closeable?,
    private val dispatcher: ExecutorCoroutineDispatcher,
) {
    @Volatile var pageCount: Int = 0
        private set
    @Volatile var closed = false
        private set

    val needsPassword: Boolean get() = locked

    private var locked = false
    private val sizes = HashMap<Int, PageSize>()
    private val pageCache = object : LinkedHashMap<Int, Page>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Page>): Boolean {
            if (size > 6) { runCatching { eldest.value.destroy() }; return true }
            return false
        }
    }

    private val textCache = object : LinkedHashMap<Int, StructuredText>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, StructuredText>): Boolean {
            if (size > 3) { runCatching { eldest.value.destroy() }; return true }
            return false
        }
    }

    private fun prepare() {
        locked = doc.needsPassword()
        if (!locked) pageCount = doc.countPages()
    }

    /** Tries a password. True when the document is now readable. */
    suspend fun unlock(password: String): Boolean = onDoc {
        val ok = doc.authenticatePassword(password)
        if (ok) { locked = false; pageCount = doc.countPages() }
        ok
    }

    private fun page(index: Int): Page = pageCache.getOrPut(index) { doc.loadPage(index) }

    suspend fun pageSize(index: Int): PageSize = onDoc { sizeNow(index) }

    /** Sizes for a run of pages in one trip to the document thread. */
    suspend fun pageSizes(range: IntRange): List<PageSize> = onDoc { range.map { sizeNow(it) } }

    private fun sizeNow(index: Int): PageSize = sizes.getOrPut(index) {
        val b = page(index).bounds
        PageSize(b.x1 - b.x0, b.y1 - b.y0)
    }

    /** Draws one page so that it is [widthPx] pixels wide. */
    suspend fun render(index: Int, widthPx: Int): Bitmap = onDoc {
        val p = page(index)
        val s = sizeNow(index)
        val scale = widthPx / s.width
        AndroidDrawDevice.drawPage(p, Matrix(scale))
    }

    /**
     * Draws only part of a page, at a scale where the whole page would be [pageWidthPx] wide.
     * Used when zoomed in past what a whole-page image can hold: only what is on screen is drawn.
     */
    suspend fun renderRegion(index: Int, pageWidthPx: Int, x: Int, y: Int, w: Int, h: Int): Bitmap = onDoc {
        val p = page(index)
        val scale = pageWidthPx / sizeNow(index).width
        val bmp = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val dev = AndroidDrawDevice(bmp, x, y, true)
        try {
            p.run(dev, Matrix(scale), null)
            dev.close()
        } finally {
            dev.destroy()
        }
        bmp
    }

    /** Every place [needle] appears on a page; each hit is one or more boxes (it may wrap a line). */
    suspend fun search(index: Int, needle: String): List<List<Box>> = onDoc {
        page(index).search(needle)?.map { hit -> hit.map { it.box() } } ?: emptyList()
    }

    suspend fun links(index: Int): List<PageLink> = onDoc {
        val out = ArrayList<PageLink>()
        val links: Array<Link>? = runCatching { page(index).links }.getOrNull()
        links?.forEach { l ->
            val r = l.bounds
            val box = Box(r.x0, r.y0, r.x1, r.y1)
            val uri = l.uri
            if (l.isExternal) out += PageLink(box, null, uri)
            else runCatching { doc.pageNumberFromLocation(doc.resolveLink(uri)) }.getOrNull()
                ?.takeIf { it >= 0 }?.let { out += PageLink(box, it, null) }
        }
        out
    }

    /** The table of contents, flattened with each entry's depth. Empty when the file has none. */
    suspend fun outline(): List<OutlineEntry> = onDoc {
        val out = ArrayList<OutlineEntry>()
        fun walk(items: Array<Outline>?, depth: Int) {
            items?.forEach { o ->
                val page = runCatching { doc.pageNumberFromLocation(doc.resolveLink(o.uri)) }.getOrDefault(-1)
                if (!o.title.isNullOrBlank()) out += OutlineEntry(o.title.trim(), page, depth)
                walk(o.down, depth + 1)
            }
        }
        walk(runCatching { doc.loadOutline() }.getOrNull(), 0)
        out
    }

    private fun stext(index: Int): StructuredText = textCache.getOrPut(index) { page(index).toStructuredText() }

    /** Selects the word at a point (long press). Null when there is no text there. */
    suspend fun selectWord(index: Int, x: Float, y: Float): Selection? = onDoc {
        val st = stext(index)
        val q = st.snapSelection(Point(x, y), Point(x, y), StructuredText.SELECT_WORDS) ?: return@onDoc null
        val r = q.toRect()
        if (r.x1 - r.x0 <= 0f) return@onDoc null
        selectionNow(index, st, r.x0 + 0.5f, (r.y0 + r.y1) / 2, r.x1 - 0.5f, (r.y0 + r.y1) / 2)
    }

    /** Text between two points on a page, as the user drags a selection handle. */
    suspend fun select(index: Int, ax: Float, ay: Float, bx: Float, by: Float): Selection? = onDoc {
        selectionNow(index, stext(index), ax, ay, bx, by)
    }

    private fun selectionNow(index: Int, st: StructuredText, ax: Float, ay: Float, bx: Float, by: Float): Selection? {
        val a = Point(ax, ay)
        val b = Point(bx, by)
        val quads = st.highlight(a, b) ?: return null
        if (quads.isEmpty()) return null
        val text = st.copy(a, b) ?: ""
        return Selection(index, ax, ay, bx, by, quads.map { it.box() }, text)
    }

    /** A page's text as paragraphs, for reading reflowed to the screen width. */
    suspend fun paragraphs(index: Int): List<String> = onDoc {
        val raw = stext(index).asText() ?: ""
        raw.split(Regex("\\n\\s*\\n"))
            .map { block ->
                block.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    .fold(StringBuilder()) { acc, line ->
                        when {
                            acc.isEmpty() -> acc.append(line)
                            acc.endsWith("-") && acc.length > 1 && acc[acc.length - 2].isLetter() -> acc.setLength(acc.length - 1).let { acc.append(line) }
                            else -> acc.append(' ').append(line)
                        }
                    }.toString()
            }
            .filter { it.isNotBlank() }
    }

    /** The plain text of a page, in reading order. */
    suspend fun text(index: Int): String = onDoc {
        val st = page(index).toStructuredText()
        try { st.asText() } finally { st.destroy() }
    }

    /** How many times [needle] appears on a page. */
    suspend fun countHits(index: Int, needle: String): Int = onDoc {
        page(index).search(needle)?.size ?: 0
    }

    suspend fun title(): String? = onDoc {
        doc.getMetaData(Document.META_INFO_TITLE)?.takeIf { it.isNotBlank() }
    }

    fun close() {
        if (closed) return
        closed = true
        dispatcher.executor.execute {
            textCache.values.forEach { runCatching { it.destroy() } }
            textCache.clear()
            pageCache.values.forEach { runCatching { it.destroy() } }
            pageCache.clear()
            runCatching { doc.destroy() }
            runCatching { source?.close() }
        }
        dispatcher.close()
    }

    private suspend fun <T> onDoc(block: () -> T): T {
        check(!closed) { "Document is closed" }
        return withContext(dispatcher) { block() }
    }

    companion object {
        private fun newThread(): ExecutorCoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "pdfx-doc").apply { isDaemon = true } }
                .asCoroutineDispatcher()

        /** Opens a document from any content or file Uri, reading it in place. */
        suspend fun open(context: Context, uri: Uri): PdfSession {
            val d = newThread()
            return try {
                withContext(d) {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: error("The file could not be opened.")
                    val stream = FdStream(pfd)
                    try {
                        val doc = Document.openDocument(stream, "application/pdf")
                        PdfSession(doc, stream, d).also { it.prepare() }
                    } catch (t: Throwable) {
                        stream.close(); throw t
                    }
                }
            } catch (t: Throwable) {
                d.close(); throw t
            }
        }

        /** Opens a document from a path on disk. */
        suspend fun openFile(path: String): PdfSession {
            val d = newThread()
            return try {
                withContext(d) { PdfSession(Document.openDocument(path), null, d).also { it.prepare() } }
            } catch (t: Throwable) {
                d.close(); throw t
            }
        }
    }
}

private fun Quad.box(): Box {
    val r = toRect()
    return Box(r.x0, r.y0, r.x1, r.y1)
}
