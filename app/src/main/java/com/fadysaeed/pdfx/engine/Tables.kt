package com.fadysaeed.pdfx.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Finding tables on a page, strictly. A table is only taken as one when the page says so plainly:
 * a caption ("Table 2", "TABLE 4–1.") with rules or shaded bands around the table, or, with no
 * caption, a row of short column labels between two rules and a closing rule under the rows.
 * Columns come from white gutters running down the whole table, helped by the column labels;
 * rows from line spacing, the first column, and hanging indents. Superscripts stay superscripts.
 * When anything does not add up the table is shown as a picture of the page instead, never as
 * guessed text. The steps match the prototype the golden test files were made with.
 *
 * Text in the result uses two marks: ^…^ around small raised (or lowered) text, and ⟦c|k⟧ for a
 * glyph the file draws but does not name (an arrow from a Symbol font): c is the code the file
 * gives, k its index in [TableModel.glyphs], where the place to cut its picture from is kept.
 */

/** One character as the page has it: box, baseline origin, code, size, weight, font, line and its direction. */
class TChar(
    val x0: Float, val y0: Float, val x1: Float, val y1: Float,
    val ox: Float, val oy: Float,
    val code: Int, val size: Float, val bold: Boolean, val font: String,
    val line: Int, val dx: Float, val dy: Float,
)

/** A drawn shape: kind "fill" (a filled subpath's box and its colour) or "line" (a stroked straight segment and its width). */
class TShape(val kind: String, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val r: Float = 0f, val g: Float = 0f, val b: Float = 0f)

class PageInput(val w: Float, val h: Float, val chars: List<TChar>, val shapes: List<TShape>)

enum class TableKind { GRID, CARDS, LIST, IMAGE }

class TableRow(val group: Boolean, val cells: List<String>)

class TableModel(
    val page: Int,
    val caption: String,
    val number: String,
    val box: Box,
    val header: List<String>,
    val rows: List<TableRow>,
    val notes: List<String>,
    val kind: TableKind,
    val why: String = "",
    val cols: Int = 0,
    /** Where to cut each unnamed glyph's picture from, by the k in its ⟦c|k⟧ mark. */
    val glyphs: List<Box> = emptyList(),
) {
    /** All the page the table takes, its notes under it included: the text there is the table's. */
    var extent: Box = box
}

private class Word(
    var x0: Float, var y0: Float, var x1: Float, var y1: Float,
    var text: String, var size: Float, var bold: Boolean, var mini: Boolean,
    val base: Float, val font: String, val line: Int, val upright: Boolean,
)

private class Rule(val y: Float, val x0: Float, val x1: Float, val lum: Float)
private class Band(val x0: Float, val y0: Float, var x1: Float, val y1: Float, val lum: Float)
private class Label(var x0: Float, var y0: Float, var x1: Float, var y1: Float, var text: String)
private class VLine(val words: MutableList<Word>, val base: Float) // one visual line

private val SPACES = setOf(32, 160, 0x2002, 0x2003, 0x2009, 0x200A)
private val SYMFONT = Regex("symbol|dingbat|wingding|webding|zapf|\\bpi\\b|universal", RegexOption.IGNORE_CASE)
private val CAP = Regex("^(TABLE|Table)$")
private val NUM = Regex("^[A-Z]?\\d+([.–\\-]\\d+)?[.:]?$")
private val CONT = Regex("^\\(?[Cc]ontinued\\)?\\.?$")
private const val BULLETS = "•▪●◦■□–"
private val NOTE_START = Regex("^(\\^?[a-z*†‡§]\\^?\\.?|Note\\.?|Notes\\.?|Source\\.?|Sources\\.?|Abbreviations?:?|Key:?)$")
private val NUMBERED = Regex("^(\\d{1,2}[.)]?|[•▪●◦■–])$")
private val LETTER_DOT = Regex("^[a-z]\\.$")
private val LETTERS3 = Regex("[A-Za-z]{3}")
private val GLYPH_INDEX = Regex("\\|\\d+⟧")

private fun median(xs: List<Float>): Float {
    if (xs.isEmpty()) return 0f
    val s = xs.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2f
}

private fun round1(v: Float): Float = Math.round(v * 10.0) / 10f

/** The marks of a table's text without glyph indexes: what the golden test files hold. */
fun goldenText(s: String): String = GLYPH_INDEX.replace(s, "⟧")

object TableFinder {

    fun find(page: Int, input: PageInput): List<TableModel> = Finder(page, input).run()

    private class Finder(val pno: Int, val input: PageInput) {
        val glyphs = ArrayList<Box>()
        val vrules = ArrayList<FloatArray>()
        /** Every thin straight piece, short ones too: at, from, to. Cell borders are often drawn cell by cell. */
        val hsegs = ArrayList<FloatArray>()
        val vsegs = ArrayList<FloatArray>()
        /** Where the notes read last end, for the table being made. */
        var notesEnd = 0f

        fun withNotes(t: TableModel): TableModel {
            t.extent = Box(t.box.x0, t.box.y0, t.box.x1, max(t.box.y1, notesEnd))
            notesEnd = 0f
            return t
        }

