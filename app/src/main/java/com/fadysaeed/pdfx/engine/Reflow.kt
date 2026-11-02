package com.fadysaeed.pdfx.engine

import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.StructuredTextWalker
import kotlin.math.max
import kotlin.math.min

/** A run of bold and/or italic words inside a paragraph, as character offsets. */
data class Span(val start: Int, val end: Int, val bold: Boolean, val italic: Boolean)

/** One line of a page as the engine read it, with the style of each character. */
internal class RawLine(
    val text: String, val bold: BooleanArray, val italic: BooleanArray, val size: Float, val y0: Float, val y1: Float,
    val x0: Float = 0f, val x1: Float = 10000f,
)

/** A block of a page: lines of text, a picture, or a table found on the page. */
internal class RawBlock(val box: Box, val lines: List<RawLine>, val image: Boolean, val table: TableModel? = null, val figure: FigureModel? = null)

/**
 * Puts each table found on a page in place of the text it covers: the table itself (or, when it
 * could not be read cleanly, a picture of it), where its first line was. Lines outside every
 * table stay as they were.
 */
internal fun placeTables(blocks: List<RawBlock>, tables: List<TableModel>, figures: List<FigureModel> = emptyList()): List<RawBlock> {
    // A picture fallback only when the finder knew the whole table's outline.
    val use = tables.filter { it.kind != TableKind.IMAGE || (it.extent.width >= 100f && it.extent.height >= 40f) }
    if (use.isEmpty() && figures.isEmpty()) return blocks
    // Each table or figure, the page area its text is in, and the block that stands in for it.
    class Part(val extent: Box, val block: RawBlock)
    val parts = use.map { t ->
        Part(t.extent, if (t.kind == TableKind.IMAGE) RawBlock(t.extent, emptyList(), true) else RawBlock(t.extent, emptyList(), false, t))
    } + figures.map { f -> Part(f.extent, RawBlock(f.box, emptyList(), false, figure = f)) }
    fun owner(l: RawLine): Part? {
        val cy = (l.y0 + l.y1) / 2
        return parts.firstOrNull { p ->
            val e = p.extent
            cy > e.y0 - 1.5f && cy < e.y1 + 1.5f && min(l.x1, e.x1) - max(l.x0, e.x0) > 0.5f * (l.x1 - l.x0)
        }
    }
    val placed = HashSet<Part>()
    val out = ArrayList<RawBlock>()
    for (b in blocks) {
        if (b.image) {
            // A picture inside a table or a figure (a drawn arrow, a logo) is part of it.
            val cx = (b.box.x0 + b.box.x1) / 2
            val cy = (b.box.y0 + b.box.y1) / 2
            if (parts.any { p -> cx > p.extent.x0 && cx < p.extent.x1 && cy > p.extent.y0 && cy < p.extent.y1 }) continue
            out += b; continue
        }
        var keep = ArrayList<RawLine>()
        for (l in b.lines) {
            val p = owner(l)
            if (p == null) { keep += l; continue }
            if (keep.isNotEmpty()) { out += RawBlock(boxOf(keep, b.box), keep, false); keep = ArrayList() }
            if (placed.add(p)) out += p.block
        }
        if (keep.size == b.lines.size) out += b
        else if (keep.isNotEmpty()) out += RawBlock(boxOf(keep, b.box), keep, false)
    }
    // One whose text the page reader did not give (drawn as outlines, say) still shows, in its place.
    for (p in parts) if (p !in placed) {
        val at = out.indexOfFirst { it.box.y0 > p.extent.y0 }.let { if (it < 0) out.size else it }
        out.add(at, p.block)
    }
    return out
}

private fun boxOf(lines: List<RawLine>, within: Box) =
    Box(max(within.x0, lines.minOf { it.x0 }), lines.minOf { it.y0 }, min(within.x1, lines.maxOf { it.x1 }).coerceAtLeast(within.x0 + 1f), lines.maxOf { it.y1 })

/** Walks a page's text keeping what plain text loses: fonts (bold, italic), sizes, and where pictures are. */
internal class FlowWalker : StructuredTextWalker {
    val blocks = ArrayList<RawBlock>()
    private var lines: ArrayList<RawLine>? = null
    private var box: Box? = null
    private val sb = StringBuilder()
    private val bold = ArrayList<Boolean>()
    private val italic = ArrayList<Boolean>()
    private var sizeSum = 0f
    private var lineBox: Rect? = null
    private var lastFont: Font? = null
    private var lastBold = false
    private var lastItalic = false

