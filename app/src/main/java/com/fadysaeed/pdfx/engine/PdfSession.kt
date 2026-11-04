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
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PKCS7Signer
import com.artifex.mupdf.fitz.PKCS7Verifier
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.PDFWidget
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
    TEXT("Text box"), NOTE("Note"), STAMP("Stamp"), REDACT("To black out"), OTHER("Mark"),
}

/**
 * A mark on a page: where it is, what it is, its colour, and its words (a note, a text box's text,
 * or the text under a highlight). [size] is the text size of a text box, 0 for anything else.
 */
data class Mark(
    val page: Int, val index: Int, val kind: MarkKind, val box: Box, val color: Int, val note: String, val quoted: String,
    val size: Float = 0f, val opacity: Float = 1f,
    /** Stroke width of a drawing, line or shape. */
    val width: Float = 0f,
    /** The boxes a highlight covers, one per line, for telling which mark a finger is on. */
    val parts: List<Box> = emptyList(),
    /** The strokes of a drawing, or the two ends of a line, for the same. */
    val path: List<List<Pair<Float, Float>>> = emptyList(),
    /** How a text box looks; null for other marks. */
    val style: BoxStyle? = null,
) {
    /** How far a point is from the mark itself (0 when it is on it), in PDF points. */
    fun distanceTo(x: Float, y: Float): Float = when {
        path.isNotEmpty() -> {
            var best = Float.MAX_VALUE
            for (st in path) {
                if (st.size == 1) best = minOf(best, kotlin.math.hypot(x - st[0].first, y - st[0].second))
                for (k in 1 until st.size) best = minOf(best, segmentDistance(x, y, st[k - 1], st[k]))
            }
            (best - width / 2f).coerceAtLeast(0f)
        }
        parts.isNotEmpty() -> parts.minOf { boxDistance(it, x, y) }
        else -> boxDistance(box, x, y)
    }

    /** Marks that sit on the page by themselves can be moved; marks over words stay with the words. */
    val movable: Boolean get() = kind != MarkKind.OTHER && kind !in TEXT_MARKS
    /** Notes are a fixed-size icon; everything else movable can also be resized. */
    val resizable: Boolean get() = movable && kind != MarkKind.NOTE
}

internal fun boxDistance(b: Box, x: Float, y: Float): Float {
    val dx = maxOf(b.x0 - x, 0f, x - b.x1)
    val dy = maxOf(b.y0 - y, 0f, y - b.y1)
    return kotlin.math.hypot(dx, dy)
}

internal fun segmentDistance(x: Float, y: Float, a: Pair<Float, Float>, b: Pair<Float, Float>): Float {
    val vx = b.first - a.first
    val vy = b.second - a.second
    val len = vx * vx + vy * vy
    val t = if (len <= 0f) 0f else (((x - a.first) * vx + (y - a.second) * vy) / len).coerceIn(0f, 1f)
    return kotlin.math.hypot(x - (a.first + t * vx), y - (a.second + t * vy))
}