        fun run(): List<TableModel> {
            val words = wordsOf()
            val (rules, bands) = shapesOf()
            val upright = words.filter { it.upright }
            val lines = linesOf(upright).sortedWith(compareBy<List<Word>>({ l -> l.minOf { it.y0 } }, { it[0].x0 }))
            val tables = ArrayList<TableModel>()
            val sizes = upright.filter { !it.mini }.map { it.size }
            val body = if (sizes.isEmpty()) 10f else median(sizes)
            for (line in lines) {
                if (line.size < 2 || !CAP.matches(line[0].text) || !NUM.matches(line[1].text)) continue
                val capy0 = line.minOf { it.y0 }
                val capy1 = line.maxOf { it.y1 }
                val near = rules.any { it.y > capy1 - 2 && it.y < capy1 + 30 } || bands.any { it.y0 < capy1 + 30 && it.y1 > capy0 - 2 }
                if (!(line[0].bold || near)) continue
                notesEnd = 0f
                tables += withNotes(regionTable(upright, rules, bands, line, body))
            }
            tables += gridTables(upright, bands, body, tables.map { it.box })
            tables += ruledTables(upright, rules, body, tables.map { it.box })
            return tables
        }

        // ---------------------------------------------------------------- words and shapes

        fun wordsOf(): List<Word> {
            val byLine = LinkedHashMap<Int, MutableList<TChar>>()
            for (c in input.chars) byLine.getOrPut(c.line) { ArrayList() }.add(c)
            val out = ArrayList<Word>()
            for ((li, cs) in byLine) {
                val sizes = HashMap<Float, Int>()
                for (c in cs) {
                    if (c.code in SPACES) continue
                    val k = round1(c.size)
                    sizes[k] = (sizes[k] ?: 0) + 1
                }
                if (sizes.isEmpty()) continue
                val main = sizes.keys.max()
                val upright = abs(cs[0].dx - 1) < 0.05f && abs(cs[0].dy) < 0.05f
                var cur: Word? = null
                var prev: TChar? = null
                fun flush() { cur?.let { out += it }; cur = null }
                for (c in cs) {
                    val code = c.code
                    if (code in SPACES) { flush(); prev = c; continue }
                    val small = c.size < 0.82f * main
                    val gap = if (prev != null) c.x0 - prev.x2() else 0f
                    val w = cur
                    if (w != null && (gap > 0.25f * max(c.size, w.size) || small != w.mini || gap < -0.5f * c.size)) flush()
                    var ch = String(Character.toChars(code))
                    if (SYMFONT.containsMatchIn(c.font) && (code in 48..57 || code in 65..90 || code in 97..122)) {
                        ch = "⟦" + ch + "|" + glyphs.size + "⟧"
                        glyphs += Box(c.x0, c.y0, c.x1, c.y1)
                    }
                    val cw = cur
                    if (cw == null) {
                        cur = Word(c.x0, c.y0, c.x1, c.y1, ch, c.size, c.bold, small, c.oy, c.font, li, upright)
                    } else {
                        cw.x0 = min(cw.x0, c.x0); cw.y0 = min(cw.y0, c.y0); cw.x1 = max(cw.x1, c.x1); cw.y1 = max(cw.y1, c.y1)
                        cw.text += ch; cw.bold = cw.bold && c.bold; cw.size = max(cw.size, c.size)
                    }
                    prev = c
                }
                flush()
            }
            return out
        }

        private fun TChar.x2() = x1

        fun shapesOf(): Pair<List<Rule>, List<Band>> {
            val rules = ArrayList<Rule>()
            val bands = ArrayList<Band>()
            for (s in input.shapes) {
                val w = s.x1 - s.x0
                val h = s.y1 - s.y0
                if (s.kind == "fill") {
                    val lum = 0.3f * s.r + 0.59f * s.g + 0.11f * s.b
                    if (lum < 0.97f && h <= 3 && w >= max(2f, 2 * h)) hsegs += floatArrayOf((s.y0 + s.y1) / 2, s.x0, s.x1)
                    if (lum < 0.97f && w <= 3 && h >= max(2f, 2 * w)) vsegs += floatArrayOf((s.x0 + s.x1) / 2, s.y0, s.y1)
                    if (w >= 40 && h <= 3 && lum < 0.97f) rules += Rule((s.y0 + s.y1) / 2, s.x0, s.x1, lum)
                    else if (h >= 40 && w <= 3 && lum < 0.97f) vrules += floatArrayOf(s.x0, s.y0, s.x1, s.y1)
                    else if (h > 3 && lum < 0.97f && w > 3) bands += Band(s.x0, s.y0, s.x1, s.y1, lum)
                } else if (s.kind == "line") {
                    if (h < 1 && w >= 2) hsegs += floatArrayOf((s.y0 + s.y1) / 2, s.x0, s.x1)
                    if (w < 1 && h >= 2) vsegs += floatArrayOf((s.x0 + s.x1) / 2, s.y0, s.y1)
                    if (h < 1 && w >= 40) rules += Rule((s.y0 + s.y1) / 2, s.x0, s.x1, 0f)
                    else if (w < 1 && h >= 40) vrules += floatArrayOf(s.x0, s.y0, s.x1, s.y1)
                }
            }
            // Bands drawn in pieces (one per column) count as one band.
            val sorted = bands.sortedWith(compareBy<Band>({ Math.round(it.y0) }, { Math.round(it.y1) }, { it.x0 }))
            val merged = ArrayList<Band>()
            for (b in sorted) {
                val m = merged.lastOrNull()
                if (m != null && abs(m.y0 - b.y0) < 1 && abs(m.y1 - b.y1) < 1 && abs(m.lum - b.lum) < 0.02f && b.x0 <= m.x1 + 1.5f) {
                    m.x1 = max(m.x1, b.x1); continue
                }
                merged += Band(b.x0, b.y0, b.x1, b.y1, b.lum)
            }
            return rules to merged
        }

        // ---------------------------------------------------------------- lines

