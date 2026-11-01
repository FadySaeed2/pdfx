package com.fadysaeed.pdfx.engine

import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.StructuredTextWalker

/** A run of bold and/or italic words inside a paragraph, as character offsets. */
data class Span(val start: Int, val end: Int, val bold: Boolean, val italic: Boolean)

/** One line of a page as the engine read it, with the style of each character. */
internal class RawLine(val text: String, val bold: BooleanArray, val italic: BooleanArray, val size: Float, val y0: Float, val y1: Float)

/** A block of a page: lines of text, a picture, or a table found on the page. */
internal class RawBlock(val box: Box, val lines: List<RawLine>, val image: Boolean, val table: RawTable? = null)

/** A table the engine found: its outline and its cells, row by row, each with its text and whether it is bold. */
internal class RawTable(
    val box: Box,
    val rows: List<List<Pair<String, Boolean>>>,
    /** For each cell: the left edge of its first line, its type size, and how many lines it has. */
    val geo: List<List<Triple<Float, Float, Int>>> = emptyList(),
)

/** A rough width for [s] set at [size]: capitals and digits wider, spaces and stops narrower. */
internal fun textWidth(s: String, size: Float): Float = s.sumOf { c ->
    when {
        c.isUpperCase() -> 0.68
        c.isDigit() -> 0.55
        c == ' ' -> 0.28
        c.isLetter() -> 0.5
        else -> 0.33
    }
}.toFloat() * size

/**
 * The engine sometimes cuts one column into several, splitting words ("Le" | "vel"). Where, in
 * every row, a cell's words run straight on into the next cell's, the two columns are one and
 * are joined back. Where columns are clearly apart they stay. Anything in between is unclear,
 * and the table is shown as a picture instead (null).
 */
internal fun repairColumns(t: RawTable): RawTable? {
    if (t.geo.size != t.rows.size || t.rows.isEmpty()) return t
    val rows = t.rows.map { it.toMutableList() }.toMutableList()
    val geo = t.geo.map { it.toMutableList() }.toMutableList()
    if (rows.any { it.size != rows[0].size } || geo.indices.any { geo[it].size != rows[it].size }) return t
    var c = 0
    while (c < rows[0].size - 1) {
        val gaps = rows.indices.mapNotNull { r ->
            val a = rows[r][c].first
            val b = rows[r][c + 1].first
            val (al, asz, alines) = geo[r][c]
            val (bl, _, _) = geo[r][c + 1]
            if (a.isBlank() || b.isBlank() || alines != 1 || al.isNaN() || bl.isNaN() || asz <= 0f) null
            else (bl - (al + textWidth(a, asz))) / asz
        }
        when {
            gaps.isNotEmpty() && gaps.all { it < 0.9f } -> {
                for (r in rows.indices) {
                    val (a, ab) = rows[r][c]
                    val (b, bb) = rows[r][c + 1]
                    val (al, asz, alines) = geo[r][c]
                    val (bl, bsz, blines) = geo[r][c + 1]
                    val joined = when {
                        a.isBlank() -> b
                        b.isBlank() -> a
                        else -> {
                            val gap = if (asz > 0f && !al.isNaN() && !bl.isNaN()) (bl - (al + textWidth(a, asz))) / asz else 1f
                            if (gap < 0.11f) a + b else "$a $b"
                        }
                    }
                    rows[r][c] = joined to (if (a.isBlank()) bb else if (b.isBlank()) ab else ab && bb)
                    geo[r][c] = if (a.isBlank()) Triple(bl, bsz, blines) else Triple(al, asz, maxOf(alines, blines))
                    rows[r].removeAt(c + 1)
                    geo[r].removeAt(c + 1)
                }
            }
            gaps.isEmpty() || gaps.all { it > 1.8f } -> c++
            else -> return null
        }
    }
    return RawTable(t.box, rows, geo)
}

/**
 * Reads the tables out of the engine's HTML for a page analysed for tables. (Walking that page
 * directly is not safe in this engine build: its vector callback hands Java a bad boolean.)
 * Each table's outline is rough here: the left and top of its lines, and their bottom.
 */