    override fun onImageBlock(bbox: Rect, transform: Matrix?, image: Image?) {
        blocks += RawBlock(Box(bbox.x0, bbox.y0, bbox.x1, bbox.y1), emptyList(), true)
    }

    override fun beginTextBlock(bbox: Rect, flags: Int) {
        lines = ArrayList()
        box = Box(bbox.x0, bbox.y0, bbox.x1, bbox.y1)
    }

    override fun endTextBlock() {
        val l = lines
        val b = box
        if (l != null && b != null && l.isNotEmpty()) blocks += RawBlock(b, l, false)
        lines = null
    }

    override fun beginLine(bbox: Rect, wmode: Int, dir: Point?) {
        sb.setLength(0); bold.clear(); italic.clear(); sizeSum = 0f
        lineBox = bbox
    }

    override fun endLine() {
        val r = lineBox ?: return
        if (sb.isNotEmpty() && sb.isNotBlank()) {
            lines?.add(RawLine(sb.toString(), bold.toBooleanArray(), italic.toBooleanArray(), sizeSum / bold.size.coerceAtLeast(1), r.y0, r.y1, r.x0, r.x1))
        }
    }

    override fun onChar(c: Int, origin: Point?, font: Font?, size: Float, q: Quad?, argb: Int, flags: Int, bidi: Int) {
        // Asking a font for its style crosses into native code; ask once per font in a row.
        if (font !== lastFont) {
            lastFont = font
            lastBold = runCatching { font?.isBold == true }.getOrDefault(false)
            lastItalic = runCatching { font?.isItalic == true }.getOrDefault(false)
        }
        val b = lastBold || (flags and StructuredText.CHAR_FLAGS_BOLD) != 0
        val units = Character.toChars(c)
        units.forEach { u ->
            sb.append(u); bold += b; italic += lastItalic
        }
        sizeSum += size * units.size
    }

    override fun beginStruct(standard: String?, raw: String?, index: Int) {}
    override fun endStruct() {}
    override fun onVector(bbox: Rect?, info: StructuredTextWalker.VectorInfo?, argb: Int) {}
}

private val PAGE_NUMBER = Regex("^(page\\s*)?([0-9]{1,4}|[ivxlcdm]{1,6})(\\s*(of|/)\\s*[0-9]{1,4})?$", RegexOption.IGNORE_CASE)
private val LIST_MARK = Regex("^\\s*([•·▪◦‣●○■□–—*-]|\\(?[0-9]{1,3}[.)]|\\(?[a-zA-Z][.)]|[ivx]{1,4}[.)])\\s+")
private val ENDS_SENTENCE = Regex("[.!?:;؟؛…\"”’)\\]]\\s*$")

/** Text in a page's top or bottom band, with numbers blanked, to spot running heads and feet. */
internal fun bandKey(s: String): String = s.lowercase().replace(Regex("[0-9]+"), "#").replace(Regex("\\s+"), " ").trim()

/**
 * Turns a page's raw blocks into paragraphs for reading: running heads and feet and page numbers
 * dropped, list items split out, hyphenated words rejoined, bold and italic kept, headings found
 * from the real type size, and pictures kept in place. [nearBands] holds the top and bottom lines
 * of the pages around this one, so a line repeated there is known to be a running head.
 */
internal fun buildParas(blocks: List<RawBlock>, pageHeight: Float, nearBands: Set<String>): List<Para> {
    val top = pageHeight * 0.085f
    val bottom = pageHeight * 0.915f
    // Body size: the size most characters are set in.
    val sizes = blocks.flatMap { b -> b.lines.map { it.size to it.text.length } }.sortedBy { it.first }
    val total = sizes.sumOf { it.second }
    var acc = 0
    val body = sizes.firstOrNull { acc += it.second; acc * 2 >= total }?.first ?: 0f

    val out = ArrayList<Para>()
    for (b in blocks) {
        if (b.table != null) {
            out += Para("", table = b.table)
            continue
        }
        if (b.figure != null) {
            out += Para("", figure = b.figure.box, figureInfo = b.figure)
            continue
        }
        if (b.image) {
            if (b.box.width >= 50f && b.box.height >= 36f) out += Para("", figure = b.box)
            continue
        }
        val text = b.lines.joinToString(" ") { it.text.trim() }
        val inBand = b.box.y1 <= top || b.box.y0 >= bottom
        if (inBand && text.length <= 90 && (PAGE_NUMBER.matches(text.trim()) || bandKey(text) in nearBands)) continue
        if (PAGE_NUMBER.matches(text.trim()) && b.lines.size == 1 && inBand) continue

        // Split the block where a line starts a list item.
        val groups = ArrayList<MutableList<RawLine>>()
        for (l in b.lines) {
            if (groups.isEmpty() || LIST_MARK.containsMatchIn(l.text) && LIST_MARK.find(l.text)!!.range.first == 0) groups += mutableListOf(l)
            else groups.last() += l
        }
        for (g in groups) out += joinLines(g, body)
    }
    return out
}