        fun lineBase(l: List<Word>): Float {
            val mains = l.filter { !it.mini }.map { it.base }
            return if (mains.isNotEmpty()) median(mains) else median(l.map { it.base })
        }

        /** Words in their text lines, left to right, the lines in reading order. */
        fun linesOf(words: List<Word>): List<List<Word>> {
            val lines = LinkedHashMap<Int, MutableList<Word>>()
            for (w in words) lines.getOrPut(w.line) { ArrayList() }.add(w)
            return lines.values.map { v -> v.sortedBy { it.x0 } }
                .sortedWith(compareBy<List<Word>>({ Math.round(lineBase(it)) }, { it[0].x0 }))
        }

        /** Words grouped by baseline across the whole width; small raised ones join the line they sit on. */
        fun baselineLines(ws: List<Word>, size: Float): List<VLine> {
            val mains = ws.filter { !it.mini }.sortedBy { it.base }
            val lines = ArrayList<VLine>()
            for (w in mains) {
                val last = lines.lastOrNull()
                if (last != null && abs(last.base - w.base) < 0.35f * size) last.words += w
                else lines += VLine(mutableListOf(w), w.base)
            }
            for (w in ws) {
                if (!w.mini) continue
                if (lines.isEmpty()) { lines += VLine(mutableListOf(w), w.base); continue }
                val mid = (w.y0 + w.y1) / 2
                val inside = lines.filter { l -> l.words.minOf { it.y0 } - 0.5f <= mid && mid <= l.words.maxOf { it.y1 } + 0.5f }
                val best = (inside.ifEmpty { lines }).minBy { abs(it.base - w.base) }
                best.words += w
            }
            for (l in lines) l.words.sortBy { it.x0 }
            return lines
        }

        fun textOf(ws: List<Word>): String {
            val sb = StringBuilder()
            var prev: Word? = null
            for (w in ws) {
                val t = if (w.mini) "^" + w.text + "^" else w.text
                val p = prev
                if ((p != null && !(w.mini || p.mini)) || (p != null && w.x0 - p.x1 > 0.25f * w.size)) sb.append(' ')
                sb.append(t)
                prev = w
            }
            return sb.toString()
        }

        // ---------------------------------------------------------------- tables without a caption

        fun ruledTables(words: List<Word>, rules: List<Rule>, body: Float, taken: List<Box>): List<TableModel> {
            val out = ArrayList<TableModel>()
            val wide = rules.filter { it.x1 - it.x0 >= 120 }.sortedBy { it.y }
            val dark = wide.filter { it.lum < 0.9f }
            val used = ArrayList(taken)
            for ((i, a) in dark.withIndex()) {
                for (b in dark.subList(i + 1, dark.size)) {
                    if (b.y - a.y < 6) continue
                    if (b.y - a.y > 45) break
                    if (min(a.x1, b.x1) - max(a.x0, b.x0) < 0.8f * (a.x1 - a.x0)) continue
                    val x0 = max(a.x0, b.x0)
                    val x1 = min(a.x1, b.x1)
                    if (used.any { t -> t.y0 - 2 < b.y && b.y < t.y1 + 2 && min(t.x1, x1) - max(t.x0, x0) > 0 }) continue
                    val hw = words.filter { w -> a.y < (w.y0 + w.y1) / 2 && (w.y0 + w.y1) / 2 < b.y && x0 - 3 < w.x0 && w.x1 < x1 + 3 }
                    if (hw.isEmpty()) continue
                    val bl = baselineLines(hw, body)
                    val labels = headerLabels(hw, body)
                    if (!(bl.size in 1..3 && labels.size >= 2)) continue
                    if (labels.any { it.text.length > 35 || BULLETS.contains(it.text.take(1)) && it.text.isNotEmpty() }) continue
                    val hm = hw.filter { !it.mini }
                    if (hm.any { it.bold } && !hm.all { it.bold }) continue
                    val ends = wide.filter { r -> r.y > b.y + 10 && min(r.x1, x1) - max(r.x0, x0) > 0.8f * (x1 - x0) }
                    if (ends.isEmpty()) continue
                    val c = ends[0].y
                    val bw = words.filter { w -> b.y < (w.y0 + w.y1) / 2 && (w.y0 + w.y1) / 2 < c && x0 - 3 < w.x0 && w.x1 < x1 + 3 }
                    if (baselineLines(bw, body).size < 2) continue
                    val above = linesOf(words).filter { l ->
                        val d = a.y - l.maxOf { it.y1 }
                        d > 0 && d < 22 && l.filter { !it.mini }.all { it.bold } && x0 - 3 < l[0].x0 && LETTERS3.containsMatchIn(textOf(l))
                    }
                    val title = above.lastOrNull()?.let { textOf(it) } ?: ""
                    val top = above.lastOrNull()?.let { min(it.minOf { w -> w.y0 }, a.y) } ?: a.y
                    val notes = notesBelow(words, x0, x1, c, body)
                    val t = build(title, "", Box(x0, top, x1, c), hw, bw, notes)
                    if (t.kind == TableKind.IMAGE || t.cols < 2) continue
                    out += withNotes(t); used += t.box
                    break
                }
            }
            return out
        }

        // ---------------------------------------------------------------- full grids

        /** Thin pieces on one line joined into whole rules: [at, from, to], sorted by where they sit. */
        fun joinSegs(segs: List<FloatArray>): List<FloatArray> {
            val out = ArrayList<FloatArray>()
            for (s in segs.sortedWith(compareBy<FloatArray>({ Math.round(it[0] * 2) }, { it[1] }))) {
                val m = out.lastOrNull()
                if (m != null && abs(m[0] - s[0]) < 1f && s[1] <= m[2] + 2f) { m[2] = max(m[2], s[2]); continue }
                out += floatArrayOf(s[0], s[1], s[2])
            }
            return out.sortedBy { it[0] }
        }