internal fun parseTablesHtml(html: String): List<RawTable> {
    val out = ArrayList<RawTable>()
    val tableRe = Regex("<table[^>]*>(.*?)</table>", RegexOption.DOT_MATCHES_ALL)
    val rowRe = Regex("<tr[^>]*>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL)
    val cellRe = Regex("<t[dh][^>]*>(.*?)</t[dh]>", RegexOption.DOT_MATCHES_ALL)
    val pRe = Regex("<p style=\"top:([0-9.\\-]+)pt;left:([0-9.\\-]+)pt;line-height:([0-9.\\-]+)pt\">(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
    for (t in tableRe.findAll(html)) {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        val geo = ArrayList<List<Triple<Float, Float, Int>>>()
        val rows = rowRe.findAll(t.groupValues[1]).map { r ->
            val rowGeo = ArrayList<Triple<Float, Float, Int>>()
            val cells = cellRe.findAll(r.groupValues[1]).map { c ->
                var firstLeft = Float.NaN
                var firstSize = 0f
                val lines = pRe.findAll(c.groupValues[1]).map { pm ->
                    val top = pm.groupValues[1].toFloatOrNull() ?: 0f
                    val left = pm.groupValues[2].toFloatOrNull() ?: 0f
                    val lh = pm.groupValues[3].toFloatOrNull() ?: 0f
                    x0 = minOf(x0, left); y0 = minOf(y0, top); y1 = maxOf(y1, top + lh * 1.25f); x1 = maxOf(x1, left)
                    if (firstLeft.isNaN()) { firstLeft = left; firstSize = lh }
                    pm.groupValues[4]
                }.toList()
                rowGeo += Triple(firstLeft, firstSize, lines.size)
                val raw = lines.joinToString(" ")
                val text = htmlText(raw)
                val boldText = Regex("<b>(.*?)</b>", RegexOption.DOT_MATCHES_ALL).findAll(raw).sumOf { htmlText(it.groupValues[1]).count { ch -> !ch.isWhitespace() } }
                val chars = text.count { !it.isWhitespace() }
                text to (chars > 0 && boldText * 10 >= chars * 8)
            }.toList()
            if (cells.isNotEmpty()) geo += rowGeo
            cells
        }.filter { it.isNotEmpty() }.toList()
        if (rows.isNotEmpty() && x0 < Float.MAX_VALUE) out += RawTable(Box(x0, y0, maxOf(x1, x0 + 1f), y1), rows, geo)
    }
    return out
}

/** Plain text from a snippet of the engine's HTML: tags removed, character references decoded. */
internal fun htmlText(s: String): String {
    val noTags = s.replace(Regex("<[^>]+>"), "")
    val sb = StringBuilder()
    var i = 0
    while (i < noTags.length) {
        val c = noTags[i]
        if (c == '&') {
            val end = noTags.indexOf(';', i)
            if (end > i) {
                val ent = noTags.substring(i + 1, end)
                val cp = when {
                    ent.startsWith("#x") -> ent.substring(2).toIntOrNull(16)
                    ent.startsWith("#") -> ent.substring(1).toIntOrNull()
                    ent == "lt" -> '<'.code
                    ent == "gt" -> '>'.code
                    ent == "amp" -> '&'.code
                    ent == "quot" -> '"'.code
                    ent == "apos" -> '\''.code
                    ent == "nbsp" -> ' '.code
                    else -> null
                }
                if (cp != null) { sb.appendCodePoint(cp); i = end + 1; continue }
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString().replace(Regex("\\s+"), " ").trim()
}

/**
 * Puts the tables found on a page in place of the plain text blocks they cover. A table is
 * kept as rows and cells only when it is clean (a real grid that holds all its words); otherwise
 * it is shown as a picture of that part of the page, so no number ever lands in the wrong cell.
 */
internal fun mergeTables(blocks: List<RawBlock>, tables: List<RawTable>): List<RawBlock> {
    if (tables.isEmpty()) return blocks
    val squash = { t: String -> t.filter { !it.isWhitespace() } }
    val tableText = tables.associateWith { t -> squash(t.rows.flatten().joinToString("") { it.first }) }
    val covered = HashMap<RawTable, MutableList<RawBlock>>()
    val out = ArrayList<RawBlock>()
    for (b in blocks) {
        val bt = squash(b.lines.joinToString("") { it.text })
        val t = if (b.image || bt.isEmpty()) null else tables.firstOrNull { t ->
            val cy = (b.box.y0 + b.box.y1) / 2
            cy >= t.box.y0 - 4f && cy <= t.box.y1 + 4f && b.box.x1 >= t.box.x0 - 4f && tableText[t]!!.contains(bt)
        }
        if (t == null) { out += b; continue }
        val list = covered.getOrPut(t) { ArrayList() }
        if (list.isEmpty()) out += RawBlock(t.box, emptyList(), false, t)
        list += b
    }
    return out.map { b ->
        val t = b.table ?: return@map b
        val cov = covered[t].orEmpty()
        // The real outline: the table's lines and every block it took over.
        val box = cov.fold(t.box) { a, c -> Box(minOf(a.x0, c.box.x0), minOf(a.y0, c.box.y0), maxOf(a.x1, c.box.x1), maxOf(a.y1, c.box.y1)) }
        val chars = cov.sumOf { c -> c.lines.sumOf { l -> l.text.count { !it.isWhitespace() } } }
        val whole = repairColumns(RawTable(box, t.rows, t.geo))
        if (whole != null && isClean(whole, chars)) RawBlock(box, emptyList(), false, whole) else RawBlock(box, emptyList(), true, null)
    }
}

/** A grid of at least 2 by 2, every row the same width, mostly filled, holding (nearly) all the words it covers. */
internal fun isClean(t: RawTable, covered: Int): Boolean {
    val rows = t.rows
    if (rows.size < 2) return false
    val cols = rows[0].size
    if (cols < 2 || rows.any { it.size != cols }) return false
    val cells = rows.flatten()
    if (cells.count { it.first.isNotBlank() } * 10 < cells.size * 5) return false
    val inCells = cells.sumOf { c -> c.first.count { !it.isWhitespace() } }
    if (covered > 0 && (inCells * 10 < covered * 9 || inCells * 10 > covered * 11)) return false
    return true
}

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
            lines?.add(RawLine(sb.toString(), bold.toBooleanArray(), italic.toBooleanArray(), sizeSum / bold.size.coerceAtLeast(1), r.y0, r.y1))
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
            val rows = b.table.rows.map { r -> r.map { it.first } }
            val header = b.table.rows[0].all { it.second || it.first.isBlank() } && b.table.rows[0].any { it.second }
            out += Para("", table = rows, tableHeader = header, figure = null, tableBox = b.box)
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
