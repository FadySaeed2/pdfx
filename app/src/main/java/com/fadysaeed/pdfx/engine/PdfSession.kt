package com.fadysaeed.pdfx.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Link
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Rect
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

/** The kinds of marks PDFx makes and lists. Anything else in a file shows as OTHER. */
enum class MarkKind(val label: String) {
    HIGHLIGHT("Highlight"), UNDERLINE("Underline"), STRIKE("Strikethrough"), SQUIGGLY("Squiggle"),
    INK("Drawing"), SQUARE("Rectangle"), CIRCLE("Ellipse"), LINE("Line"), ARROW("Arrow"),
    TEXT("Text box"), NOTE("Note"), STAMP("Stamp"), OTHER("Mark"),
}

/**
 * A mark on a page: where it is, what it is, its colour, and its words (a note, a text box's text,
 * or the text under a highlight). [size] is the text size of a text box, 0 for anything else.
 */
data class Mark(val page: Int, val index: Int, val kind: MarkKind, val box: Box, val color: Int, val note: String, val quoted: String, val size: Float = 0f) {
    /** Marks that sit on the page by themselves can be moved; marks over words stay with the words. */
    val movable: Boolean get() = kind != MarkKind.OTHER && kind !in TEXT_MARKS
    /** Notes are a fixed-size icon; everything else movable can also be resized. */
    val resizable: Boolean get() = movable && kind != MarkKind.NOTE
}

/** A paragraph of reflowed text. [level] is 0 for body text, 1 for a heading, 2 for a smaller heading. */
data class Para(val text: String, val level: Int = 0)