        /**
         * Tables drawn as a full grid with no caption: one outline, at least three rules across it
         * (a header and a row, or two rows) closed by sides, and at least one rule down between
         * columns. Cells come straight from the lines, so nothing is guessed; a word over a line
         * means the table is shown as a picture.
         */
        fun gridTables(words: List<Word>, bands: List<Band>, body: Float, taken: List<Box>): List<TableModel> {
            val hs = joinSegs(hsegs).filter { it[2] - it[1] >= 60 }
            val vs = joinSegs(vsegs)
            fun down(x: Float, ya: Float, yb: Float) = vs.any { abs(it[0] - x) < 2.5f && it[1] <= ya + 1.5f && it[2] >= yb - 1.5f }
            val out = ArrayList<TableModel>()
            val used = ArrayList(taken)
            var i = 0
            while (i < hs.size) {
                val a = hs[i]
                val chain = arrayListOf(a)
                var lastIndex = i
                for (j in i + 1 until hs.size) {
                    val r = hs[j]
                    if (abs(r[1] - a[1]) >= 3 || abs(r[2] - a[2]) >= 3) continue
                    val prev = chain.last()
                    if (r[0] - prev[0] < 4) { lastIndex = j; continue }
                    if (!(down(a[1], prev[0], r[0]) && down(a[2], prev[0], r[0]))) break
                    chain += r; lastIndex = j
                }
                if (chain.size < 3) { i++; continue }
                i = lastIndex + 1
                val x0 = a[1]
                val x1 = a[2]
                val top = chain.first()[0]
                val bottom = chain.last()[0]
                if (used.any { t -> min(t.x1, x1) - max(t.x0, x0) > 0 && min(t.y1, bottom) - max(t.y0, top) > 0 }) continue
                notesEnd = 0f
                grid(words, bands, body, chain.map { it[0] }, x0, x1, vs, ::down)?.let { out += withNotes(it); used += it.box }
            }
            return out
        }

        fun grid(words: List<Word>, bands: List<Band>, body: Float, ys: List<Float>, x0: Float, x1: Float, vs: List<FloatArray>, down: (Float, Float, Float) -> Boolean): TableModel? {
            val top = ys.first()
            val bottom = ys.last()
            // The rules down between columns, each where it runs through a whole row.
            val xs = vs.filter { it[0] > x0 + 3 && it[0] < x1 - 3 && it[2] > top && it[1] < bottom }.map { it[0] }.sorted()
            val dividers = ArrayList<Float>()
            for (x in xs) if (dividers.isEmpty() || x - dividers.last() > 2.5f) dividers += x
            val rowsAt = ys.zipWithNext()
            val present = rowsAt.map { (ya, yb) -> dividers.filter { down(it, ya, yb) } }
            val cuts = dividers.filter { d -> present.count { d in it } * 2 >= rowsAt.size }
            if (cuts.isEmpty()) return null
            val inside = words.filter { w -> val cx = (w.x0 + w.x1) / 2; val cy = (w.y0 + w.y1) / 2; cx > x0 && cx < x1 && cy > top && cy < bottom }
            val mainsAll = inside.filter { !it.mini }
            if (mainsAll.isEmpty()) return null
            val size = median(mainsAll.map { it.size })
            for (w in inside) if (w.size < 0.82f * size) w.mini = true
            val ncol = cuts.size + 1
            fun col(w: Word, p: List<Float>): Int {
                val cx = (w.x0 + w.x1) / 2
                val span = p.count { cx > it }
                return if (span == 0) 0 else cuts.indexOf(p[span - 1]) + 1
            }
            class Raw(val group: Boolean, val cells: List<MutableList<Word>>, val ya: Float, val yb: Float)
            val raws = ArrayList<Raw>()
            for ((k, r) in rowsAt.withIndex()) {
                val (ya, yb) = r
                val p = cuts.filter { it in present[k] }
                val ws = inside.filter { w -> val cy = (w.y0 + w.y1) / 2; cy > ya && cy < yb }
                if (ws.isEmpty()) continue
                for (w in ws) if (!w.mini) for (d in p) if (w.x0 < d - 0.8f && w.x1 > d + 0.8f) {
                    return image("", "", Box(x0, top, x1, bottom), "word '${w.text}' crosses a grid line")
                }
                val cells = List(ncol) { ArrayList<Word>() }
                for (w in ws) cells[col(w, p)] += w
                raws += Raw(p.isEmpty(), cells, ya, yb)
            }
            if (raws.size < 2) return null
            // A column with nothing in it anywhere is only a drawn gap.
            val keep = (0 until ncol).filter { c -> raws.any { it.cells[c].isNotEmpty() } }
            if (keep.size < 2) return null
            val rights = keep.map { c -> raws.flatMap { it.cells[c] }.filter { !it.mini }.maxOfOrNull { it.x1 } ?: 0f }
            fun texts(r: Raw) = keep.mapIndexed { n, c -> joinCell(baselineLines(r.cells[c], size).map { it.words }, size, rights[n]) }
            val first = raws.first()
            val firstMains = first.cells.flatten().filter { !it.mini }
            val restMains = raws.drop(1).flatMap { it.cells.flatten() }.filter { !it.mini }
            fun shaded(r: Raw) = bands.any { b -> b.y0 <= r.ya + 2 && b.y1 >= r.yb - 2 && b.x1 - b.x0 >= 0.8f * (x1 - x0) }
            val headed = !first.group && raws.size >= 2 && (
                (firstMains.isNotEmpty() && firstMains.all { it.bold } && !restMains.all { it.bold }) ||
                    (shaded(first) && !shaded(raws[1])))
            val header = if (headed) texts(first) else emptyList()
            // A row with no line down through it is one cell across: a heading for the rows under it.
            val rows = (if (headed) raws.drop(1) else raws).map { r -> TableRow(r.group, texts(r)) }
            val above = linesOf(words).filter { l ->
                val d = top - l.maxOf { it.y1 }
                d > 0 && d < 22 && l.filter { !it.mini }.all { it.bold } && x0 - 3 < l[0].x0 && LETTERS3.containsMatchIn(textOf(l))
            }
            val title = above.lastOrNull()?.let { textOf(it) } ?: ""
            val boxTop = above.lastOrNull()?.let { min(it.minOf { w -> w.y0 }, top) } ?: top
            val notes = notesBelow(words, x0, x1, bottom, body)
            return TableModel(pno, title, "", Box(x0, boxTop, x1, bottom), header, rows, notes, chooseKind(rows, keep.size), "grid", keep.size, glyphs)
        }