/** A paragraph of reflowed text. [level] is 0 for body text, 1 for a heading, 2 for a smaller heading. */
data class Para(
    val text: String,
    val level: Int = 0,
    /** Bold and italic runs inside the text. */
    val spans: List<Span> = emptyList(),
    /** A list item; [marker] is its bullet or number. */
    val list: Boolean = false,
    /** For a picture: where it sits on the page (the text is then empty). */
    val figure: Box? = null,
    val marker: String = "",
    /** For a table: what the page says it holds, read row by row (the text is then empty). */
    val table: TableModel? = null,
    /** For a figure the page draws: its caption and notes, shown under the picture of [figure]. */
    val figureInfo: FigureModel? = null,
)

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
    /** Reads the file as it is on disk, from the start: signatures are checked against these bytes. */
    private val opener: (() -> java.io.InputStream)?,
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

    /**
     * Every place [needle] appears on a page; each hit is one or more boxes (it may wrap a line).
     * Case, accents and Arabic marks are ignored, so «اكتئاب» also finds «إكتِئاب».
     */
    suspend fun search(index: Int, needle: String): List<List<Box>> = onDoc { findOnPage(index, needle).map { it.boxes } }

    /** Like [search], with the words around each hit for the list of results. */
    suspend fun searchHits(index: Int, needle: String): List<Hit> = onDoc { findOnPage(index, needle) }

    /** A page's characters in reading order (lines joined by spaces), each with its outline, ready to search. */
    private class PageChars(val text: String, val quads: Array<Quad?>, val line: IntArray, val folded: Folded)

    private val charsCache = object : LinkedHashMap<Int, PageChars>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PageChars>) = size > 4
    }

    private fun pageChars(index: Int): PageChars = charsCache.getOrPut(index) {
        val sb = StringBuilder()
        val quads = ArrayList<Quad?>()
        val lines = ArrayList<Int>()
        var n = 0
        runCatching { stext(index).blocks }.getOrNull()?.forEach { b ->
            b.lines?.forEach { l ->
                val cs = l.chars ?: return@forEach
                if (cs.isEmpty()) return@forEach
                cs.forEach { c ->
                    val units = Character.toChars(c.c)
                    units.forEach { u -> sb.append(u); quads += c.quad; lines += n }
                }
                sb.append(' '); quads += null; lines += n
                n++
            }
        }
        val text = sb.toString()
        PageChars(text, quads.toTypedArray(), lines.toIntArray(), fold(text))
    }

    private fun findOnPage(index: Int, needle: String): List<Hit> {
        val q = fold(needle).text.trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return emptyList()
        val pc = pageChars(index)
        val f = pc.folded
        val out = ArrayList<Hit>()
        var from = 0
        while (out.size < 2000) {
            val at = f.text.indexOf(q, from)
            if (at < 0) break
            from = at + q.length
            val start = f.map[at]
            val end = f.map[at + q.length - 1]
            // One box per line the hit covers.
            val boxes = (start..end).filter { pc.quads[it] != null }.groupBy { pc.line[it] }.values.map { idx ->
                val rs = idx.map { pc.quads[it]!!.toRect() }
                Box(rs.minOf { it.x0 }, rs.minOf { it.y0 }, rs.maxOf { it.x1 }, rs.maxOf { it.y1 })
            }
            if (boxes.isEmpty()) continue
            val ctxStart = (start - 90).coerceAtLeast(0)
            val ctxEnd = (end + 91).coerceAtMost(pc.text.length)
            val context = pc.text.substring(ctxStart, ctxEnd)
            out += snippet(context, needle, start - ctxStart, boxes)
        }
        return out
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

    /** Like [select], with both ends moved out to whole words, so a selection never stops mid-word. */
    suspend fun selectWords(index: Int, ax: Float, ay: Float, bx: Float, by: Float): Selection? = onDoc {
        val st = stext(index)
        val a = Point(ax, ay)
        val b = Point(bx, by)
        runCatching { st.snapSelection(a, b, StructuredText.SELECT_WORDS) }
        selectionNow(index, st, a.x, a.y, b.x, b.y) ?: selectionNow(index, st, ax, ay, bx, by)
    }

    private fun selectionNow(index: Int, st: StructuredText, ax: Float, ay: Float, bx: Float, by: Float): Selection? {
        val a = Point(ax, ay)
        val b = Point(bx, by)
        val quads = st.highlight(a, b) ?: return null
        if (quads.isEmpty()) return null
        val text = st.copy(a, b) ?: ""
        return Selection(index, ax, ay, bx, by, quads.map { it.box() }, text)
    }

    private val rawCache = object : LinkedHashMap<Int, List<Para>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<Para>>) = size > 8
    }
    private val bandCache = HashMap<Int, Set<String>>()

    /** The lines in a page's top and bottom bands, numbers blanked. */
    private fun bandLines(index: Int): Set<String> = bandCache.getOrPut(index) {
        val h = sizeNow(index).height
        val out = HashSet<String>()
        runCatching { stext(index).blocks }.getOrNull()?.forEach { b ->
            val r = b.bbox ?: return@forEach
            if (r.y1 <= h * 0.085f || r.y0 >= h * 0.915f) {
                val t = b.lines?.joinToString(" ") { l -> buildString { l.chars?.forEach { appendCodePoint(it.c) } }.trim() }.orEmpty()
                if (t.isNotBlank() && t.length <= 90) out += bandKey(t)
            }
        }
        out
    }

    private fun rawParas(index: Int): List<Para> = rawCache.getOrPut(index) {
        val w = FlowWalker()
        val st = page(index).toStructuredText("preserve-images")
        try { st.walk(w) } finally { runCatching { st.destroy() } }
        val near = ((index - 2)..(index + 2)).filter { it != index && it in 0 until pageCount }.flatMap { bandLines(it) }.toSet()
        val parts = partsOn(index)
        buildParas(placeTables(w.blocks, parts.tables, parts.figures), sizeNow(index).height, near)
    }

    private val tableCache = object : LinkedHashMap<Int, PageParts>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PageParts>) = size > 24
    }

    /** The tables and figures on a page, found strictly (see [TableFinder]); none when anything goes wrong. */
    private fun partsOn(index: Int): PageParts = tableCache.getOrPut(index) {
        runCatching { TableFinder.read(index, tableInput(page(index))) }.getOrDefault(PageParts(emptyList(), emptyList()))
    }

    /** The tables on a page, as the reflowed view shows them. */
    suspend fun tables(index: Int): List<TableModel> = onDoc { partsOn(index).tables }

    /** The figures on a page, as the reflowed view shows them. */
    suspend fun figures(index: Int): List<FigureModel> = onDoc { partsOn(index).figures }

    /**
     * A page's text as paragraphs, for reading reflowed to the screen width: bold and italic kept,
     * headings found, lists split out, pictures in place, running heads and page numbers left out,
     * and a paragraph that runs over the page break joined back together (on the page it starts on).
     */
    suspend fun paragraphs(index: Int): List<Para> = onDoc {
        var list = rawParas(index)
        if (index > 0 && runsOn(rawParas(index - 1).lastOrNull(), list.firstOrNull())) list = list.drop(1)
        if (index + 1 < pageCount && list.isNotEmpty()) {
            val next = rawParas(index + 1).firstOrNull()
            if (next != null && runsOn(list.last(), next)) list = list.dropLast(1) + joinParas(list.last(), next)
        }
        list
    }

    // ------------------------------------------------------------------ forms

    /** True when the file has form fields to fill. */
    suspend fun hasForm(): Boolean = onDoc {
        val d = pdf ?: return@onDoc false
        runCatching { d.trailer.get("Root").get("AcroForm").get("Fields").size() > 0 }.getOrDefault(false)
    }

    private fun widgets(index: Int): Array<PDFWidget> = runCatching { pdfPage(index).widgets }.getOrNull() ?: emptyArray()

    private fun fieldOf(index: Int, i: Int, w: PDFWidget): Field {
        val r = w.bounds
        val kind = when (w.fieldType) {
            PDFWidget.TYPE_TEXT -> FieldKind.TEXT
            PDFWidget.TYPE_CHECKBOX -> FieldKind.CHECKBOX
            PDFWidget.TYPE_RADIOBUTTON -> FieldKind.RADIO
            PDFWidget.TYPE_COMBOBOX, PDFWidget.TYPE_LISTBOX -> FieldKind.CHOICE
            PDFWidget.TYPE_SIGNATURE -> FieldKind.SIGNATURE
            else -> FieldKind.OTHER
        }
        val name = runCatching { w.name ?: "" }.getOrDefault("")
        val value = runCatching { w.value ?: "" }.getOrDefault("")
        // A box or button is on when its appearance state is anything but Off.
        val on = (kind == FieldKind.CHECKBOX || kind == FieldKind.RADIO) && runCatching {
            val st = w.getObject().get("AS")?.asName()
            st != null && st != "Off" && st.isNotEmpty()
        }.getOrDefault(false)
        val js = if (kind == FieldKind.TEXT) formatScript(w) else null
        val pattern = dateFormatFromScript(js)
        val isDate = kind == FieldKind.TEXT && (pattern != null || runCatching { w.textFormat == PDFWidget.TX_FORMAT_DATE }.getOrDefault(false) ||
            Regex("date|dob|\\bd\\.?o\\.?b\\b|تاريخ", RegexOption.IGNORE_CASE).containsMatchIn(name))
        return Field(
            page = index, index = i, kind = kind, name = name, value = value, box = Box(r.x0, r.y0, r.x1, r.y1),
            readOnly = runCatching { w.isReadOnly }.getOrDefault(false),
            multiline = kind == FieldKind.TEXT && runCatching { w.isMultiline }.getOrDefault(false),
            options = if (kind == FieldKind.CHOICE) runCatching { w.options?.toList() ?: emptyList() }.getOrDefault(emptyList()) else emptyList(),
            on = on,
            isDate = isDate, datePattern = pattern,
            maxLen = if (kind == FieldKind.TEXT) runCatching { w.maxLen }.getOrDefault(0) else 0,
        )
    }

    /** A field's format script, from the widget or the field above it; it may be text or a stream. */
    private fun formatScript(w: PDFWidget): String? = runCatching {
        var o: com.artifex.mupdf.fitz.PDFObject? = w.getObject()
        repeat(5) {
            val cur = o ?: return@runCatching null
            val js = cur.get("AA")?.get("F")?.get("JS")
            if (js != null) {
                val text = if (js.isStream) String(js.readStream() ?: ByteArray(0)) else js.asString()
                if (!text.isNullOrBlank()) return@runCatching text
            }
            o = cur.get("Parent")
        }
        null
    }.getOrNull()

    /** The form fields on a page, in the order the page holds them. */
    suspend fun fields(index: Int): List<Field> = onDoc {
        if (pdf == null) return@onDoc emptyList()
        widgets(index).mapIndexed { i, w -> fieldOf(index, i, w) }.filter { it.kind != FieldKind.OTHER }
    }

    /** Every fillable field in the file, page by page, top to bottom: the order Next follows. */
    suspend fun allFields(): List<Field> = onDoc {
        if (pdf == null) return@onDoc emptyList()
        (0 until pageCount).flatMap { p ->
            widgets(p).mapIndexed { i, w -> fieldOf(p, i, w) }
                .filter { it.kind != FieldKind.OTHER && !it.readOnly }
                .sortedWith(compareBy({ (it.box.y0 / 6f).toInt() }, { it.box.x0 }))
        }
    }

    suspend fun setFieldText(index: Int, fieldIndex: Int, text: String) = edit("Fill", index) {
        val w = widgets(index).getOrNull(fieldIndex) ?: return@edit
        w.setTextValue(text)
        w.update()
    }

    /** Ticks or unticks a checkbox; picks a radio button (and clears the others in its group). */
    suspend fun toggleField(index: Int, fieldIndex: Int) = edit("Tick", index) {
        val w = widgets(index).getOrNull(fieldIndex) ?: return@edit
        w.toggle()
        w.update()
    }

    suspend fun setFieldChoice(index: Int, fieldIndex: Int, value: String) = edit("Choose", index) {
        val w = widgets(index).getOrNull(fieldIndex) ?: return@edit
        w.setChoiceValue(value)
        w.update()
    }

    /** The run of words at ([x], [y]) on a page, to edit; null where there are none. */
    suspend fun textRunAt(index: Int, x: Float, y: Float): TextRun? = onDoc {
        if (pdf == null) null else runCatching { TextEdit.runAt(pdfPage(index), index, x, y) }.getOrNull()
    }

    /** Writes [text] in place of [run] (see [TextEdit]), as one step that undo takes back. */
    suspend fun replaceText(index: Int, run: TextRun, text: String): Boolean {
        val d = pdf ?: return false
        val ok = edit("Edit text", index) { TextEdit.replace(d, pdfPage(index), run, text) }
        // The old words are taken out as a blacking out does, so the file is written whole.
        if (ok) rewriteOnly = true
        return ok
    }

    /** A picture placed on the page in [box]: it can be moved and resized like any mark, and flattened into the page. */
    suspend fun addPicture(index: Int, box: Box, imagePath: String) = edit("Picture", index) {
        val a = pdfPage(index).createAnnotation(PDFAnnotation.TYPE_STAMP)
        a.rect = Rect(box.x0, box.y0, box.x1, box.y1)
        val img = com.artifex.mupdf.fitz.Image(imagePath)
        try {
            runCatching { a.setStampImage(img) }.getOrElse { a.setAppearance(img) }
        } finally {
            runCatching { img.destroy() }
        }
        a.update()
    }

    /** A picture (a photographed signature) placed on the page in [box]. */
    suspend fun addImageStamp(index: Int, box: Box, imagePath: String) = edit("Signature", index) {
        val a = pdfPage(index).createAnnotation(PDFAnnotation.TYPE_STAMP)
        a.rect = Rect(box.x0, box.y0, box.x1, box.y1)
        val img = com.artifex.mupdf.fitz.Image(imagePath)
        try {
            runCatching { a.setStampImage(img) }.getOrElse { a.setAppearance(img) }
        } finally {
            runCatching { img.destroy() }
        }
        a.update()
    }

    // ------------------------------------------------------------------ digital signatures

    /** Signers in use for signatures not yet written; MuPDF calls them when the file is saved. */
    private val pendingSigners = mutableListOf<P12Signer>()

    private fun inherited(o: PDFObject?, key: String): PDFObject? {
        var cur = o
        repeat(12) {
            val c = cur ?: return null
            if (c.isNull) return null
            val v = c.get(key)
            if (v != null && !v.isNull) return v
            cur = c.get("Parent")
        }
        return null
    }

    /** Every signed signature in the file, checked: unchanged, who signed, and whether the phone trusts them. */
    suspend fun signatures(): List<DocSignature> = onDoc {
        val d = pdf ?: return@onDoc emptyList()
        val any = runCatching { d.trailer.get("Root").get("AcroForm").get("Fields").size() > 0 }.getOrDefault(false)
        if (!any) return@onDoc emptyList()
        val verifier = CmsVerifier()
        val fileSize = runCatching { opener?.invoke()?.use { inp -> var n = 0L; val buf = ByteArray(1 shl 16); while (true) { val k = inp.read(buf); if (k < 0) break; n += k }; n } }.getOrNull()
        try {
            (0 until pageCount).flatMap { p ->
                widgets(p).filter { w -> runCatching { w.fieldType == PDFWidget.TYPE_SIGNATURE && w.isSigned }.getOrDefault(false) }
                    .map { w -> describeSignature(p, w, verifier, fileSize) }
            }
        } finally {
            runCatching { verifier.destroy() }
        }
    }

    private fun describeSignature(page: Int, w: PDFWidget, verifier: CmsVerifier, fileSize: Long?): DocSignature {
        val v = inherited(w.getObject(), "V")
        val contents = runCatching { v?.get("Contents")?.asByteString() }.getOrNull() ?: ByteArray(0)
        val (cert, time, chain) = readSignature(contents)
        val range = runCatching {
            val br = v!!.get("ByteRange")
            LongArray(br.size()) { br.get(it).asInteger().toLong() }
        }.getOrNull()
        // The signed bytes are checked straight from the file: MuPDF's own stream for this reads empty on Android.
        val (intact, problem) = if (range != null && opener != null) checkRanges(opener, range, contents) else false to "no byte range"
        val trust = runCatching { verifier.checkCertificate(contents) }.getOrDefault(PKCS7Verifier.PKCS7VerifierUnknown)
        val changed = if (range != null && range.size == 4 && fileSize != null) range[2] + range[3] < fileSize
            else runCatching { w.incrementalChangeSinceSigning() }.getOrDefault(false)
        val locked = runCatching { w.validateSignature() != 0 }.getOrDefault(false)
        val signer = cert?.let { personOf(it) } ?: Person(runCatching { v?.get("Name")?.asString() }.getOrNull() ?: "")
        val issuer = cert?.let { runCatching { personOf(org.bouncycastle.cert.X509CertificateHolder(it.encoded).issuer) }.getOrNull() } ?: Person("")
        val check = SignatureCheck(
            signer = signer, issuer = issuer,
            time = time ?: pdfDate(runCatching { v?.get("M")?.asString() }.getOrNull()),
            intact = intact,
            trusted = trust == PKCS7Verifier.PKCS7VerifierOK,
            selfSigned = trust == PKCS7Verifier.PKCS7VerifierSelfSigned || (cert != null && isSelfSigned(cert)),
            changedAfter = changed,
            validFrom = cert?.notBefore?.time ?: 0, validTo = cert?.notAfter?.time ?: 0,
            lockedChanged = locked,
            problem = problem,
        )
        val r = w.bounds
        return DocSignature(
            page = page, box = Box(r.x0, r.y0, r.x1, r.y1),
            field = runCatching { w.name ?: "" }.getOrDefault(""),
            reason = runCatching { v?.get("Reason")?.asString() }.getOrNull() ?: "",
            location = runCatching { v?.get("Location")?.asString() }.getOrNull() ?: "",
            check = check,
        )
    }

    /**
     * Signs the file digitally: in the empty signature field [fieldIndex] on the page, or in a new
     * one at [box]. The signature shows [imagePath] (a signature picture) or the signer's name, with
     * the date. It is completed when the file is saved, so save straight after.
     */
    suspend fun signDigitally(index: Int, box: Box?, fieldIndex: Int?, signer: P12Signer, imagePath: String?, reason: String?, location: String?): Boolean = onDoc {
        val d = pdf ?: return@onDoc false
        // Read from the form itself, not page by page: loading every page here pushed the page
        // being signed out of the cache and freed it, and MuPDF then crashed signing it.
        val others = if (fieldIndex == null) runCatching { unsignedSignatureNames(d) }.getOrDefault(emptyList()) else emptyList()
        val page = pdfPage(index)
        val w = if (fieldIndex != null) {
            widgets(index).getOrNull(fieldIndex)?.takeIf { it.fieldType == PDFWidget.TYPE_SIGNATURE } ?: error("That signature field is gone.")
        } else {
            val b = box ?: error("Choose where the signature goes.")
            page.createSignature().also { it.rect = Rect(b.x0, b.y0, b.x1, b.y1) }
        }
        if (fieldIndex == null && others.isNotEmpty()) {
            // A new signature locks the form, except other signature fields still to be signed.
            runCatching {
                d.beginOperation("Signature lock")
                try {
                    val lock = d.newDictionary()
                    lock.put("Action", d.newName("Exclude"))
                    val names = d.newArray()
                    others.forEach { names.append(d.newString(it)) }
                    lock.put("Fields", names)
                    w.getObject().put("Lock", lock)
                    d.endOperation()
                } catch (t: Throwable) { runCatching { d.abandonOperation() }; throw t }
            }.onFailure { android.util.Log.w("PdfxSigning", "lock left as MuPDF set it", it) }
        }
        if (fieldIndex != null) runCatching { mergeIntoField(d, w) }.onFailure { android.util.Log.w("PdfxSigning", "field left as it was", it) }
        val img = imagePath?.let { runCatching { com.artifex.mupdf.fitz.Image(it) }.getOrNull() }
        try {
            var flags = PDFWidget.SIGNATURE_SHOW_LABELS or PDFWidget.SIGNATURE_SHOW_TEXT_NAME or PDFWidget.SIGNATURE_SHOW_DATE
            if (img == null) flags = flags or PDFWidget.SIGNATURE_SHOW_GRAPHIC_NAME
            w.sign(signer, flags, img, reason?.ifBlank { null }, location?.ifBlank { null })
        } finally {
            runCatching { img?.destroy() }
        }
        // A field drawn by a separate widget keeps its value on the field itself, where other
        // readers look; MuPDF puts it on the widget, so the field gets it too.
        pendingSigners += signer
        runCatching { page.update() }
        textCache.remove(index)?.let { runCatching { it.destroy() } }
        charsCache.remove(index)
        rawCache.clear()
        tableCache.clear()
        bandCache.remove(index)
        refreshFlags()
        dirty = true
        true
    }

    /** Full names of the signature fields not signed yet, read from the form's own tree. */
    private fun unsignedSignatureNames(d: PDFDocument): List<String> {
        val out = mutableListOf<String>()
        fun walk(o: PDFObject, prefix: String, depth: Int) {
            if (depth > 12 || o.isNull) return
            val t = o.get("T").takeIf { !it.isNull }?.asString()
            val name = listOfNotNull(prefix.ifEmpty { null }, t).joinToString(".")
            val kids = o.get("Kids")
            val fieldKids = !kids.isNull && (0 until kids.size()).any { !kids.get(it).get("T").isNull }
            if (fieldKids) for (i in 0 until kids.size()) walk(kids.get(i), name, depth + 1)
            else if (inherited(o, "FT")?.asName() == "Sig" && inherited(o, "V") == null && name.isNotEmpty()) out += name
        }
        val fields = d.trailer.get("Root").get("AcroForm").get("Fields")
        if (!fields.isNull) for (i in 0 until fields.size()) walk(fields.get(i), "", 0)
        return out
    }

    /**
     * Some files draw a signature field with a separate widget under it. MuPDF signs the widget, but
     * other readers look for the signature on the field; so, before signing, the field and its only
     * widget become one, as most PDF software makes them. Nothing else in the file changes.
     */
    private fun mergeIntoField(d: PDFDocument, w: PDFWidget) {
        val o = w.getObject()
        val parent = o.get("Parent")
        if (parent == null || parent.isNull || !o.get("T").isNull) return
        if (parent.get("FT").asName() != "Sig" || !parent.get("Parent").isNull) return
        if (parent.get("Kids").size() != 1) return
        val fields = d.trailer.get("Root").get("AcroForm").get("Fields")
        val num = parent.asIndirect()
        if (num <= 0 || o.asIndirect() <= 0) return
        val at = (0 until fields.size()).firstOrNull { fields.get(it).asIndirect() == num } ?: return
        d.beginOperation("Signature field")
        try {
            for (k in listOf("T", "TU", "TM", "FT", "Ff", "DA", "Q", "Lock", "SV")) {
                val v = parent.get(k)
                if (v != null && !v.isNull && o.get(k).isNull) o.put(k, v)
            }
            o.delete("Parent")
            fields.put(at, o)
            d.endOperation()
        } catch (t: Throwable) {
            runCatching { d.abandonOperation() }
            throw t
        }
    }

    /** True when the file holds digital signatures: changes must then be appended, never rewritten. */
    suspend fun hasSignatures(): Boolean = onDoc { runCatching { (pdf?.countSignatures() ?: 0) > 0 }.getOrDefault(false) }

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
        PDFAnnotation.TYPE_REDACT -> MarkKind.REDACT
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
            val opacity = runCatching { a.opacity }.getOrDefault(1f)
            val parts = if (kind in TEXT_MARKS) runCatching { (0 until a.quadPointCount).map { a.getQuadPoint(it).box() } }.getOrDefault(emptyList()) else emptyList()
            val path = when (t) {
                PDFAnnotation.TYPE_INK -> runCatching { a.inkList?.map { st -> st.map { it.x to it.y } } ?: emptyList() }.getOrDefault(emptyList())
                PDFAnnotation.TYPE_LINE -> runCatching { a.line?.takeIf { it.size >= 2 }?.let { listOf(listOf(it[0].x to it[0].y, it[1].x to it[1].y)) } ?: emptyList() }.getOrDefault(emptyList())
                else -> emptyList()
            }
            val width = if (kind in setOf(MarkKind.INK, MarkKind.SQUARE, MarkKind.CIRCLE, MarkKind.LINE, MarkKind.ARROW)) runCatching { a.borderWidth }.getOrDefault(1f) else 0f
            val contents = runCatching { a.contents ?: "" }.getOrDefault("")
            val style = if (kind == MarkKind.TEXT) runCatching { styleOf(a, contents, da?.size ?: 12f, color) }.getOrNull() else null
            out += Mark(index, i, kind, Box(r.x0, r.y0, r.x1, r.y1), style?.color ?: color, contents, quoted, style?.size ?: da?.size ?: 0f, opacity, width, parts, path, style)
        }
        return out
    }

    suspend fun marks(index: Int): List<Mark> = onDoc { marksNow(index) }

    /**
     * The highlights, notes and text boxes in the file, for the library's page of them: only pages
     * that have marks at all are opened, so a long book without any costs next to nothing.
     */
    suspend fun marksToKeep(): List<Mark> = onDoc {
        val d = pdf ?: return@onDoc emptyList()
        val keep = setOf(MarkKind.HIGHLIGHT, MarkKind.UNDERLINE, MarkKind.STRIKE, MarkKind.SQUIGGLY, MarkKind.NOTE, MarkKind.TEXT)
        (0 until pageCount).filter { i -> runCatching { d.findPage(i).get("Annots").let { !it.isNull && it.size() > 0 } }.getOrDefault(false) }
            .flatMap { i -> runCatching { marksNow(i) }.getOrDefault(emptyList()) }
            .filter { it.kind in keep && (it.quoted.isNotBlank() || it.note.isNotBlank()) }
    }

    /** Every mark in the file, page by page, for the list of marks. */
    suspend fun allMarks(): List<Mark> = onDoc { (0 until pageCount).flatMap { runCatching { marksNow(it) }.getOrDefault(emptyList()) } }

    /** Highlight, underline, strike or squiggle over text, given the boxes of the selected words. */
    suspend fun addTextMark(index: Int, kind: MarkKind, boxes: List<Box>, color: Int, opacity: Float = 1f) = edit("Mark text", index) {
        val t = when (kind) {
            MarkKind.UNDERLINE -> PDFAnnotation.TYPE_UNDERLINE
            MarkKind.STRIKE -> PDFAnnotation.TYPE_STRIKE_OUT
            MarkKind.SQUIGGLY -> PDFAnnotation.TYPE_SQUIGGLY
            else -> PDFAnnotation.TYPE_HIGHLIGHT
        }
        val a = pdfPage(index).createAnnotation(t)
        a.setQuadPoints(boxes.map { Quad(Rect(it.x0, it.y0, it.x1, it.y1)) }.toTypedArray())
        a.color = rgb(color)
        if (opacity < 1f) a.opacity = opacity.coerceIn(0.1f, 1f)
        a.update()
    }

    /**
     * Marks words ([words], one box per line piece) or an area to be blacked out. Nothing goes until
     * [applyRedactions]; till then it is a red outline that can be moved, removed or undone.
     */
    suspend fun addRedaction(index: Int, boxes: List<Box>, words: Boolean) = edit("Mark to black out", index) {
        if (boxes.isEmpty()) return@edit
        val a = pdfPage(index).createAnnotation(PDFAnnotation.TYPE_REDACT)
        if (words) a.setQuadPoints(boxes.map { Quad(Rect(it.x0, it.y0, it.x1, it.y1)) }.toTypedArray())
        else boxes[0].let { a.rect = Rect(minOf(it.x0, it.x1), minOf(it.y0, it.y1), maxOf(it.x0, it.x1), maxOf(it.y0, it.y1)) }
        a.update()
    }

    /** Marks every place [needle] is found, on every page, as one step. Returns how many places and on how many pages. */
    suspend fun markAllToRedact(needle: String): Pair<Int, Int> = onDoc {
        val d = pdf ?: return@onDoc 0 to 0
        var places = 0
        var pages = 0
        d.beginOperation("Mark to black out")
        try {
            for (i in 0 until pageCount) {
                val hits = findOnPage(i, needle)
                if (hits.isEmpty()) continue
                pages++
                val p = pdfPage(i)
                for (h in hits) {
                    val a = p.createAnnotation(PDFAnnotation.TYPE_REDACT)
                    a.setQuadPoints(h.boxes.map { Quad(Rect(it.x0, it.y0, it.x1, it.y1)) }.toTypedArray())
                    a.update()
                    places++
                }
            }
            d.endOperation()
        } catch (t: Throwable) {
            runCatching { d.abandonOperation() }
            throw t
        }
        if (places > 0) { dropPages(); refreshFlags(); dirty = true }
        places to pages
    }

    /** How many places are marked to be blacked out, in the whole file. */
    suspend fun redactionCount(): Int = onDoc {
        if (pdf == null) return@onDoc 0
        val d = pdf!!
        // Straight from the page objects, so a long book is not loaded page by page to count.
        (0 until pageCount).sumOf { i ->
            runCatching {
                val annots = d.findPage(i).get("Annots")
                (0 until annots.size()).count { k -> annots.get(k).get("Subtype").asName() == "Redact" }
            }.getOrDefault(0)
        }
    }

    /** Set once something is blacked out: the file must then be written afresh, never appended to. */
    @Volatile private var rewriteOnly = false

    /**
     * Blacks out every marked place for good: the words, pictures (their pixels) and drawings under
     * each mark are taken out of the page itself and a black box is drawn in their place. Marks
     * (highlights, notes, drawings) over those places go too, and form fields there are emptied, so
     * nothing of what was there stays in the file. Returns how many places were blacked out.
     */
    suspend fun applyRedactions(): Int = onDoc {
        val d = pdf ?: return@onDoc 0
        var n = 0
        d.beginOperation("Black out")
        try {
            for (i in 0 until pageCount) {
                val p = pdfPage(i)
                val redacts = p.annotations?.filter { it.type == PDFAnnotation.TYPE_REDACT }.orEmpty()
                if (redacts.isEmpty()) continue
                val areas = redacts.flatMap { a ->
                    val q = runCatching { a.quadPoints }.getOrNull()
                    if (q != null && q.isNotEmpty()) q.map { it.toRect() } else listOf(a.rect)
                }
                fun hit(r: Rect) = areas.any { it.x0 < r.x1 && r.x0 < it.x1 && it.y0 < r.y1 && r.y0 < it.y1 }
                // Other marks over a blacked-out place would still say what was there.
                p.annotations?.filter { it.type != PDFAnnotation.TYPE_REDACT && it.type != PDFAnnotation.TYPE_LINK && hit(it.bounds) }
                    ?.forEach { p.deleteAnnotation(it) }
                p.widgets?.filter { hit(it.bounds) }?.forEach { w ->
                    runCatching { if (w.fieldType == com.artifex.mupdf.fitz.PDFWidget.TYPE_TEXT) { w.setTextValue(""); w.update() } }
                }
                n += redacts.size
                p.applyRedactions(true, PDFPage.REDACT_IMAGE_PIXELS, PDFPage.REDACT_LINE_ART_REMOVE_IF_COVERED, PDFPage.REDACT_TEXT_REMOVE)
            }
            d.endOperation()
        } catch (t: Throwable) {
            runCatching { d.abandonOperation() }
            throw t
        }
        if (n > 0) {
            rewriteOnly = true
            dropPages()
            sizes.clear()
            refreshFlags()
            dirty = true
        }
        n
    }

    /** Takes out the details the file carries about itself (see Secure.scrub), as one step. */
    suspend fun scrubDetails() = onDoc {
        val d = pdf ?: return@onDoc
        d.beginOperation("Remove hidden details")
        try {
            Secure.scrub(d)
            d.endOperation()
        } catch (t: Throwable) {
            runCatching { d.abandonOperation() }
            throw t
        }
        refreshFlags()
        dirty = true
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
    suspend fun addTextBox(index: Int, x: Float, y: Float, text: String, color: Int, size: Float) =
        addTextBox(index, x, y, text, BoxStyle(size = size, color = color))

    /** Typed words placed on the page, in [style]. A right-to-left box grows leftwards from the tap. */
    suspend fun addTextBox(index: Int, x: Float, y: Float, text: String, style: BoxStyle) = edit("Text", index) {
        val size = style.size
        val lines = text.lines()
        val pageW = sizeNow(index).width
        val w = (lines.maxOf { it.length } * size * (if (style.font == BoxFont.MONO) 0.62f else 0.55f) + size * 1.4f).coerceIn(size * 3, pageW * 0.9f)
        val rtl = style.rtl(lines.firstOrNull { it.isNotBlank() } ?: "")
        val x0 = (if (rtl) x - w else x).coerceIn(0f, (pageW - w).coerceAtLeast(0f))
        val a = pdfPage(index).createAnnotation(PDFAnnotation.TYPE_FREE_TEXT)
        a.rect = Rect(x0, y, x0 + w, y + textHeight(text, w, style))
        applyText(a, text, style)
        a.update()
    }

    /** Writes words and style into a text box, for this app and for other PDF apps alike. */
    private fun applyText(a: PDFAnnotation, text: String, style: BoxStyle) {
        a.contents = text
        a.setDefaultAppearance(style.font.da, style.size, rgb(style.color))
        styleErrors.clear()
        runCatching { a.quadding = style.quadding(text) }.onFailure { styleErrors += "Q: $it" }
        // The style rides on the rich text's body; this engine build cannot set a separate default style.
        runCatching { a.setRichContents(text, style.rich(text)) }.onFailure { styleErrors += "RC: $it" }
        // A text box's /C colour is its background; its border takes the colour of the words.
        runCatching { a.color = style.fill?.let { rgb(it) } ?: FloatArray(0) }.onFailure { styleErrors += "C: $it" }
        a.borderWidth = if (style.border) 0.8f else 0f
    }

    private val styleErrors = ArrayList<String>()

    /** What the file holds for a text box's style, for checking it in tests. */
    suspend fun textBoxRaw(index: Int, markIndex: Int): String = onDoc {
        val a = pdfPage(index).annotations?.getOrNull(markIndex) ?: return@onDoc "no mark"
        val da = runCatching { a.defaultAppearance }.getOrNull()
        "DA=${da?.font}/${da?.size} hasDS=${runCatching { a.hasRichDefaults() }.getOrNull()} DS=${runCatching { a.richDefaults }.getOrNull()} " +
            "hasRC=${runCatching { a.hasRichContents() }.getOrNull()} RC=${runCatching { a.richContents }.getOrNull()?.take(200)} errors=$styleErrors"
    }

    private fun styleOf(a: PDFAnnotation, text: String, size: Float, color: Int): BoxStyle {
        val rc = runCatching { if (a.hasRichContents()) a.richContents else null }.getOrNull()
        val ds = rc?.let { Regex("<body[^>]*\\bstyle=\"([^\"]*)\"").find(it)?.groupValues?.get(1) }
        val fill = runCatching { a.color?.takeIf { it.isNotEmpty() }?.let { argb(it) } }.getOrNull()
        val border = runCatching { a.borderWidth > 0.05f }.getOrDefault(false)
        val rtl = rc?.let { r -> if (r.contains("dir=\"rtl\"")) true else if (r.contains("dir=\"ltr\"")) false else null }
        val parsed = BoxStyle.parse(ds, text, size, color, fill, border, rtl)
        // Without a stored style, the font still shows in the plain appearance string.
        if (ds.isNullOrBlank()) {
            val f = runCatching { a.defaultAppearance?.font }.getOrNull()
            return parsed.copy(font = BoxFont.entries.firstOrNull { it.da == f } ?: BoxFont.SANS)
        }
        return parsed
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

    suspend fun setMarkColor(index: Int, markIndex: Int, color: Int) = setMarksColor(index, listOf(markIndex), color)

    /** One colour for several marks on a page, as one step to undo. */
    suspend fun setMarksColor(index: Int, markIndexes: List<Int>, color: Int) = edit("Colour", index) {
        val annots = pdfPage(index).annotations ?: return@edit
        markIndexes.forEach { mi -> annots.getOrNull(mi)?.let { colorNow(it, color) } }
    }

    /** One stroke width for several drawings, lines or shapes, as one step to undo. */
    suspend fun setMarksWidth(index: Int, markIndexes: List<Int>, width: Float) = edit("Width", index) {
        val annots = pdfPage(index).annotations ?: return@edit
        markIndexes.forEach { mi ->
            val a = annots.getOrNull(mi) ?: return@forEach
            if (a.type in setOf(PDFAnnotation.TYPE_INK, PDFAnnotation.TYPE_SQUARE, PDFAnnotation.TYPE_CIRCLE, PDFAnnotation.TYPE_LINE)) {
                a.borderWidth = width
                a.update()
            }
        }
    }

    private fun colorNow(a: PDFAnnotation, color: Int) {
        if (a.type == PDFAnnotation.TYPE_FREE_TEXT) {
            // A text box's words take their colour from its style.
            val text = runCatching { a.contents ?: "" }.getOrDefault("")
            val da = runCatching { a.defaultAppearance }.getOrNull()
            val style = styleOf(a, text, da?.size?.takeIf { it > 0f } ?: 12f, argb(da?.color))
            applyText(a, text, style.copy(color = color))
        } else {
            a.color = rgb(color)
        }
        a.update()
    }

    /** How strong a mark looks: 1 is solid, lower lets more of the page show through. */
    suspend fun setMarkOpacity(index: Int, markIndex: Int, opacity: Float) = edit("Strength", index) {
        val a = pdfPage(index).annotations?.getOrNull(markIndex) ?: return@edit
        a.opacity = opacity.coerceIn(0.1f, 1f)
        a.update()
    }

    /** Changes a text box's words, size and colour, keeping the rest of its style. */
    suspend fun editTextBox(index: Int, markIndex: Int, text: String, size: Float, color: Int): Boolean {
        val now = marks(index).getOrNull(markIndex)?.style ?: BoxStyle()
        return editTextBox(index, markIndex, text, now.copy(size = size, color = color))
    }

    /** Changes a text box's words and style. The box keeps its width and grows or shrinks to fit. */
    suspend fun editTextBox(index: Int, markIndex: Int, text: String, style: BoxStyle) = edit("Edit text", index) {
        val a = pdfPage(index).annotations?.getOrNull(markIndex) ?: return@edit
        if (a.type != PDFAnnotation.TYPE_FREE_TEXT) return@edit
        val r = a.rect
        val w = (r.x1 - r.x0).coerceAtLeast(style.size * 3)
        a.rect = Rect(r.x0, r.y0, r.x0 + w, r.y0 + textHeight(text, w, style))
        applyText(a, text, style)
        a.update()
    }

    /**
     * Moves or resizes a mark so that its outline becomes [to]. Everything inside is mapped from the
     * old outline to the new one: the points of a drawing, the ends of a line, a box's corners.
     */
    suspend fun reshapeMark(index: Int, markIndex: Int, to: Box) = reshapeMarks(index, listOf(markIndex to to))

    /** Moves or resizes several marks on a page as one step to undo. */
    suspend fun reshapeMarks(index: Int, moves: List<Pair<Int, Box>>) = edit("Move", index) {
        val annots = pdfPage(index).annotations ?: return@edit
        moves.forEach { (mi, to) -> annots.getOrNull(mi)?.let { reshapeNow(it, to) } }
    }

    private fun reshapeNow(a: PDFAnnotation, to: Box) {
        val b = a.bounds
        val fw = (b.x1 - b.x0).coerceAtLeast(0.01f)
        val fh = (b.y1 - b.y0).coerceAtLeast(0.01f)
        val sx = to.width / fw
        val sy = to.height / fh
        fun mx(x: Float) = to.x0 + (x - b.x0) * sx
        fun my(y: Float) = to.y0 + (y - b.y0) * sy
        // Drawings and lines: map their points, keeping the margin the stroke adds round them.
        fun mapper(pts: List<Point>): (Point) -> Point {
            val ix0 = pts.minOf { it.x }; val ix1 = pts.maxOf { it.x }
            val iy0 = pts.minOf { it.y }; val iy1 = pts.maxOf { it.y }
            val tx0 = to.x0 + (ix0 - b.x0); val tx1 = to.x1 - (b.x1 - ix1)
            val ty0 = to.y0 + (iy0 - b.y0); val ty1 = to.y1 - (b.y1 - iy1)
            val kx = if (ix1 - ix0 > 0.01f) ((tx1 - tx0) / (ix1 - ix0)).coerceAtLeast(0.02f) else 1f
            val ky = if (iy1 - iy0 > 0.01f) ((ty1 - ty0) / (iy1 - iy0)).coerceAtLeast(0.02f) else 1f
            return { p -> Point(tx0 + (p.x - ix0) * kx, ty0 + (p.y - iy0) * ky) }
        }
        when (a.type) {
            PDFAnnotation.TYPE_INK -> {
                val ink = a.inkList ?: return
                val all = ink.flatMap { it.toList() }
                if (all.isEmpty()) return
                val m = mapper(all)
                a.setInkList(ink.map { st -> st.map(m).toTypedArray() }.toTypedArray())
            }
            PDFAnnotation.TYPE_LINE -> {
                val l = a.line ?: return
                if (l.size >= 2) { val m = mapper(l.toList()); a.setLine(m(l[0]), m(l[1])) }
            }
            PDFAnnotation.TYPE_TEXT -> {
                // A note keeps its icon size; it only moves.
                val r = a.rect
                val dx = to.x0 - b.x0
                val dy = to.y0 - b.y0
                a.rect = Rect(r.x0 + dx, r.y0 + dy, r.x1 + dx, r.y1 + dy)
            }
            else -> {
                if (!a.hasRect()) return
                val r = a.rect
                a.rect = Rect(mx(r.x0), my(r.y0), mx(r.x1), my(r.y1))
            }
        }
        a.update()
    }

    /** Height a text box needs for [text] in [style] when it is [width] wide, by a generous estimate. */
    private fun textHeight(text: String, width: Float, style: BoxStyle): Float {
        val size = style.size
        val charW = size * (if (style.font == BoxFont.MONO) 0.62f else if (style.bold) 0.56f else 0.52f)
        val perLine = ((width - size * 0.8f) / charW).coerceAtLeast(1f)
        val lines = text.lines().sumOf { l -> kotlin.math.ceil(l.length.coerceAtLeast(1) / perLine).toInt().coerceAtLeast(1) }
        val lh = maxOf(1.25f, BoxSpacings[style.spacing.coerceIn(0, 2)] + 0.1f)
        return lines * size * lh + size * 0.8f + (if (style.border) 2f else 0f)
    }

    suspend fun deleteMark(index: Int, markIndex: Int) = deleteMarks(index, listOf(markIndex))

    /** Removes several marks on a page as one step to undo. */
    suspend fun deleteMarks(index: Int, markIndexes: List<Int>) = edit("Delete", index) {
        val p = pdfPage(index)
        val annots = p.annotations ?: return@edit
        markIndexes.distinct().mapNotNull { annots.getOrNull(it) }.forEach { p.deleteAnnotation(it) }
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

    // ------------------------------------------------------------------ margins

    /**
     * Where a page's ink is: the smallest box holding everything that is not near-white, from a
     * small drawing of the page, in page points. Null for a page with nothing on it.
     */
    suspend fun contentBox(index: Int, widthPx: Int = 280): Box? {
        val size = pageSize(index)
        val bmp = render(index, widthPx)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val w = bmp.width
            val h = bmp.height
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)
            fun ink(c: Int): Boolean {
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                return r < 232 || g < 232 || b < 232
            }
            // A row or column counts once it holds two marked pixels, so a speck of dust does not.
            val rows = IntArray(h)
            val cols = IntArray(w)
            for (y in 0 until h) for (x in 0 until w) if (ink(px[y * w + x])) { rows[y]++; cols[x]++ }
            val top = rows.indexOfFirst { it >= 2 }
            val left = cols.indexOfFirst { it >= 2 }
            if (top < 0 || left < 0) return@withContext null
            val bottom = rows.indexOfLast { it >= 2 }
            val right = cols.indexOfLast { it >= 2 }
            val k = size.width / w
            Box(left * k, top * k, (right + 1) * k, (bottom + 1) * k)
        }
    }

    /**
     * Shows only part of each page: [boxes] gives, for each page, the part to keep in page points
     * (as the page is seen now), or null to show the whole page again. One undoable change.
     */
    suspend fun cropPages(boxes: Map<Int, Box?>): Boolean = onDoc {
        val d = pdf ?: return@onDoc false
        d.beginOperation("Trim margins")
        try {
            for ((i, box) in boxes) {
                val p = pdfPage(i)
                if (box == null) p.getObject().delete("CropBox")
                else p.setCropBox(Rect(box.x0, box.y0, box.x1, box.y1))
            }
            d.endOperation()
        } catch (t: Throwable) {
            runCatching { d.abandonOperation() }
            throw t
        }
        dropPages()
        sizes.clear()
        refreshFlags()
        dirty = true
        true
    }

    /** True when any page shows only part of itself. */
    suspend fun hasCrops(): Boolean = onDoc {
        val d = pdf ?: return@onDoc false
        (0 until pageCount).any { i -> runCatching { !d.findPage(i).get("CropBox").isNull }.getOrDefault(false) }
    }

    /** Pages that are pictures with no words MuPDF can find (or only a scanner's stamp): the ones worth reading. */
    suspend fun pagesWithoutText(): List<Int> = onDoc {
        (0 until pageCount).filter { i ->
            runCatching { (page(i) as? com.artifex.mupdf.fitz.PDFPage)?.let { TextLayer.needsReading(it) } ?: !TextLayer.hasText(page(i)) }.getOrDefault(false)
        }
    }

    /**
     * Draws a page for reading its words (see Reading.scaleFor).
     * Returns the picture and how many pixels make one point, to bring word boxes back to the page.
     */
    suspend fun renderForReading(index: Int): Pair<Bitmap, Float> = onDoc {
        val s = sizeNow(index)
        val scale = Reading.scaleFor(s.width, s.height, (page(index) as? PDFPage)?.let(Reading::pictureScale))
        AndroidDrawDevice.drawPage(page(index), Matrix(scale)) to scale
    }

    /** Lays words read off pictures over their pages as invisible text, as one step that can be undone. */
    suspend fun addReadText(words: Map<Int, List<ReadWord>>): Int = onDoc {
        val d = pdf ?: return@onDoc 0
        var n = 0
        d.beginOperation("Make searchable")
        try {
            for ((i, ws) in words) n += TextLayer.add(d, pdfPage(i), ws)
            d.endOperation()
        } catch (t: Throwable) {
            runCatching { d.abandonOperation() }
            throw t
        }
        dropPages()
        refreshFlags()
        if (n > 0) dirty = true
        n
    }

    /** Whether changes can be appended to the original; asked on the document's own thread. */
    suspend fun canSaveIncrementally(): Boolean = onDoc { !rewriteOnly && runCatching { pdf?.canBeSavedIncrementally() ?: false }.getOrDefault(false) }

    /**
     * Writes the document to [path]. Incremental saving appends only the changes, so [path] must
     * already hold an exact copy of the original file; the caller makes that copy first.
     */
    suspend fun writeTo(path: String, incremental: Boolean) = onDoc {
        val d = pdf ?: error("Only PDF files can be saved.")
        d.save(path, if (incremental) "incremental" else "garbage=compact")
        if (pendingSigners.isNotEmpty()) {
            completeSignatures(path, pendingSigners.toList())
            pendingSigners.clear()
        }
    }

    private fun refreshFlags() {
        val d = pdf ?: return
        canUndo = runCatching { d.canUndo() }.getOrDefault(false)
        canRedo = runCatching { d.canRedo() }.getOrDefault(false)
    }

    private fun dropPages() {
        charsCache.clear()
        rawCache.clear()
        tableCache.clear()
        bandCache.clear()
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
        charsCache.remove(index)
        rawCache.clear()
        tableCache.clear()
        bandCache.remove(index)
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

    /** The first pages' lines with their type sizes, to find the file's real title and authors. */
    suspend fun infoPages(n: Int = 2): List<InfoPage> = onDoc {
        (0 until minOf(n, pageCount)).map { i ->
            val w = FlowWalker()
            val st = page(i).toStructuredText()
            try { st.walk(w) } finally { runCatching { st.destroy() } }
            val lines = w.blocks.flatMap { b -> b.lines.map { l -> InfoLine(l.text, l.size, l.y0, l.y1, l.bold.count { it } * 2 > l.bold.size) } }
            InfoPage(sizeNow(i).height, lines, lines.joinToString("\n") { it.text })
        }
    }

    /** How many form fields the file has. */
    suspend fun formFieldCount(): Int = onDoc {
        runCatching { (pdf ?: return@onDoc 0).trailer.get("Root").get("AcroForm").get("Fields").size() }.getOrDefault(0)
    }

    /** What the file says about itself: Title, Author, CreationDate, Creator, Producer (empty ones left out). */
    suspend fun metadata(): Map<String, String> = onDoc {
        listOf("Title", "Author", "CreationDate", "Creator", "Producer").mapNotNull { k ->
            runCatching { doc.getMetaData("info:$k") }.getOrNull()?.takeIf { it.isNotBlank() }?.let { k to it }
        }.toMap()
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

    /**
     * Runs [block] on the document's own thread. Once the document is closed, waiting work is
     * dropped as cancelled (quietly, like a screen that has gone away), and the check is made again
     * on the thread itself: a page still drawing when the file is saved and opened again must never
     * reach a document that is already freed, which crashed the app.
     */
    private suspend fun <T> onDoc(block: () -> T): T {
        if (closed) throw kotlinx.coroutines.CancellationException("Document is closed")
        return withContext(dispatcher) {
            if (closed || Thread.currentThread().name != "pdfx-doc") throw kotlinx.coroutines.CancellationException("Document is closed")
            block()
        }
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
                        PdfSession(doc, stream, { context.contentResolver.openInputStream(uri) ?: error("The file could not be read.") }, d).also { it.prepare() }
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
                withContext(d) { PdfSession(Document.openDocument(path), null, { java.io.FileInputStream(path) }, d).also { it.prepare() } }
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