private fun joinLines(lines: List<RawLine>, body: Float): Para {
    val sb = StringBuilder()
    val bold = ArrayList<Boolean>()
    val italic = ArrayList<Boolean>()
    for (l in lines) {
        val t = l.text
        val start = t.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        val end = t.indexOfLast { !it.isWhitespace() } + 1
        if (end <= start) continue
        if (sb.isNotEmpty()) {
            val hyphen = sb.endsWith("-") && sb.length > 1 && sb[sb.length - 2].isLetter() && t[start].isLowerCase()
            if (hyphen) { sb.setLength(sb.length - 1); bold.removeAt(bold.size - 1); italic.removeAt(italic.size - 1) }
            else { sb.append(' '); bold += false; italic += false }
        }
        for (i in start until end) { sb.append(t[i]); bold += l.bold.getOrElse(i) { false }; italic += l.italic.getOrElse(i) { false } }
    }
    var text = sb.toString()
    var offset = 0
    var marker = ""
    val m = LIST_MARK.find(text)
    val list = m != null && m.range.first == 0
    if (list) {
        marker = m!!.groupValues[1]
        offset = m.range.last + 1
        text = text.substring(offset)
    }
    // Runs of bold or italic, skipping spaces at their edges.
    val spans = ArrayList<Span>()
    var i = 0
    while (i < text.length) {
        val bi = bold.getOrElse(i + offset) { false }
        val ii = italic.getOrElse(i + offset) { false }
        var j = i + 1
        while (j < text.length && bold.getOrElse(j + offset) { false } == bi && italic.getOrElse(j + offset) { false } == ii) j++
        if (bi || ii) spans += Span(i, j, bi, ii)
        i = j
    }
    val boldShare = if (text.isEmpty()) 0f else spans.filter { it.bold }.sumOf { it.end - it.start }.toFloat() / text.length
    val size = lines.map { it.size }.average().toFloat()
    val short = lines.size <= 3 && text.length <= 160
    val level = when {
        list -> 0
        body > 0f && short && size >= body * 1.4f -> 1
        body > 0f && short && size >= body * 1.15f -> 2
        short && boldShare > 0.9f && lines.size <= 2 && text.length <= 100 && !ENDS_SENTENCE.containsMatchIn(text) -> 2
        else -> 0
    }
    // Whole-paragraph bold is the heading's own weight; keep spans only for body text.
    return Para(text, level, if (level > 0) emptyList() else spans, list, marker = marker)
}

/** Whether the last paragraph of one page runs on into the first paragraph of the next. */
internal fun runsOn(last: Para?, first: Para?): Boolean {
    if (last == null || first == null) return false
    if (last.figure != null || first.figure != null || last.table != null || first.table != null || last.level > 0 || first.level > 0 || first.list) return false
    if (ENDS_SENTENCE.containsMatchIn(last.text)) return false
    val c = first.text.firstOrNull { !it.isWhitespace() } ?: return false
    return c.isLowerCase() || (Character.getDirectionality(c) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC)
}

/** Joins two paragraphs into one, keeping bold and italic in the right places. */
internal fun joinParas(a: Para, b: Para): Para {
    val hyphen = a.text.endsWith("-") && a.text.length > 1 && a.text[a.text.length - 2].isLetter()
    val head = if (hyphen) a.text.dropLast(1) else a.text + " "
    val shift = head.length
    return a.copy(text = head + b.text, spans = a.spans.map { if (it.end > head.length) it.copy(end = head.length) else it } + b.spans.map { it.copy(start = it.start + shift, end = it.end + shift) })
}