        fun notesBelow(words: List<Word>, x0: Float, x1: Float, end: Float, body: Float): List<String> {
            val after = words.filter { x0 - 3 < it.x0 && it.x1 < x1 + 3 && it.y0 > end - 0.5f }
            val kept = ArrayList<VLine>()
            var last = end
            for (l in baselineLines(after, body)) {
                val top = l.words.minOf { it.y0 }
                val ms = l.words.filter { !it.mini }.map { it.size }
                if (ms.isEmpty() || ms.max() > 0.92f * body || top - last > 14) break
                kept += l; last = l.words.maxOf { it.y1 }
            }
            notesEnd = last
            return noteParagraphs(kept)
        }

        // ---------------------------------------------------------------- tables with a caption

        fun regionTable(words: List<Word>, rules: List<Rule>, bands: List<Band>, cap: List<Word>, body: Float): TableModel {
            val capy0 = cap.minOf { it.y0 }
            val capy1 = cap.maxOf { it.y1 }
            val number = cap[1].text.trimEnd('.', ':')
            val near = rules.filter { it.y > capy0 - 20 && it.y < capy1 + 40 }.map { it.x0 to it.x1 } +
                bands.filter { it.y0 > capy0 - 20 && it.y0 < capy1 + 40 }.map { it.x0 to it.x1 }
            if (near.isEmpty()) return image("", number, Box(0f, 0f, 0f, 0f), "no rule or band near the caption")
            val x0 = near.minOf { it.first }
            val x1 = near.maxOf { it.second }
            val width = x1 - x0
            val edges = ArrayList<Float>()
            for (r in rules) if (min(r.x1, x1) - max(r.x0, x0) > 0.6f * width) edges += r.y
            for (b in bands) if (min(b.x1, x1) - max(b.x0, x0) > 0.6f * width && b.y1 - b.y0 > 3) { edges += b.y0; edges += b.y1 }
            edges.sort()
            val merged = ArrayList<Float>()
            for (e in edges) {
                if (merged.isNotEmpty() && e - merged.last() < 2.5f) { merged[merged.size - 1] = max(merged.last(), e); continue }
                merged += e
            }
            var below = merged.filter { it > capy1 - 1 }
            if (below.isEmpty()) return image("", number, Box(x0, capy0, x1, capy1), "no edge below the caption")
            val first = below[0]
            val capwords = words.filter { capy0 - 2 < it.y0 && it.y1 < first + 1 && x0 - 2 < it.x0 && it.x1 < x1 + 2 }
            val caption = linesOf(capwords).sortedBy { l -> l.minOf { it.y0 } }.joinToString(" ") { textOf(it) }
            val sides = vrules.filter { abs(it[0] - x0) < 3 || abs(it[2] - x1) < 3 }
            if (below.size < 3 && sides.isNotEmpty()) {
                // A framed table running on to the next page: it ends where its side lines end.
                val bottom = sides.maxOf { it[3] }
                if (bottom > below.last() + 10) below = below + bottom
            }
            if (below.size < 2) return image(caption, number, Box(x0, capy0, x1, first), "table runs on without an end")
            fun inside(w: Word, a: Float, b: Float): Boolean {
                val m = (w.y0 + w.y1) / 2
                return a - 0.5f < m && m < b + 0.5f && x0 - 3 < w.x0 && w.x1 < x1 + 3
            }
            val s1 = below[0]
            val s2 = below[1]
            val between = words.filter { inside(it, s1, s2) }
            val blines = if (between.isNotEmpty()) baselineLines(between, body) else emptyList()
            val bulleted = blines.any { l -> l.words[0].text.isNotEmpty() && BULLETS.contains(l.words[0].text.take(1)) }
            val headTop: Float
            val headBot: Float
            val end: Float
            if (blines.size in 1..4 && below.size >= 3 && !bulleted && between.all { it.text.length < 40 }) {
                headTop = s1; headBot = s2; end = below[2]
            } else {
                headTop = s1; headBot = s1; end = s2
            }
            val headerWords = words.filter { inside(it, headTop, headBot) }
            val bodyWords = words.filter { inside(it, headBot, end) && !CONT.matches(it.text) }
            val nb = bands.filter { abs(it.y0 - end) < 3 && min(it.x1, x1) - max(it.x0, x0) > 0.6f * width }
            val notes = if (nb.isNotEmpty()) {
                notesEnd = nb[0].y1
                noteParagraphs(baselineLines(words.filter { inside(it, nb[0].y0, nb[0].y1) }, body))
            } else notesBelow(words, x0, x1, end, body)
            return build(caption, number, Box(x0, capy0, x1, end), headerWords, bodyWords, notes)
        }