/** A stroke of ink: points in PDF points. */
typealias Stroke = List<Pair<Float, Float>>

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

    /** The PDF view of the document, for anything that changes it. Null for non-PDF files. */
    private val pdf: PDFDocument? = runCatching { doc.asPDF() }.getOrNull()
    val editable: Boolean get() = pdf != null
    @Volatile var canUndo = false
        private set
    @Volatile var canRedo = false
        private set
    /** True once anything has changed since opening or the last save. */
    @Volatile var dirty = false
        private set

    private fun prepare() {
        locked = doc.needsPassword()
        if (!locked) pageCount = doc.countPages()
        runCatching { pdf?.enableJournal() }
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

    /**
     * A page's text as paragraphs, for reading reflowed to the screen width. Lines are joined,
     * words split across lines by a hyphen are put back together, and blocks set noticeably larger
     * than the page's body text (or short and all bold) are marked as headings.
     */
    suspend fun paragraphs(index: Int): List<Para> = onDoc {
        val blocks = runCatching { stext(index).blocks }.getOrNull() ?: emptyArray()
        class Raw(val lines: List<String>, val height: Float, val bold: Boolean)
        val raws = ArrayList<Raw>()
        val heights = ArrayList<Pair<Float, Int>>() // line height, characters on it
        for (b in blocks) {
            val lines = b.lines ?: continue
            val texts = ArrayList<String>()
            var hSum = 0f; var hN = 0; var chars = 0; var boldChars = 0
            for (l in lines) {
                val cs = l.chars ?: continue
                val t = buildString { cs.forEach { appendCodePoint(it.c) } }.trim()
                if (t.isEmpty()) continue
                texts += t
                val h = l.bbox?.let { r -> (r.y1 - r.y0).coerceAtLeast(0f) } ?: 0f
                if (h > 0f) { hSum += h; hN++; heights += h to t.length }
                chars += cs.size
                boldChars += cs.count { (it.flags and StructuredText.CHAR_FLAGS_BOLD) != 0 }
            }
            if (texts.isEmpty()) continue
            raws += Raw(texts, if (hN > 0) hSum / hN else 0f, chars > 0 && boldChars * 10 >= chars * 9)
        }
        // The body size is the line height most characters are set in.
        val body = heights.sortedBy { it.first }.let { list ->
            val total = list.sumOf { it.second }
            var acc = 0
            list.firstOrNull { acc += it.second; acc * 2 >= total }?.first ?: 0f
        }
        raws.map { r ->
            val text = r.lines.fold(StringBuilder()) { acc, line ->
                when {
                    acc.isEmpty() -> acc.append(line)
                    acc.endsWith("-") && acc.length > 1 && acc[acc.length - 2].isLetter() && line.first().isLowerCase() ->
                        acc.setLength(acc.length - 1).let { acc.append(line) }
                    else -> acc.append(' ').append(line)
                }
            }.toString()
            val short = r.lines.size <= 3 && text.length <= 160
            val level = when {
                body > 0f && short && r.height >= body * 1.45f -> 1
                body > 0f && short && r.height >= body * 1.18f -> 2
                short && r.bold && r.lines.size <= 2 && text.length <= 100 && raws.size > 1 -> 2
                else -> 0
            }
            Para(text, level)
        }
    }

    // ------------------------------------------------------------------ marks (annotations)

    private fun pdfPage(index: Int): PDFPage = page(index) as PDFPage

    private fun kindOf(t: Int, a: PDFAnnotation): MarkKind = when (t) {
        PDFAnnotation.TYPE_HIGHLIGHT -> MarkKind.HIGHLIGHT
        PDFAnnotation.TYPE_UNDERLINE -> MarkKind.UNDERLINE
        PDFAnnotation.TYPE_STRIKE_OUT -> MarkKind.STRIKE
        PDFAnnotation.TYPE_SQUIGGLY -> MarkKind.SQUIGGLY
        PDFAnnotation.TYPE_INK -> MarkKind.INK
        PDFAnnotation.TYPE_SQUARE -> MarkKind.SQUARE
        PDFAnnotation.TYPE_CIRCLE -> MarkKind.CIRCLE
        PDFAnnotation.TYPE_LINE -> if (runCatching { a.lineEndingStyles.any { it != PDFAnnotation.LINE_ENDING_NONE } }.getOrDefault(false)) MarkKind.ARROW else MarkKind.LINE
        PDFAnnotation.TYPE_FREE_TEXT -> MarkKind.TEXT
        PDFAnnotation.TYPE_TEXT -> MarkKind.NOTE
        PDFAnnotation.TYPE_STAMP -> MarkKind.STAMP
        else -> MarkKind.OTHER
    }

    private fun marksNow(index: Int): List<Mark> {
        if (pdf == null) return emptyList()
        val annots = pdfPage(index).annotations ?: return emptyList()
        val out = ArrayList<Mark>()
        annots.forEachIndexed { i, a ->
            val t = a.type
            if (t == PDFAnnotation.TYPE_LINK || t == PDFAnnotation.TYPE_POPUP || t == PDFAnnotation.TYPE_WIDGET) return@forEachIndexed
            val r = a.bounds
            val kind = kindOf(t, a)
            val c = runCatching { a.color }.getOrNull()
            val quoted = if (kind in TEXT_MARKS) runCatching {
                val st = stext(index)
                (0 until a.quadPointCount).joinToString(" ") { k ->
                    val q = a.getQuadPoint(k).toRect()
                    val y = (q.y0 + q.y1) / 2
                    st.copy(Point(q.x0 + 0.3f, y), Point(q.x1 - 0.3f, y)) ?: ""
                }.replace(Regex("\\s+"), " ").trim()
            }.getOrDefault("") else ""
            val da = if (kind == MarkKind.TEXT) runCatching { a.defaultAppearance }.getOrNull() else null
            val color = if (da?.color != null) argb(da.color) else argb(c)
            out += Mark(index, i, kind, Box(r.x0, r.y0, r.x1, r.y1), color, runCatching { a.contents ?: "" }.getOrDefault(""), quoted, da?.size ?: 0f)
        }
        return out
    }

    suspend fun marks(index: Int): List<Mark> = onDoc { marksNow(index) }

    /** Every mark in the file, page by page, for the list of marks. */
    suspend fun allMarks(): List<Mark> = onDoc { (0 until pageCount).flatMap { runCatching { marksNow(it) }.getOrDefault(emptyList()) } }

    /** Highlight, underline, strike or squiggle over text, given the boxes of the selected words. */
    suspend fun addTextMark(index: Int, kind: MarkKind, boxes: List<Box>, color: Int) = edit("Mark text", index) {
        val t = when (kind) {
            MarkKind.UNDERLINE -> PDFAnnotation.TYPE_UNDERLINE
            MarkKind.STRIKE -> PDFAnnotation.TYPE_STRIKE_OUT
            MarkKind.SQUIGGLY -> PDFAnnotation.TYPE_SQUIGGLY
            else -> PDFAnnotation.TYPE_HIGHLIGHT
        }
        val a = pdfPage(index).createAnnotation(t)
        a.setQuadPoints(boxes.map { Quad(Rect(it.x0, it.y0, it.x1, it.y1)) }.toTypedArray())
        a.color = rgb(color)
        a.update()
    }

    /** Freehand ink. [opacity] below 1 makes a marker. */
    suspend fun addInk(index: Int, strokes: List<Stroke>, color: Int, width: Float, opacity: Float = 1f) = edit("Draw", index) {
        val a = pdfPage(index).createAnnotation(PDFAnnotation.TYPE_INK)
        a.setInkList(strokes.filter { it.size >= 2 }.map { s -> s.map { Point(it.first, it.second) }.toTypedArray() }.toTypedArray())
        a.color = rgb(color)
        a.borderWidth = width
        if (opacity < 1f) a.opacity = opacity
        a.update()
    }

    suspend fun addShape(index: Int, kind: MarkKind, x0: Float, y0: Float, x1: Float, y1: Float, color: Int, width: Float) = edit("Shape", index) {
        val p = pdfPage(index)
        when (kind) {
            MarkKind.LINE, MarkKind.ARROW -> {
                val a = p.createAnnotation(PDFAnnotation.TYPE_LINE)
                a.setLine(Point(x0, y0), Point(x1, y1))
                if (kind == MarkKind.ARROW) a.setLineEndingStyles(PDFAnnotation.LINE_ENDING_NONE, PDFAnnotation.LINE_ENDING_OPEN_ARROW)
                a.color = rgb(color)
                a.borderWidth = width
                a.update()
            }
            else -> {
                val a = p.createAnnotation(if (kind == MarkKind.CIRCLE) PDFAnnotation.TYPE_CIRCLE else PDFAnnotation.TYPE_SQUARE)
                a.rect = Rect(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
                a.color = rgb(color)
                a.borderWidth = width
                a.update()
            }
        }
    }

    /** Typed words placed on the page. */
    suspend fun addTextBox(index: Int, x: Float, y: Float, text: String, color: Int, size: Float) = edit("Text", index) {
        val lines = text.lines()
        val w = (lines.maxOf { it.length } * size * 0.55f + size).coerceIn(size * 3, (sizeNow(index).width - x).coerceAtLeast(size * 3))
        val h = textHeight(text, w, size)
        val a = pdfPage(index).createAnnotation(PDFAnnotation.TYPE_FREE_TEXT)
        a.rect = Rect(x, y, x + w, y + h)
        a.contents = text
        a.setDefaultAppearance("Helv", size, rgb(color))
        a.borderWidth = 0f
        a.update()
    }

    /** A sticky note: an icon on the page that opens to show its words. */
    suspend fun addNote(index: Int, x: Float, y: Float, text: String, color: Int) = edit("Note", index) {
        val a = pdfPage(index).createAnnotation(PDFAnnotation.TYPE_TEXT)
        a.rect = Rect(x, y, x + 20f, y + 20f)
        a.contents = text
        a.icon = "Comment"
        a.color = rgb(color)
        a.update()
    }

    suspend fun setMarkNote(index: Int, markIndex: Int, text: String) = edit("Edit note", index) {
        val a = pdfPage(index).annotations?.getOrNull(markIndex) ?: return@edit
        a.contents = text
        a.update()
    }

    suspend fun setMarkColor(index: Int, markIndex: Int, color: Int) = edit("Colour", index) {
        val a = pdfPage(index).annotations?.getOrNull(markIndex) ?: return@edit
        if (a.type == PDFAnnotation.TYPE_FREE_TEXT) {
            // A text box's words take their colour from its default appearance.
            val da = runCatching { a.defaultAppearance }.getOrNull()
            a.setDefaultAppearance(da?.font ?: "Helv", da?.size?.takeIf { it > 0f } ?: 12f, rgb(color))
        } else {
            a.color = rgb(color)
        }
        a.update()
    }

    /** Changes a text box's words, size and colour. The box keeps its width and grows or shrinks to fit. */
    suspend fun editTextBox(index: Int, markIndex: Int, text: String, size: Float, color: Int) = edit("Edit text", index) {
        val a = pdfPage(index).annotations?.getOrNull(markIndex) ?: return@edit
        if (a.type != PDFAnnotation.TYPE_FREE_TEXT) return@edit
        val da = runCatching { a.defaultAppearance }.getOrNull()
        a.contents = text
        a.setDefaultAppearance(da?.font ?: "Helv", size, rgb(color))
        val r = a.rect
        val w = (r.x1 - r.x0).coerceAtLeast(size * 3)
        a.rect = Rect(r.x0, r.y0, r.x0 + w, r.y0 + textHeight(text, w, size))
        a.update()
    }

    /**
     * Moves or resizes a mark so that its outline becomes [to]. Everything inside is mapped from the
     * old outline to the new one: the points of a drawing, the ends of a line, a box's corners.
     */
    suspend fun reshapeMark(index: Int, markIndex: Int, to: Box) = edit("Move", index) {
        val a = pdfPage(index).annotations?.getOrNull(markIndex) ?: return@edit
        val b = a.bounds
        val fw = (b.x1 - b.x0).coerceAtLeast(0.01f)
        val fh = (b.y1 - b.y0).coerceAtLeast(0.01f)
        val sx = to.width / fw
        val sy = to.height / fh
        fun mx(x: Float) = to.x0 + (x - b.x0) * sx
        fun my(y: Float) = to.y0 + (y - b.y0) * sy
        when (a.type) {
            PDFAnnotation.TYPE_INK -> {
                val ink = a.inkList ?: return@edit
                a.setInkList(ink.map { s -> s.map { Point(mx(it.x), my(it.y)) }.toTypedArray() }.toTypedArray())
            }
            PDFAnnotation.TYPE_LINE -> {
                val l = a.line ?: return@edit
                if (l.size >= 2) a.setLine(Point(mx(l[0].x), my(l[0].y)), Point(mx(l[1].x), my(l[1].y)))
            }
            PDFAnnotation.TYPE_TEXT -> {
                // A note keeps its icon size; it only moves.
                val r = a.rect
                val dx = to.x0 - b.x0
                val dy = to.y0 - b.y0
                a.rect = Rect(r.x0 + dx, r.y0 + dy, r.x1 + dx, r.y1 + dy)
            }
            else -> {
                if (!a.hasRect()) return@edit
                val r = a.rect
                a.rect = Rect(mx(r.x0), my(r.y0), mx(r.x1), my(r.y1))
            }
        }
        a.update()
    }

    /** Height a text box needs for [text] at [size] when it is [width] wide, by a generous estimate. */
    private fun textHeight(text: String, width: Float, size: Float): Float {
        val perLine = ((width - size * 0.4f) / (size * 0.52f)).coerceAtLeast(1f)
        val lines = text.lines().sumOf { l -> kotlin.math.ceil(l.length.coerceAtLeast(1) / perLine).toInt().coerceAtLeast(1) }
        return lines * size * 1.3f + size * 0.6f
    }

    suspend fun deleteMark(index: Int, markIndex: Int) = edit("Delete", index) {
        val p = pdfPage(index)
        val a = p.annotations?.getOrNull(markIndex) ?: return@edit
        p.deleteAnnotation(a)
    }

    /** Undo or redo one change. Pages are reloaded afterwards, since a step can touch any of them. */
    suspend fun undo(): Boolean = onDoc { step(true) }
    suspend fun redo(): Boolean = onDoc { step(false) }

    private fun step(back: Boolean): Boolean {
        val d = pdf ?: return false
        if (back && !d.canUndo()) return false
        if (!back && !d.canRedo()) return false
        if (back) d.undo() else d.redo()
        dropPages()
        refreshFlags()
        dirty = true
        return true
    }

    /** Makes every mark part of the page itself, so no app can hide, move or delete it. */
    suspend fun flatten(): Boolean = onDoc {
        val d = pdf ?: return@onDoc false
        d.beginOperation("Flatten")
        try { d.bake(true, false) } finally { d.endOperation() }
        dropPages()
        refreshFlags()
        dirty = true
        true
    }

    val canSaveIncrementally: Boolean get() = runCatching { pdf?.canBeSavedIncrementally() ?: false }.getOrDefault(false)

    /**
     * Writes the document to [path]. Incremental saving appends only the changes, so [path] must
     * already hold an exact copy of the original file; the caller makes that copy first.
     */
    suspend fun writeTo(path: String, incremental: Boolean) = onDoc {
        val d = pdf ?: error("Only PDF files can be saved.")
        d.save(path, if (incremental) "incremental" else "garbage=compact")
    }

    private fun refreshFlags() {
        val d = pdf ?: return
        canUndo = runCatching { d.canUndo() }.getOrDefault(false)
        canRedo = runCatching { d.canRedo() }.getOrDefault(false)
    }

    private fun dropPages() {
        textCache.values.forEach { runCatching { it.destroy() } }
        textCache.clear()
        pageCache.values.forEach { runCatching { it.destroy() } }
        pageCache.clear()
    }

    /** One undoable change on one page; the page's own drawing is refreshed when it is done. */
    private suspend fun edit(name: String, index: Int, block: () -> Unit): Boolean = onDoc {
        val d = pdf ?: return@onDoc false
        d.beginOperation(name)
        try {
            block()
            runCatching { pdfPage(index).update() }
            d.endOperation()
        } catch (t: Throwable) {
            runCatching { d.abandonOperation() }
            throw t
        }
        textCache.remove(index)?.let { runCatching { it.destroy() } }
        refreshFlags()
        dirty = true
        true
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

private val TEXT_MARKS = setOf(MarkKind.HIGHLIGHT, MarkKind.UNDERLINE, MarkKind.STRIKE, MarkKind.SQUIGGLY)

private fun rgb(color: Int): FloatArray = floatArrayOf(
    ((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f,
)

private fun argb(c: FloatArray?): Int {
    if (c == null || c.isEmpty()) return 0xFF000000.toInt()
    fun ch(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return when (c.size) {
        1 -> (0xFF shl 24) or (ch(c[0]) shl 16) or (ch(c[0]) shl 8) or ch(c[0])
        4 -> {
            val k = c[3]
            (0xFF shl 24) or (ch((1 - c[0]) * (1 - k)) shl 16) or (ch((1 - c[1]) * (1 - k)) shl 8) or ch((1 - c[2]) * (1 - k))
        }
        else -> (0xFF shl 24) or (ch(c[0]) shl 16) or (ch(c[1]) shl 8) or ch(c[2])
    }
}