        fun noteParagraphs(lines: List<VLine>): List<String> {
            val paras = ArrayList<String>()
            for (l in lines) {
                val t = textOf(l.words)
                val w0 = l.words[0]
                val first = if (!w0.mini) w0.text else "^" + w0.text + "^"
                val starts = NOTE_START.matches(first) || w0.mini || LETTER_DOT.matches(w0.text)
                if (paras.isNotEmpty() && !starts) {
                    val prev = paras.last()
                    paras[paras.size - 1] = if (prev.endsWith("-") && !prev.endsWith(" -")) prev + t else "$prev $t"
                } else paras += t
            }
            return paras
        }

        fun image(caption: String, number: String, box: Box, why: String, header: List<String> = emptyList(), rows: List<TableRow> = emptyList(), notes: List<String> = emptyList()) =
            TableModel(pno, caption, number, box, header, rows, notes, TableKind.IMAGE, why, 0, glyphs)

        // ---------------------------------------------------------------- columns and rows

        fun gutters(ws: List<Word>, x0: Float, x1: Float, minw: Float): List<Pair<Float, Float>> {
            val res = 4
            val n = ((x1 - x0 + 8) * res).toInt()
            if (n <= 0) return emptyList()
            val cover = BooleanArray(n)
            for (w in ws) {
                val a = ((w.x0 - x0 + 2) * res).toInt()
                val b = ((w.x1 - x0 + 2) * res).toInt()
                for (i in max(0, a) until min(n, b)) cover[i] = true
            }
            val out = ArrayList<Pair<Float, Float>>()
            val first = (0 until n).firstOrNull { cover[it] } ?: n
            val last = (0 until n).lastOrNull { cover[it] } ?: 0
            var i = first
            while (i <= last) {
                if (!cover[i]) {
                    var j = i
                    while (j < n && !cover[j]) j++
                    if ((j - i).toFloat() / res >= minw) out += (x0 - 2 + i.toFloat() / res) to (x0 - 2 + j.toFloat() / res)
                    i = j
                } else i++
            }
            return out
        }

        fun colOf(cx0: Float, cx1: Float, cuts: List<Float>): Int = cuts.count { (cx0 + cx1) / 2 > it }

        fun build(caption: String, number: String, box: Box, headerWords: List<Word>, bodyWords: List<Word>, notes: List<String>): TableModel {
            val x0 = box.x0
            val x1 = box.x1
            if (bodyWords.isEmpty()) return image(caption, number, box, "no body", notes = notes)
            val size = median(bodyWords.filter { !it.mini }.map { it.size })
            // Text much smaller than the table's own is a superscript or subscript, even set apart.
            for (w in bodyWords + headerWords) if (w.size < 0.82f * size) w.mini = true
            val mains = bodyWords.filter { !it.mini }
            // Lines holding a single phrase (a group heading) may run across columns: leave them out when finding gutters.
            val blines = baselineLines(mains, size)
            fun phrases(l: List<Word>): Int {
                var n = 1
                for (k in 1 until l.size) if (l[k].x0 - l[k - 1].x1 > 1.0f * size) n++
                return n
            }
            val counts = blines.map { phrases(it.words) }
            val multi = counts.isNotEmpty() && counts.sorted()[counts.size / 2] >= 2
            val lone: MutableSet<Word> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
            if (multi) for ((l, n) in blines.zip(counts)) if (n == 1) lone.addAll(l.words)
            val g = gutters(mains.filter { it !in lone }, x0, x1, max(2.6f, 0.38f * size))
            var cuts = g.map { (it.first + it.second) / 2 }
            val labels = headerLabels(headerWords, size)
            cuts = addHeaderCuts(cuts, labels, mains)
            // A first column holding only numbers or bullets belongs with the text beside it.
            if (cuts.isNotEmpty()) {
                val c0 = cuts
                val firsts = mains.filter { colOf(it.x0, it.x1, c0) == 0 && it !in lone }
                if (firsts.isNotEmpty() && firsts.all { NUMBERED.matches(it.text) }) cuts = cuts.drop(1)
            }
            val ncol = cuts.size + 1
            for (w in mains) {
                if (w in lone) continue
                for (k in cuts) if (w.x0 < k - 0.8f && w.x1 > k + 0.8f) return image(caption, number, box, "word '${w.text}' crosses a column boundary", notes = notes)
            }
            val header = MutableList(ncol) { "" }
            for (lab in labels.sortedWith(compareBy<Label>({ it.y0 }, { it.x0 }))) {
                val c = colOf(lab.x0, lab.x1, cuts)
                val crossing = cuts.filter { lab.x0 < it - 0.8f && lab.x1 > it + 0.8f }
                for (k in crossing) {
                    // Wider than its column is fine when each neighbour it reaches has its own label.
                    val side = cuts.count { it < k }
                    val other = if (side == c) side + 1 else side
                    if (labels.none { it !== lab && colOf(it.x0, it.x1, cuts) == other }) return image(caption, number, box, "header '${lab.text}' spans columns", notes = notes)
                }
                header[c] = (header[c] + " " + lab.text).trim()
            }
            val rows = makeRows(bodyWords, ncol, cuts, size, lone) ?: return image(caption, number, box, "rows unclear", notes = notes)
            val plain = rows.filter { !it.group }
            if (ncol >= 3) {
                for (c in 0 until ncol) if (plain.none { it.cells[c].isNotBlank() }) return image(caption, number, box, "column $c empty", header, rows, notes)
            }
            return TableModel(pno, caption, number, box, header, rows, notes, chooseKind(rows, ncol), "", ncol, glyphs)
        }

        fun headerLabels(ws: List<Word>, size: Float): List<Label> {
            val pieces = ArrayList<Label>()
            for (l in baselineLines(ws, size)) {
                var cur: Label? = null
                for (w in l.words) {
                    val c = cur
                    if (c != null && (w.mini || w.x0 - c.x1 < 0.45f * size)) {
                        c.y0 = min(c.y0, w.y0); c.x1 = max(c.x1, w.x1); c.y1 = max(c.y1, w.y1)
                        c.text += if (w.mini) "^" + w.text + "^" else " " + w.text
                    } else {
                        if (c != null) pieces += c
                        cur = Label(w.x0, w.y0, w.x1, w.y1, w.text)
                    }
                }
                cur?.let { pieces += it }
            }
            val sorted = pieces.sortedWith(compareBy<Label>({ it.y0 }, { it.x0 }))
            val labels = ArrayList<Label>()
            for (p in sorted) {
                val hit = labels.firstOrNull { lab -> min(lab.x1, p.x1) - max(lab.x0, p.x0) > 0 && p.y0 - lab.y1 < 1.2f * size }
                if (hit != null) {
                    hit.x0 = min(hit.x0, p.x0); hit.x1 = max(hit.x1, p.x1); hit.y1 = max(hit.y1, p.y1); hit.text += " " + p.text
                } else labels += Label(p.x0, p.y0, p.x1, p.y1, p.text)
            }
            return labels
        }

        fun addHeaderCuts(cuts: List<Float>, labels: List<Label>, mains: List<Word>): List<Float> {
            val ls = labels.sortedBy { it.x0 }
            val out = ArrayList(cuts)
            for (k in 1 until ls.size) {
                val a = ls[k - 1]
                val b = ls[k]
                if (b.x0 - a.x1 < 1.0f) continue
                if (out.any { (a.x0 + a.x1) / 2 < it && it < (b.x0 + b.x1) / 2 }) continue
                val right = mains.filter { b.x0 - 6 <= it.x0 && it.x0 < max(b.x1, b.x0 + 30) }
                if (right.isEmpty()) continue
                val x = right.minOf { it.x0 } - 0.6f
                if (mains.any { it.x0 < x - 0.3f && it.x1 > x + 0.3f }) continue
                if (mains.none { it.x1 <= x + 0.3f && it.x0 >= a.x0 - 6 }) continue
                out += x
            }
            return out.sorted()
        }

        fun makeRows(ws: List<Word>, ncol: Int, cuts: List<Float>, size: Float, lone: Set<Word>): List<TableRow>? {
            val lines = baselineLines(ws, size)
            if (lines.isEmpty()) return null
            fun col(w: Word) = colOf(w.x0, w.x1, cuts)
            val steps = (1 until lines.size).map { lines[it].base - lines[it - 1].base }
            val med = if (steps.isNotEmpty()) median(steps) else size * 1.2f
            val gapped = steps.any { it > 1.35f * med }
            val col0Left = lines.flatMap { it.words }.filter { col(it) == 0 && !it.mini }.minOfOrNull { it.x0 } ?: 0f
            fun cellsOf(lw: List<Word>): List<MutableList<Word>> {
                val cs = List(ncol) { ArrayList<Word>() }
                val m = lw.filter { !it.mini }
                val lonely = m.isNotEmpty() && m.all { it in lone } && m.any { w -> cuts.any { k -> w.x0 < k - 0.8f && w.x1 > k + 0.8f } }
                for (w in lw) cs[if (lonely) 0 else col(w)].add(w)
                return cs
            }
            // Gapped tables with a column of one-line entries (a number, a duration): each entry starts a row.
            var anchor: Int? = null
            if (gapped && ncol > 2) {
                var best = 0
                for (c in 1 until ncol) {
                    val hits = lines.indices.filter { i -> lines[i].words.any { col(it) == c && !it.mini } }
                    if (hits.size < 2) continue
                    val single = (1 until hits.size).all { hits[it] - hits[it - 1] > 1 }
                    val short = hits.all { i -> lines[i].words.filter { col(it) == c && !it.mini }.joinToString(" ") { it.text }.length <= 16 }
                    if (single && short && hits.size > best) { anchor = c; best = hits.size }
                }
            }
            val rows = ArrayList<MutableList<List<MutableList<Word>>>>()
            for ((i, l) in lines.withIndex()) {
                val cs = cellsOf(l.words)
                val c0 = cs[0].filter { !it.mini }
                val start: Boolean = if (rows.isEmpty()) true
                else if (ncol == 1) false
                else if (anchor != null) {
                    val hasAnchor = cs[anchor].any { col(it) == anchor && !it.mini }
                    val only0 = c0.isNotEmpty() && (1 until ncol).all { cs[it].isEmpty() }
                    val nxt = if (i + 1 < lines.size) lines[i + 1].words else emptyList()
                    val nextAnchor = nxt.any { col(it) == anchor && !it.mini }
                    val heading = only0 && c0[0].x0 <= col0Left + 0.3f * size && ((l.base - lines[i - 1].base) > 1.35f * med || nextAnchor)
                    hasAnchor || heading
                } else if (gapped) {
                    (l.base - lines[i - 1].base) > 1.35f * med
                } else if (c0.isNotEmpty()) {
                    val indented = c0[0].x0 > col0Left + 0.3f * size
                    val othersEmpty = (1 until ncol).all { cs[it].isEmpty() }
                    val prev0 = rows.last().lastOrNull { it[0].isNotEmpty() }?.get(0) ?: emptyList()
                    val prevEnds = prev0.isNotEmpty() && ".:;?!)".contains(prev0.last().text.takeLast(1)) && prev0.last().text.isNotEmpty()
                    !(indented && othersEmpty && !prevEnds && prev0.isNotEmpty())
                } else false
                if (start) rows += mutableListOf(cs) else rows.last() += cs
            }
            if (ncol == 1) return listItems(lines, size).map { TableRow(false, listOf(it)) }
            val rights = (0 until ncol).map { c -> rows.flatMap { r -> r.flatMap { it[c] } }.filter { !it.mini }.maxOfOrNull { it.x1 } ?: 0f }
            val out = ArrayList<TableRow>()
            for (r0 in rows) {
                var r: List<List<MutableList<Word>>> = r0
                // In a gapped table a block may open with a heading line (only the first column, then more rows).
                if (ncol > 1 && r.size > 1 && (1 until ncol).all { r[0][it].isEmpty() } && r[1][0].isNotEmpty() &&
                    (1 until ncol).any { r[1][it].isNotEmpty() } && r[0][0][0].x0 < r[1][0][0].x0 - 0.3f * size
                ) {
                    out += TableRow(true, listOf(textOf(r[0][0])) + List(ncol - 1) { "" })
                    r = r.drop(1)
                }
                val cells = (0 until ncol).map { c -> joinCell(r.map { it[c] }.filter { it.isNotEmpty() }, size, rights[c]) }
                val nonempty = (0 until ncol).filter { cells[it].isNotBlank() }
                out += TableRow(nonempty == listOf(0) && ncol > 1 && rows.size > 1, cells)
            }
            return out
        }

        fun listItems(lines: List<VLine>, size: Float): List<String> {
            val marks = lines.filter { it.words.isNotEmpty() && it.words[0].text.isNotEmpty() && BULLETS.contains(it.words[0].text.take(1)) }.map { it.words[0].x0 }
            val left = marks.minOrNull() ?: 0f
            val right = lines.flatMap { it.words }.filter { !it.mini }.maxOfOrNull { it.x1 } ?: 0f
            val items = ArrayList<String>()
            var prev: List<Word>? = null
            for (l in lines) {
                val mains = l.words.filter { !it.mini }
                if (mains.isEmpty()) continue
                val t = textOf(l.words)
                val first = mains[0]
                val head = first.text.take(1)
                val bullet = (head.isNotEmpty() && BULLETS.contains(head)) || head == "⟦"
                val p = prev
                if (bullet) {
                    items += (if (first.x0 > left + 0.5f * size) "\t" else "") + t
                } else if (items.isNotEmpty() && p != null) {
                    val pm = p.filter { !it.mini }
                    val short = pm.isNotEmpty() && pm.last().x1 < right - 2.5f * size
                    val pt = items.last()
                    val upper = first.text.isNotEmpty() && first.text[0].isUpperCase()
                    when {
                        short && pt.isNotEmpty() && ".:;?!".contains(pt.takeLast(1)) && upper -> items += t
                        pt.endsWith("-") && !pt.endsWith(" -") -> items[items.size - 1] = pt + t
                        pt.endsWith("/") -> items[items.size - 1] = pt + t
                        else -> items[items.size - 1] = "$pt $t"
                    }
                } else items += t
                prev = l.words
            }
            return items
        }

        fun joinCell(parts: List<List<Word>>, size: Float, right: Float): String {
            var text = ""
            var prev: List<Word>? = null
            for (p in parts) {
                val t = textOf(p)
                val pv = prev
                if (pv == null) text = t
                else {
                    val pt = text.trimEnd()
                    val mains = pv.filter { !it.mini }
                    val short = right != 0f && mains.isNotEmpty() && mains.last().x1 < right - 2.5f * size
                    val first = p.firstOrNull { !it.mini }?.text ?: ""
                    text = when {
                        (t.isNotEmpty() && BULLETS.contains(t.take(1))) || t.startsWith("⟦") || t.startsWith("+ ") -> pt + "\n" + t
                        pt.endsWith("/") -> pt + t
                        short && pt.isNotEmpty() && ".:;?!".contains(pt.takeLast(1)) && first.isNotEmpty() && first[0].isUpperCase() -> pt + "\n" + t
                        pt.endsWith("-") && !pt.endsWith(" -") -> pt + t
                        else -> "$pt $t"
                    }
                }
                prev = p
            }
            return text
        }

        fun chooseKind(rows: List<TableRow>, ncol: Int): TableKind {
            val plain = rows.filter { !it.group }
            if (ncol == 1) return TableKind.LIST
            val first = plain.map { it.cells[0].trim() }
            if (ncol == 2 && first.isNotEmpty() && first.all { it in setOf("•", "▪", "●", "–") }) return TableKind.LIST
            val longest = plain.flatMap { r -> (1 until ncol).map { r.cells[it].length } }.maxOrNull() ?: 0
            val total = plain.sumOf { r -> (1 until ncol).sumOf { r.cells[it].length } }
            val avg = total.toFloat() / max(1, plain.size * (ncol - 1))
            return if (ncol == 2 || avg > 28 || longest > 90) TableKind.CARDS else TableKind.GRID
        }
    }
}
