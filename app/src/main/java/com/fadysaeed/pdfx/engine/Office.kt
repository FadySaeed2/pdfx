package com.fadysaeed.pdfx.engine

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A stretch of a converted file that shares one page size: the XHTML that flows over its pages and
 * the header and footer drawn on each. In a header or footer, [Office.PAGE] and [Office.PAGES]
 * stand for the page number and the number of pages.
 */
class OfficePart(
    val body: String,
    val w: Float,
    val h: Float,
    /** Top, right, bottom, left, in points. */
    val margin: FloatArray,
    val header: String? = null,
    val footer: String? = null,
    val firstHeader: String? = null,
    val firstFooter: String? = null,
    /** From the page's top edge to the header, and from its bottom edge to the footer, in points. */
    val headerAt: Float = 36f,
    val footerAt: Float = 36f,
    /** This part starts a section, so its first page takes the first-page header when there is one. */
    val startsSection: Boolean = true,
    /** The type size MuPDF lays the body out at, for sheets made smaller to fit their width. */
    val baseSize: Float = 11f,
)

/** A Word file, spreadsheet or CSV turned into parts to lay out, with its title and anything left out. */
class OfficeDoc(val parts: List<OfficePart>, val title: String, val note: String?)

/**
 * Word files (.docx), spreadsheets (.xlsx) and CSV turned into XHTML that MuPDF lays out on pages,
 * all on the phone and nothing sent anywhere. Word's styles, lists, tables, pictures, links,
 * footnotes, sections, headers and footers (page numbers too) carry over; a spreadsheet becomes a
 * table per sheet with its number formats, merged cells, column widths and colours, fitted to the
 * page width. It is close to what Word or Excel would print, not identical: fonts that are not on
 * the phone are drawn in the nearest kind (Calibri in a sans, Times in a serif), and charts,
 * SmartArt and equations are left out.
 */
object Office {
    const val PAGE = "\u0001P\u0001"
    const val PAGES = "\u0001N\u0001"

    val wordExtensions = setOf("docx", "docm", "dotx", "dotm")
    val sheetExtensions = setOf("xlsx", "xlsm", "xltx", "xltm")
    val textExtensions = setOf("csv", "tsv")
    val extensions = wordExtensions + sheetExtensions + textExtensions
    /** The formats from before 2007, which are a different, closed kind of file. */
    val oldExtensions = setOf("doc", "xls")
    /** Kinds PDFx does not make PDFs of: slides and OpenDocument files. */
    val otherExtensions = setOf("ppt", "pptx", "odt", "ods", "odp", "rtf", "pages", "numbers", "key")

    fun isOffice(name: String) = name.substringAfterLast('.', "").lowercase() in extensions

    fun read(path: String, name: String = path): OfficeDoc {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in wordExtensions -> ZipFile(path).use { Docx(it, name).convert() }
            in sheetExtensions -> ZipFile(path).use { Sheets.xlsx(it, name) }
            in textExtensions -> Sheets.csv(java.io.File(path).readBytes(), name, tab = ext == "tsv")
            in oldExtensions -> throw IllegalArgumentException(
                "This is an older ${ext.uppercase()} file. Open it in Word, Excel or Google Docs, save it as .docx or .xlsx, and convert that.",
            )
            in otherExtensions -> throw IllegalArgumentException(
                "PDFx makes PDFs from Word, Excel and CSV files, not ${ext.uppercase()}. The app that opens it can save it as a PDF.",
            )
            else -> throw IllegalArgumentException("PDFx can make PDFs from Word (.docx), Excel (.xlsx) and CSV files.")
        }
    }

    // ------------------------------------------------------------------ shared helpers

    internal fun parse(bytes: ByteArray): org.w3c.dom.Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.isExpandEntityReferences = false }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    /** The most one part of a Word or Excel file may unpack to; real ones stay far below it. */
    internal const val PART_MAX = 64L * 1024 * 1024

    /**
     * One part of the file, unpacked. A part that unpacks past [PART_MAX] (a damaged file, or one
     * made to swell when opened) stops the reading with a plain message, before it fills the memory.
     */
    internal fun ZipFile.bytes(name: String): ByteArray? {
        val e = getEntry(name) ?: entries().asSequence().firstOrNull { it.name.equals(name, true) } ?: return null
        if (e.size > PART_MAX) throw java.io.IOException(TOO_BIG)
        return getInputStream(e).use { i ->
            val out = java.io.ByteArrayOutputStream(if (e.size in 1..PART_MAX) e.size.toInt() else 1 shl 16)
            val buf = ByteArray(1 shl 16)
            var total = 0L
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                total += n
                if (total > PART_MAX) throw java.io.IOException(TOO_BIG)
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }

    private const val TOO_BIG = "Part of this file unpacks to more than 64 MB, so PDFx won't open it. It may be damaged; try saving it again from Word or Excel."


    internal fun ZipFile.xml(name: String): Element? = bytes(name)?.let { runCatching { parse(it).documentElement }.getOrNull() }

    internal val Node.local: String get() = localName ?: nodeName.substringAfter(':')

    internal fun Element.kids(): List<Element> {
        val out = ArrayList<Element>()
        var n = firstChild
        while (n != null) { if (n is Element) out += n; n = n.nextSibling }
        return out
    }

    internal fun Element.kid(name: String): Element? {
        var n = firstChild
        while (n != null) { if (n is Element && n.local == name) return n; n = n.nextSibling }
        return null
    }

    internal fun Element.path(vararg names: String): Element? {
        var e: Element? = this
        for (n in names) e = e?.kid(n) ?: return null
        return e
    }

    /** All elements called [name] anywhere inside. */
    internal fun Element.all(name: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(e: Element) { for (k in e.kids()) { if (k.local == name) out += k; walk(k) } }
        walk(this)
        return out
    }

    /** An attribute by its local name, whatever its namespace (w:val, val, the strict namespaces). */
    internal fun Element.a(name: String): String? {
        val m = attributes
        for (i in 0 until m.length) {
            val at = m.item(i)
            if (at.local == name && at.prefix != "xmlns" && at.nodeName != "xmlns") return at.nodeValue
        }
        return null
    }

    /** A relationship id (r:id, r:embed, r:link), told apart from Word's own w:id. */
    internal fun Element.rel(name: String = "id"): String? {
        val m = attributes
        for (i in 0 until m.length) {
            val at = m.item(i)
            if (at.local == name && (at.namespaceURI?.contains("relationships") == true || at.prefix == "r")) return at.nodeValue
        }
        return null
    }

    internal fun Element.text(): String = textContent.orEmpty()

    internal fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            '\u0001' -> sb.append('\u0001')
            else -> if (c < ' ' && c != '\n' && c != '\t') sb.append(' ') else sb.append(c)
        }
        return sb.toString()
    }

    internal fun n(f: Float): String = if (f == f.toInt().toFloat()) f.toInt().toString() else String.format(java.util.Locale.US, "%.2f", f).trimEnd('0').trimEnd('.')

    /** A picture as a data: address MuPDF can draw, or null for kinds it cannot (EMF, WMF). */
    internal fun dataUri(bytes: ByteArray, name: String): String? {
        val mime = when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg", "jpe", "jfif" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp", "dib" -> "image/bmp"
            "tif", "tiff" -> "image/tiff"
            "webp" -> "image/webp"
            else -> return null
        }
        return "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    /** Fonts by kind: the phone has a sans, a serif and a fixed-width one to draw them in. */
    internal fun family(font: String?): String {
        val f = font?.lowercase()?.trim() ?: return "sans-serif"
        return when {
            listOf("courier", "consolas", "mono", "menlo", "lucida console", "source code").any { f.contains(it) } -> "monospace"
            listOf("times", "cambria", "georgia", "garamond", "book antiqua", "palatino", "serif", "minion", "baskerville", "century", "bookman", "constantia", "didot", "charter")
                .any { f.contains(it) } && !f.contains("sans") -> "serif"
            else -> "sans-serif"
        }
    }

    internal fun css(color: String?): String? {
        val c = color?.trim() ?: return null
        if (c.equals("auto", true) || c.isEmpty()) return null
        val hex = c.removePrefix("#")
        return when {
            hex.length == 6 && hex.all { it.isLetterOrDigit() } -> "#" + hex.uppercase()
            hex.length == 8 && hex.all { it.isLetterOrDigit() } -> "#" + hex.substring(2).uppercase()
            else -> null
        }
    }

    const val BASE_CSS = "body{margin:0;font-family:sans-serif;font-size:11pt;line-height:1.15}" +
        "p,h1,h2,h3,h4,h5,h6{margin:0;padding:0;font-size:11pt;font-weight:normal;font-style:normal}" +
        "table{border-collapse:collapse}td,th{padding:0 5.4pt;font-weight:normal;text-align:left}" +
        "img{vertical-align:baseline}a{color:inherit;text-decoration:inherit}.fn{font-size:9pt}"
}

// ====================================================================== Word

/** How a run of text looks: each field is "as before" when null. */
private data class RunLook(
    val bold: Boolean? = null, val italic: Boolean? = null, val underline: Boolean? = null, val strike: Boolean? = null,
    val caps: Boolean? = null, val smallCaps: Boolean? = null, val size: Float? = null, val color: String? = null,
    val highlight: String? = null, val shade: String? = null, val font: String? = null, val vert: String? = null,
    val hidden: Boolean? = null,
) {
    fun over(o: RunLook?): RunLook = if (o == null) this else RunLook(
        o.bold ?: bold, o.italic ?: italic, o.underline ?: underline, o.strike ?: strike, o.caps ?: caps, o.smallCaps ?: smallCaps,
        o.size ?: size, o.color ?: color, o.highlight ?: highlight, o.shade ?: shade, o.font ?: font, o.vert ?: vert, o.hidden ?: hidden,
    )
}

/** How a paragraph sits: each field is "as before" when null. Lengths in points. */
private data class ParaLook(
    val align: String? = null, val left: Float? = null, val right: Float? = null, val first: Float? = null,
    val before: Float? = null, val after: Float? = null, val line: Float? = null, val exact: Float? = null,
    val numId: String? = null, val level: Int? = null, val outline: Int? = null, val rtl: Boolean? = null,
    val shade: String? = null, val pageBefore: Boolean? = null, val rule: String? = null, val contextual: Boolean? = null,
) {
    fun over(o: ParaLook?): ParaLook = if (o == null) this else ParaLook(
        o.align ?: align, o.left ?: left, o.right ?: right, o.first ?: first, o.before ?: before, o.after ?: after,
        if (o.line != null || o.exact != null) o.line else line, if (o.line != null || o.exact != null) o.exact else exact,
        o.numId ?: numId, o.level ?: level, o.outline ?: outline, o.rtl ?: rtl, o.shade ?: shade, o.pageBefore ?: pageBefore, o.rule ?: rule,
        o.contextual ?: contextual,
    )
}

private class Style(val type: String, val basedOn: String?, val name: String, val para: ParaLook?, val run: RunLook?, val tableBorders: Boolean?)

private class Level(val start: Int, val format: String, val text: String, val para: ParaLook?, val run: RunLook?)

private class PageSetup(val w: Float, val h: Float, val margin: FloatArray, val headerAt: Float, val footerAt: Float, val header: String?, val footer: String?, val firstHeader: String?, val firstFooter: String?)

private class Docx(private val zip: ZipFile, private val fileName: String) {
    private val rels = HashMap<String, String>()
    private val styles = HashMap<String, Style>()
    private var defaultRun = RunLook(size = 11f)
    private var defaultPara = ParaLook()
    private var normalStyle: String? = null
    private val abstracts = HashMap<String, Map<Int, Level>>()
    private val nums = HashMap<String, Pair<String, Map<Int, Int>>>()
    private val counters = HashMap<String, IntArray>()
    private val footnotes = HashMap<String, Element>()
    private val endnotes = HashMap<String, Element>()
    private val notes = ArrayList<String>()
    private var firstHeading: String? = null
    private var skipped = HashSet<String>()

    // Fields: the instructions being read, and whether their shown result has started.
    private class Field(val instr: StringBuilder = StringBuilder(), var shown: Boolean = false, var replaced: Boolean = false)
    private val fields = ArrayList<Field>()
    private var inHeader = false

    init {
        with(Office) {
            readRels("word/_rels/document.xml.rels", rels)
            rels.values.firstOrNull { it.contains("theme/") }?.let { zip.xml(it) }?.let { th ->
                th.all("majorFont").firstOrNull()?.kid("latin")?.a("typeface")?.takeIf { it.isNotBlank() }?.let { majorFont = it }
                th.all("minorFont").firstOrNull()?.kid("latin")?.a("typeface")?.takeIf { it.isNotBlank() }?.let { minorFont = it }
            }
            zip.xml("word/styles.xml")?.let(::readStyles)
            zip.xml("word/numbering.xml")?.let(::readNumbering)
            zip.xml("word/footnotes.xml")?.let { r -> r.kids().filter { it.local == "footnote" }.forEach { e -> e.a("id")?.let { footnotes[it] = e } } }
            zip.xml("word/endnotes.xml")?.let { r -> r.kids().filter { it.local == "endnote" }.forEach { e -> e.a("id")?.let { endnotes[it] = e } } }
        }
    }

    private fun readRels(path: String, into: HashMap<String, String>) = with(Office) {
        val r = zip.xml(path) ?: return@with
        val base = path.substringBefore("_rels/")
        for (e in r.kids()) {
            val id = e.a("Id") ?: continue
            val t = e.a("Target") ?: continue
            into[id] = if (e.a("TargetMode") == "External") "ext:$t" else resolve(base, t)
        }
    }

    private fun resolve(base: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = (base + target).split('/').toMutableList()
        val out = ArrayList<String>()
        for (p in parts) when (p) { ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1); ".", "" -> Unit; else -> out += p }
        return out.joinToString("/")
    }

    // ------------------------------------------------------------- reading properties

    private fun on(e: Element?): Boolean? = with(Office) {
        if (e == null) return null
        val v = e.a("val")?.lowercase() ?: return true
        return v !in setOf("0", "false", "off", "none")
    }

    private fun twip(s: String?): Float? = s?.trim()?.toFloatOrNull()?.let { it / 20f }

    private fun runLook(rPr: Element?): RunLook? = with(Office) {
        if (rPr == null) return null
        val u = rPr.kid("u")
        val fonts = rPr.kid("rFonts")
        val shd = rPr.kid("shd")?.a("fill")
        RunLook(
            bold = on(rPr.kid("b")), italic = on(rPr.kid("i")),
            underline = u?.let { (it.a("val") ?: "single") != "none" },
            strike = on(rPr.kid("strike")) ?: on(rPr.kid("dstrike")),
            caps = on(rPr.kid("caps")), smallCaps = on(rPr.kid("smallCaps")),
            size = rPr.kid("sz")?.a("val")?.toFloatOrNull()?.let { it / 2f },
            color = rPr.kid("color")?.a("val")?.let { css(it) ?: "" },
            highlight = rPr.kid("highlight")?.a("val")?.let { highlight(it) ?: "" },
            shade = shd?.let { css(it) ?: "" },
            font = fonts?.let { it.a("ascii") ?: it.a("hAnsi") ?: it.a("cs") ?: themeFont(it.a("asciiTheme")) },
            vert = rPr.kid("vertAlign")?.a("val"),
            hidden = on(rPr.kid("vanish")) ?: on(rPr.kid("specVanish")),
        )
    }

    private var majorFont = "Calibri Light"
    private var minorFont = "Calibri"

    private fun themeFont(t: String?) = when {
        t == null -> null
        t.startsWith("major") -> majorFont
        else -> minorFont
    }

    private fun highlight(v: String) = when (v.lowercase()) {
        "yellow" -> "#FFFF00"; "green" -> "#00FF00"; "cyan" -> "#00FFFF"; "magenta" -> "#FF00FF"; "blue" -> "#0000FF"
        "red" -> "#FF0000"; "darkblue" -> "#000080"; "darkcyan" -> "#008080"; "darkgreen" -> "#008000"; "darkmagenta" -> "#800080"
        "darkred" -> "#800000"; "darkyellow" -> "#808000"; "darkgray" -> "#808080"; "lightgray" -> "#C0C0C0"; "black" -> "#000000"
        "white" -> "#FFFFFF"; else -> null
    }

    private fun paraLook(pPr: Element?): ParaLook? = with(Office) {
        if (pPr == null) return null
        val ind = pPr.kid("ind")
        val sp = pPr.kid("spacing")
        val num = pPr.kid("numPr")
        val jc = pPr.kid("jc")?.a("val")
        var line: Float? = null
        var exact: Float? = null
        sp?.a("line")?.toFloatOrNull()?.let { v ->
            when (sp.a("lineRule") ?: "auto") {
                "auto" -> line = v / 240f
                else -> exact = v / 20f
            }
        }
        val hanging = twip(ind?.a("hanging"))
        val first = twip(ind?.a("firstLine"))
        val bottom = pPr.path("pBdr", "bottom")
        ParaLook(
            align = jc, left = twip(ind?.a("left") ?: ind?.a("start")), right = twip(ind?.a("right") ?: ind?.a("end")),
            first = hanging?.let { -it } ?: first,
            before = if (sp?.a("beforeAutospacing") == "1") 14f else twip(sp?.a("before")),
            after = if (sp?.a("afterAutospacing") == "1") 14f else twip(sp?.a("after")),
            line = line, exact = exact,
            numId = num?.kid("numId")?.a("val"), level = num?.kid("ilvl")?.a("val")?.toIntOrNull(),
            outline = pPr.kid("outlineLvl")?.a("val")?.toIntOrNull(),
            rtl = on(pPr.kid("bidi")), shade = pPr.kid("shd")?.a("fill")?.let { css(it) ?: "" },
            pageBefore = on(pPr.kid("pageBreakBefore")), contextual = on(pPr.kid("contextualSpacing")),
            rule = bottom?.takeIf { (it.a("val") ?: "none") !in setOf("none", "nil") }?.let { b ->
                val w = (b.a("sz")?.toFloatOrNull() ?: 4f) / 8f
                "${n(w.coerceIn(0.25f, 6f))}pt solid ${css(b.a("color")) ?: "#000000"}"
            },
        )
    }

    private fun readStyles(root: Element) = with(Office) {
        root.path("docDefaults", "rPrDefault", "rPr")?.let { defaultRun = defaultRun.over(runLook(it)) }
        root.path("docDefaults", "pPrDefault", "pPr")?.let { defaultPara = defaultPara.over(paraLook(it)) }
        for (s in root.kids()) {
            if (s.local != "style") continue
            val id = s.a("styleId") ?: continue
            val type = s.a("type") ?: "paragraph"
            val tb = s.path("tblPr", "tblBorders")?.let { b -> b.kids().any { (it.a("val") ?: "none") !in setOf("none", "nil") } }
            styles[id] = Style(type, s.kid("basedOn")?.a("val"), s.kid("name")?.a("val").orEmpty(), paraLook(s.kid("pPr")), runLook(s.kid("rPr")), tb)
            if (type == "paragraph" && (s.a("default") == "1" || s.a("default") == "true")) normalStyle = id
        }
    }

    private fun chain(id: String?): List<Style> {
        val out = ArrayList<Style>()
        var cur = id
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur)) {
            val s = styles[cur] ?: break
            out.add(0, s)
            cur = s.basedOn
        }
        return out
    }

    private fun readNumbering(root: Element) = with(Office) {
        for (an in root.kids()) {
            if (an.local != "abstractNum") continue
            val id = an.a("abstractNumId") ?: continue
            val lv = HashMap<Int, Level>()
            for (l in an.kids()) {
                if (l.local != "lvl") continue
                val il = l.a("ilvl")?.toIntOrNull() ?: continue
                lv[il] = Level(
                    l.kid("start")?.a("val")?.toIntOrNull() ?: 1, l.kid("numFmt")?.a("val") ?: "decimal",
                    l.kid("lvlText")?.a("val") ?: "", paraLook(l.kid("pPr")), runLook(l.kid("rPr")),
                )
            }
            abstracts[id] = lv
        }
        for (num in root.kids()) {
            if (num.local != "num") continue
            val id = num.a("numId") ?: continue
            val abs = num.kid("abstractNumId")?.a("val") ?: continue
            val starts = HashMap<Int, Int>()
            for (o in num.kids()) if (o.local == "lvlOverride") {
                val il = o.a("ilvl")?.toIntOrNull() ?: continue
                o.kid("startOverride")?.a("val")?.toIntOrNull()?.let { starts[il] = it }
            }
            nums[id] = abs to starts
        }
    }

    private fun level(numId: String?, il: Int): Level? {
        val (abs, _) = nums[numId ?: return null] ?: return null
        return abstracts[abs]?.get(il)
    }

    /** The number or bullet a list paragraph starts with, counting on from the ones before it. */
    private fun label(numId: String, il: Int): String? {
        val (abs, starts) = nums[numId] ?: return null
        val levels = abstracts[abs] ?: return null
        val lv = levels[il] ?: return null
        val c = counters.getOrPut(numId) { IntArray(9) { k -> (starts[k] ?: levels[k]?.start ?: 1) - 1 } }
        c[il]++
        for (k in il + 1 until 9) c[k] = (starts[k] ?: levels[k]?.start ?: 1) - 1
        if (lv.format == "none") return ""
        if (lv.format == "bullet") return bullet(lv.text)
        var t = lv.text
        for (k in 0..8) if (t.contains("%${k + 1}")) {
            val f = levels[k]?.format ?: "decimal"
            val v = if (k == il) c[k] else c[k].coerceAtLeast(levels[k]?.start ?: 1)
            t = t.replace("%${k + 1}", format(v, if (k != il && levels[il]?.format == "decimal") "decimal" else f))
        }
        return t
    }

    private fun bullet(t: String): String {
        val c = t.firstOrNull() ?: return "•"
        return when (c) {
            'o' -> "◦"
            '', '§', '', 'n' -> "▪"
            '', 'Ø' -> "➢"
            '', 'ü' -> "✓"
            '-', '–', '—' -> "–"
            '•', '·', '', '·' -> "•"
            else -> if (c.code in 0xF000..0xF0FF || c.isLetterOrDigit()) "•" else c.toString()
        }
    }

    private fun format(v: Int, f: String): String = when (f) {
        "lowerLetter" -> letters(v).lowercase()
        "upperLetter" -> letters(v)
        "lowerRoman" -> roman(v).lowercase()
        "upperRoman" -> roman(v)
        "decimalZero" -> if (v < 10) "0$v" else "$v"
        "ordinal" -> "$v" + when { v % 100 in 11..13 -> "th"; v % 10 == 1 -> "st"; v % 10 == 2 -> "nd"; v % 10 == 3 -> "rd"; else -> "th" }
        else -> "$v"
    }

    private fun letters(v: Int): String { val c = 'A' + ((v - 1).coerceAtLeast(0) % 26); return c.toString().repeat((v - 1).coerceAtLeast(0) / 26 + 1) }

    private fun roman(v: Int): String {
        if (v <= 0 || v >= 4000) return "$v"
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var n = v
        val sb = StringBuilder()
        for (i in vals.indices) while (n >= vals[i]) { sb.append(syms[i]); n -= vals[i] }
        return sb.toString()
    }

    // ------------------------------------------------------------- writing

    private fun runCss(r: RunLook, base: RunLook?): String {
        val sb = StringBuilder()
        fun diff(a: Any?, b: Any?) = base == null || a != b
        if (diff(r.bold, base?.bold)) sb.append(if (r.bold == true) "font-weight:bold;" else "font-weight:normal;")
        if (diff(r.italic, base?.italic)) sb.append(if (r.italic == true) "font-style:italic;" else "font-style:normal;")
        val deco = listOfNotNull("underline".takeIf { r.underline == true }, "line-through".takeIf { r.strike == true })
        val baseDeco = listOfNotNull("underline".takeIf { base?.underline == true }, "line-through".takeIf { base?.strike == true })
        if (base == null || deco != baseDeco) sb.append("text-decoration:").append(if (deco.isEmpty()) "none" else deco.joinToString(" ")).append(';')
        val size = (r.size ?: 11f) * (if (r.smallCaps == true) 0.8f else 1f) * (if (r.vert == "superscript" || r.vert == "subscript") 0.65f else 1f)
        val baseSize = (base?.size ?: 11f) * (if (base?.smallCaps == true) 0.8f else 1f) * (if (base?.vert == "superscript" || base?.vert == "subscript") 0.65f else 1f)
        if (base == null || size != baseSize) sb.append("font-size:").append(Office.n(size)).append("pt;")
        if (diff(r.color, base?.color)) r.color?.takeIf { it.isNotEmpty() }?.let { sb.append("color:").append(it).append(';') } ?: run { if (base != null) sb.append("color:#000000;") }
        val bg = r.highlight?.takeIf { it.isNotEmpty() } ?: r.shade?.takeIf { it.isNotEmpty() && it != "#FFFFFF" }
        val baseBg = base?.let { it.highlight?.takeIf { h -> h.isNotEmpty() } ?: it.shade?.takeIf { s -> s.isNotEmpty() && s != "#FFFFFF" } }
        if (bg != null && bg != baseBg) sb.append("background-color:").append(bg).append(';')
        val fam = Office.family(r.font)
        if (base == null || fam != Office.family(base.font)) sb.append("font-family:").append(fam).append(';')
        if (diff(r.vert, base?.vert)) when (r.vert) {
            "superscript" -> sb.append("vertical-align:super;")
            "subscript" -> sb.append("vertical-align:sub;")
        }
        return sb.toString()
    }

    private fun paraCss(p: ParaLook, run: RunLook, inCell: Boolean): String {
        val sb = StringBuilder()
        p.before?.takeIf { it > 0 }?.let { sb.append("margin-top:").append(Office.n(it)).append("pt;") }
        val after = p.after ?: 0f
        if (after > 0) sb.append("margin-bottom:").append(Office.n(after)).append("pt;")
        p.left?.takeIf { it != 0f }?.let { sb.append(if (p.rtl == true) "margin-right:" else "margin-left:").append(Office.n(it.coerceAtLeast(0f))).append("pt;") }
        p.right?.takeIf { it != 0f }?.let { sb.append(if (p.rtl == true) "margin-left:" else "margin-right:").append(Office.n(it.coerceAtLeast(0f))).append("pt;") }
        p.first?.takeIf { it != 0f }?.let { sb.append("text-indent:").append(Office.n(it)).append("pt;") }
        when (p.align) {
            "center" -> sb.append("text-align:center;")
            "right", "end" -> sb.append(if (p.rtl == true) "text-align:left;" else "text-align:right;")
            "both", "distribute", "justify" -> sb.append("text-align:justify;")
            else -> if (p.rtl == true) sb.append("text-align:right;")
        }
        val line = p.exact?.let { e -> (e / (run.size ?: 11f)).coerceIn(0.8f, 4f) } ?: p.line
        line?.let { sb.append("line-height:").append(Office.n((it * 1.15f).coerceIn(0.9f, 4f))).append(';') }
        p.shade?.takeIf { it.isNotEmpty() && it != "#FFFFFF" }?.let { sb.append("background-color:").append(it).append(';') }
        p.rule?.let { sb.append("border-bottom:").append(it).append(";padding-bottom:1pt;") }
        sb.append(runCss(run, null))
        return sb.toString()
    }

    /** What has been written so far, cut into pages where the file asks for a new one. */
    private class Flow {
        val chunks = ArrayList<Pair<String, Boolean>>()
        var cur = StringBuilder()
        var startsSection = true
        fun cut(newSection: Boolean) {
            chunks += cur.toString() to startsSection
            cur = StringBuilder()
            startsSection = newSection
        }
    }

    private val parts = ArrayList<OfficePart>()
    /** The styles of the tables being written, innermost last: their paragraph look applies in their cells. */
    private val tableStyles = ArrayList<List<Style>>()

    private fun styleOf(p: Element?): String? = with(Office) { p?.kid("pPr")?.kid("pStyle")?.a("val") ?: normalStyle }

    /** The paragraph next to [p] among its siblings, before or after it. */
    private fun neighbour(p: Element, forward: Boolean): Element? {
        var n = if (forward) p.nextSibling else p.previousSibling
        while (n != null) {
            if (n is Element) return if (with(Office) { n.local } == "p") n else null
            n = if (forward) n.nextSibling else n.previousSibling
        }
        return null
    }

    fun convert(): OfficeDoc = with(Office) {
        val doc = zip.xml("word/document.xml") ?: throw IllegalArgumentException("This Word file has no text in it, or it is damaged.")
        val body = doc.kid("body") ?: throw IllegalArgumentException("This Word file has no text in it, or it is damaged.")
        val flow = Flow()
        var setup = pageSetup(null, null)
        for (e in body.kids()) {
            when (e.local) {
                "sectPr" -> Unit
                else -> {
                    block(e, flow.cur, flow, inCell = false)
                    // A section ends at a paragraph that carries its settings.
                    val sect = if (e.local == "p") e.path("pPr", "sectPr") else null
                    if (sect != null) {
                        setup = pageSetup(sect, setup)
                        closeSection(flow, setup)
                    }
                }
            }
        }
        setup = pageSetup(body.kid("sectPr"), setup)
        if (notes.isNotEmpty()) {
            flow.cur.append("<p style=\"margin-top:12pt;margin-bottom:6pt;border-bottom:0.5pt solid #808080\"> </p>")
            notes.forEach { flow.cur.append(it) }
        }
        closeSection(flow, setup)
        if (parts.isEmpty()) parts += OfficePart("<p>&#160;</p>", setup.w, setup.h, setup.margin)
        val title = zip.xml("docProps/core.xml")?.kids()?.firstOrNull { it.local == "title" }?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: firstHeading ?: fileName.substringAfterLast('/').substringBeforeLast('.')
        val note = if (skipped.isEmpty()) null else "Left out: ${skipped.sorted().joinToString(", ")}."
        OfficeDoc(parts, title, note)
    }

    private fun closeSection(flow: Flow, s: PageSetup) {
        flow.cut(true)
        for ((html, starts) in flow.chunks) {
            if (html.isBlank() && parts.isNotEmpty()) continue
            parts += OfficePart(
                html.ifBlank { "<p>&#160;</p>" }, s.w, s.h, s.margin, s.header, s.footer, s.firstHeader, s.firstFooter, s.headerAt, s.footerAt, starts,
            )
        }
        flow.chunks.clear()
    }

    private fun pageSetup(sect: Element?, before: PageSetup?): PageSetup = with(Office) {
        val sz = sect?.kid("pgSz")
        val mar = sect?.kid("pgMar")
        var w = twip(sz?.a("w")) ?: before?.w ?: 595.3f
        var h = twip(sz?.a("h")) ?: before?.h ?: 841.9f
        if (sz?.a("orient") == "landscape" && w < h) { val t = w; w = h; h = t }
        val m = floatArrayOf(
            (twip(mar?.a("top")) ?: before?.margin?.get(0) ?: 72f).let { kotlin.math.abs(it) },
            twip(mar?.a("right") ?: mar?.a("end")) ?: before?.margin?.get(1) ?: 72f,
            (twip(mar?.a("bottom")) ?: before?.margin?.get(2) ?: 72f).let { kotlin.math.abs(it) },
            (twip(mar?.a("left") ?: mar?.a("start")) ?: before?.margin?.get(3) ?: 72f) + (twip(mar?.a("gutter")) ?: 0f),
        )
        fun ref(kind: String, type: String): String? {
            val r = sect?.kids()?.firstOrNull { it.local == kind && (it.a("type") ?: "default") == type } ?: return null
            return rels[r.rel() ?: return null]
        }
        val titlePg = on(sect?.kid("titlePg")) == true
        // Each kind of header is carried on from the section before unless this one has its own.
        val hd = ref("headerReference", "default")?.let(::partHtml) ?: before?.header
        val ft = ref("footerReference", "default")?.let(::partHtml) ?: before?.footer
        val fh = if (titlePg) (ref("headerReference", "first")?.let(::partHtml) ?: before?.firstHeader ?: "") else null
        val ff = if (titlePg) (ref("footerReference", "first")?.let(::partHtml) ?: before?.firstFooter ?: "") else null
        PageSetup(w, h, m, twip(mar?.a("header")) ?: before?.headerAt ?: 36f, twip(mar?.a("footer")) ?: before?.footerAt ?: 36f, hd, ft, fh, ff)
    }

    /** A header's or footer's own XHTML, with the page number standing in for its field. */
    private fun partHtml(path: String): String? = with(Office) {
        val root = zip.xml(path) ?: return null
        val own = HashMap<String, String>()
        readRels(path.substringBeforeLast('/') + "/_rels/" + path.substringAfterLast('/') + ".rels", own)
        val saved = HashMap(rels)
        rels.putAll(own)
        val wasIn = inHeader
        inHeader = true
        val sb = StringBuilder()
        try {
            val flow = Flow()
            for (e in root.kids()) block(e, sb, flow, inCell = false)
        } finally {
            inHeader = wasIn
            rels.clear(); rels.putAll(saved)
        }
        sb.toString().takeIf { it.isNotBlank() }
    }

    /** A block: a paragraph, a table, or a container of them (content controls, custom XML). */
    private fun block(e: Element, out: StringBuilder, flow: Flow, inCell: Boolean) {
        with(Office) {
            when (e.local) {
                "p" -> paragraph(e, out, flow, inCell)
                "tbl" -> table(e, out, flow)
                "sdt" -> e.kid("sdtContent")?.kids()?.forEach { block(it, out, flow, inCell) }
                "customXml", "ins", "moveTo" -> e.kids().forEach { block(it, out, flow, inCell) }
                "AlternateContent" -> (e.kid("Choice") ?: e.kid("Fallback"))?.kids()?.forEach { block(it, out, flow, inCell) }
                else -> Unit
            }
        }
    }

    private fun paragraph(p: Element, outIn: StringBuilder, flow: Flow, inCell: Boolean) = with(Office) {
        var out = outIn
        val pPr = p.kid("pPr")
        val styleId = pPr?.kid("pStyle")?.a("val") ?: normalStyle
        val styleChain = chain(styleId)
        var para = defaultPara
        var run = defaultRun
        // In a table, its style's spacing and type where the paragraph has no style of its own.
        if (inCell && tableStyles.isNotEmpty() && (styleId == null || styleId == normalStyle)) {
            for (s in styleChain) { para = para.over(s.para); run = run.over(s.run) }
            for (s in tableStyles.last()) { para = para.over(s.para); run = run.over(s.run) }
        } else for (s in styleChain) { para = para.over(s.para); run = run.over(s.run) }
        val direct = paraLook(pPr)
        // A list: the style's or the paragraph's own numbering, with its level's indents.
        val numId = direct?.numId ?: para.numId
        val il = direct?.level ?: para.level ?: 0
        val lv = if (numId != null && numId != "0") level(numId, il) else null
        if (lv != null) para = para.over(lv.para)
        para = para.over(direct)
        // "Don't add space between paragraphs of the same style", as lists usually are.
        if (para.contextual == true) {
            if (neighbour(p, false)?.let(::styleOf) == styleId) para = para.copy(before = 0f)
            if (neighbour(p, true)?.let(::styleOf) == styleId) para = para.copy(after = 0f)
        }
        val markRun = run.over(runLook(pPr?.kid("rPr")))
        if (markRun.hidden == true && p.all("t").isEmpty()) return@with
        if (para.pageBefore == true && !inCell && !inHeader && flow.cur.isNotEmpty() && out === flow.cur) { flow.cut(false); out = flow.cur }
        val outline = para.outline ?: styleChain.lastOrNull { it.name.startsWith("heading", true) }?.name?.removePrefix("heading")?.removePrefix("Heading")?.trim()?.toIntOrNull()?.minus(1)
        val tag = if (outline != null && outline in 0..5 && !inCell && !inHeader) "h${outline + 1}" else "p"
        val content = StringBuilder()
        val after = StringBuilder()
        if (lv != null) label(numId!!, il)?.takeIf { it.isNotEmpty() }?.let { l ->
            val lr = run.over(lv.run).copy(font = run.font)
            content.append("<span style=\"").append(runCss(lr.copy(underline = false, strike = false), run)).append("\">").append(esc(l)).append("</span>")
            val gap = (-(para.first ?: 0f)).takeIf { it > 0 } ?: 18f
            content.append("\u00A0".repeat(((gap / ((run.size ?: 11f) * 0.5f)).toInt().coerceIn(1, 8) - (l.length - 1).coerceIn(0, 4)).coerceAtLeast(1)))
        }
        fun open() = "<$tag" + (if (para.rtl == true) " dir=\"rtl\"" else "") + " style=\"" + paraCss(para, run, inCell) + "\">"
        // Page breaks inside the paragraph cut it there.
        val pageCut: () -> Unit = {
            if (!inCell && !inHeader && out === flow.cur) {
                if (content.isNotEmpty()) { out.append(open()).append(content).append("</$tag>"); content.setLength(0) }
                flow.cut(false)
                out = flow.cur
            }
        }
        inline(p, content, run, after, pageCut)
        if (tag != "p" && firstHeading == null) content.toString().replace(Regex("<[^>]+>"), "").trim().takeIf { it.length > 2 }?.let { firstHeading = it.take(200) }
        val text = content.toString()
        out.append(open())
        if (text.replace(Regex("<[^>]+>"), "").isBlank() && !text.contains("<img")) out.append("<span style=\"font-size:").append(n(markRun.size ?: run.size ?: 11f)).append("pt\">&#160;</span>").append(text)
        else out.append(text)
        out.append("</").append(tag).append('>')
        out.append(after)
    }

    /** The runs of a paragraph or hyperlink, with what goes after the paragraph (text boxes) in [after]. */
    private fun inline(parent: Element, out: StringBuilder, base: RunLook, after: StringBuilder, pageCut: () -> Unit) {
        with(Office) {
            for (e in parent.kids()) when (e.local) {
                "r" -> textRun(e, out, base, after, pageCut)
                "hyperlink" -> {
                    val target = e.rel()?.let { rels[it] }?.takeIf { it.startsWith("ext:") }?.removePrefix("ext:")
                    if (target != null) out.append("<a href=\"").append(esc(target)).append("\">")
                    inline(e, out, base, after, pageCut)
                    if (target != null) out.append("</a>")
                }
                "ins", "smartTag", "customXml", "moveTo", "bdo", "dir" -> inline(e, out, base, after, pageCut)
                "sdt" -> e.kid("sdtContent")?.let { inline(it, out, base, after, pageCut) }
                "fldSimple" -> {
                    val instr = e.a("instr")?.trim()?.uppercase().orEmpty()
                    when {
                        instr.startsWith("PAGE") -> out.append(Office.PAGE)
                        instr.startsWith("NUMPAGES") || instr.startsWith("SECTIONPAGES") -> out.append(Office.PAGES)
                        else -> inline(e, out, base, after, pageCut)
                    }
                }
                "oMath", "oMathPara" -> out.append(esc(e.all("t").joinToString("") { it.text() }))
                "AlternateContent" -> (e.kid("Choice") ?: e.kid("Fallback"))?.let { inline(it, out, base, after, pageCut) }
                else -> Unit
            }
        }
    }

    private fun textRun(r: Element, out: StringBuilder, paraRun: RunLook, after: StringBuilder, pageCut: () -> Unit) = with(Office) {
        val rPr = r.kid("rPr")
        var look = paraRun
        rPr?.kid("rStyle")?.a("val")?.let { cs -> for (s in chain(cs)) look = look.over(s.run) }
        look = look.over(runLook(rPr))
        if (look.hidden == true) return@with
        val text = StringBuilder()
        fun flushText() {
            if (text.isEmpty()) return
            var t = text.toString()
            if (look.caps == true || look.smallCaps == true) t = t.uppercase()
            val css = runCss(look, paraRun)
            val body = esc(t).replace("  ", " \u00A0")
            if (css.isEmpty()) out.append(body) else out.append("<span style=\"").append(css).append("\">").append(body).append("</span>")
            text.setLength(0)
        }
        for (e in r.kids()) {
            val f = fields.lastOrNull()
            when (e.local) {
                "fldChar" -> when (e.a("fldCharType")) {
                    "begin" -> { flushText(); fields += Field() }
                    "separate" -> f?.let { fl ->
                        fl.shown = true
                        val instr = fl.instr.toString().trim().uppercase()
                        if (instr.startsWith("PAGE") && !instr.startsWith("PAGEREF")) { fl.replaced = true; flushText(); out.append(Office.PAGE) }
                        else if (instr.startsWith("NUMPAGES") || instr.startsWith("SECTIONPAGES")) { fl.replaced = true; flushText(); out.append(Office.PAGES) }
                    }
                    "end" -> {
                        if (f != null && !f.shown) {
                            // A field with no shown result: page numbers still show.
                            val instr = f.instr.toString().trim().uppercase()
                            flushText()
                            if (instr.startsWith("PAGE") && !instr.startsWith("PAGEREF")) out.append(Office.PAGE)
                            else if (instr.startsWith("NUMPAGES")) out.append(Office.PAGES)
                        }
                        if (fields.isNotEmpty()) fields.removeAt(fields.size - 1)
                    }
                }
                "instrText" -> f?.instr?.append(e.text())
                else -> {
                    // Inside a field's instructions, or a page number shown in its place: nothing to show.
                    if (f != null && (!f.shown || f.replaced)) continue
                    when (e.local) {
                        "t" -> text.append(e.text())
                        "tab", "ptab" -> text.append("  ")
                        "br" -> when (e.a("type")) {
                            "page" -> { flushText(); pageCut() }
                            "column" -> text.append(' ')
                            else -> { flushText(); out.append("<br/>") }
                        }
                        "cr" -> { flushText(); out.append("<br/>") }
                        "noBreakHyphen" -> text.append('‑')
                        "softHyphen" -> text.append('­')
                        "sym" -> text.append(bullet(e.a("char")?.toIntOrNull(16)?.toChar()?.toString() ?: "•"))
                        "footnoteReference", "endnoteReference" -> {
                            flushText()
                            val id = e.a("id")
                            val src = if (e.local == "footnoteReference") footnotes[id] else endnotes[id]
                            if (src != null) {
                                val k = notes.size + 1
                                out.append("<span style=\"vertical-align:super;font-size:").append(n((look.size ?: 11f) * 0.65f)).append("pt\">").append(k).append("</span>")
                                note(src, k)
                            }
                        }
                        "drawing" -> { flushText(); drawing(e, out, after) }
                        "pict", "object" -> { flushText(); vml(e, out, after) }
                        "AlternateContent" -> {
                            flushText()
                            val pick = e.kid("Choice") ?: e.kid("Fallback")
                            pick?.kids()?.forEach { k -> if (k.local == "drawing") drawing(k, out, after) else if (k.local == "pict") vml(k, out, after) }
                        }
                        else -> Unit
                    }
                }
            }
        }
        flushText()
    }

    private fun note(src: Element, k: Int) = with(Office) {
        val sb = StringBuilder()
        val flow = Flow()
        var first = true
        val wasIn = inHeader
        inHeader = true
        try {
            for (e in src.kids()) {
                val start = sb.length
                block(e, sb, flow, inCell = false)
                if (first && e.local == "p") {
                    // The note's own number, where Word puts its footnoteRef mark.
                    val close = sb.indexOf(">", start)
                    if (close > 0) sb.insert(close + 1, "<span style=\"vertical-align:super;font-size:6pt\">$k</span>\u00A0")
                    first = false
                }
            }
        } finally {
            inHeader = wasIn
        }
        notes += "<div class=\"fn\">" + sb.toString().replace(Regex("font-size:[0-9.]+pt;"), "font-size:9pt;") + "</div>"
    }

    private fun drawing(d: Element, out: StringBuilder, after: StringBuilder) = with(Office) {
        val holder = d.kids().firstOrNull { it.local == "inline" || it.local == "anchor" } ?: return@with
        val ext = holder.kid("extent")
        val w = ext?.a("cx")?.toFloatOrNull()?.div(12700f)
        val h = ext?.a("cy")?.toFloatOrNull()?.div(12700f)
        val blip = holder.all("blip").firstOrNull()
        val id = blip?.rel("embed")
        if (id != null) {
            image(id, w, h, out, block = holder.local == "anchor")
            return@with
        }
        // A text box: its words go after the paragraph, framed.
        val box = holder.all("txbxContent").firstOrNull()
        if (box != null) { textBox(box, after); return@with }
        if (holder.all("chart").isNotEmpty()) skipped += "charts"
        else if (holder.all("relIds").isNotEmpty()) skipped += "SmartArt"
        else if (holder.all("wsp").isNotEmpty()) Unit else skipped += "drawings"
    }

    private fun vml(p: Element, out: StringBuilder, after: StringBuilder) = with(Office) {
        val img = p.all("imagedata").firstOrNull()
        val id = img?.rel("id") ?: img?.rel("embed")
        if (id != null) {
            val style = p.all("shape").firstOrNull()?.a("style").orEmpty()
            fun dim(key: String) = Regex("$key:\\s*([0-9.]+)(pt|in|px|cm|mm)?").find(style)?.let { m ->
                val v = m.groupValues[1].toFloatOrNull() ?: return@let null
                when (m.groupValues[2]) { "in" -> v * 72; "px" -> v * 0.75f; "cm" -> v * 28.35f; "mm" -> v * 2.835f; else -> v }
            }
            image(id, dim("width"), dim("height"), out, block = false)
            return@with
        }
        p.all("txbxContent").firstOrNull()?.let { textBox(it, after) }
    }

    private fun textBox(box: Element, after: StringBuilder) = with(Office) {
        val sb = StringBuilder()
        val flow = Flow()
        for (e in box.kids()) block(e, sb, flow, inCell = true)
        if (sb.isNotBlank()) after.append("<div style=\"border:0.5pt solid #808080;padding:4pt;margin:4pt 0\">").append(sb).append("</div>")
    }

    private fun image(id: String, w: Float?, h: Float?, out: StringBuilder, block: Boolean) = with(Office) {
        val path = rels[id] ?: return@with
        if (path.startsWith("ext:")) return@with
        val bytes = zip.bytes(path) ?: return@with
        val uri = dataUri(bytes, path)
        if (uri == null) { skipped += "pictures in ${path.substringAfterLast('.').uppercase()} format"; return@with }
        val style = StringBuilder()
        if (w != null && w > 0) style.append("width:").append(n(w)).append("pt;")
        if (h != null && h > 0) style.append("height:").append(n(h)).append("pt;")
        if (block) out.append("<br/>")
        out.append("<img src=\"").append(uri).append("\" style=\"").append(style).append("\"/>")
        if (block) out.append("<br/>")
    }

    private fun table(t: Element, out: StringBuilder, flow: Flow) = with(Office) {
        val tblPr = t.kid("tblPr")
        val styleId = tblPr?.kid("tblStyle")?.a("val")
        tableStyles += chain(styleId)
        try { tableBody(t, tblPr, styleId, out, flow) } finally { tableStyles.removeAt(tableStyles.size - 1) }
    }

    private fun tableBody(t: Element, tblPr: Element?, styleId: String?, out: StringBuilder, flow: Flow) = with(Office) {
        val grid = t.kid("tblGrid")?.kids()?.filter { it.local == "gridCol" }?.map { twip(it.a("w")) ?: 0f }.orEmpty()
        val styleBorders = chain(styleId).lastOrNull { it.tableBorders != null }?.tableBorders
        val direct = tblPr?.kid("tblBorders")
        fun sideOn(b: Element?, side: String) = b?.kid(side)?.let { (it.a("val") ?: "none") !in setOf("none", "nil") }
        val inside = sideOn(direct, "insideH") ?: sideOn(direct, "insideV") ?: sideOn(direct, "top") ?: styleBorders ?: (styleId?.contains("Grid", true) == true)
        val outer = sideOn(direct, "top") ?: sideOn(direct, "bottom") ?: inside
        // The style's text look for the table's paragraphs.
        val rows = t.kids().filter { it.local == "tr" || it.local == "sdt" || it.local == "customXml" }.flatMap { r ->
            if (r.local == "tr") listOf(r) else r.all("tr")
        }
        // Each row's cells with the grid column they start in.
        class Cell(val e: Element, val col: Int, val span: Int, val merge: String?)
        val laid = rows.map { r ->
            var col = r.kid("trPr")?.kid("gridBefore")?.a("val")?.toIntOrNull() ?: 0
            r.kids().flatMap { if (it.local == "tc") listOf(it) else if (it.local == "sdt" || it.local == "customXml") it.all("tc") else emptyList() }.map { c ->
                val pr = c.kid("tcPr")
                val span = pr?.kid("gridSpan")?.a("val")?.toIntOrNull() ?: 1
                val vm = pr?.kid("vMerge")?.let { it.a("val") ?: "continue" }
                Cell(c, col, span, vm).also { col += span }
            }
        }
        val totalW = grid.sum().takeIf { it > 0 }
        val tw = tblPr?.kid("tblW")
        val widthCss = when {
            tw?.a("type") == "pct" -> (tw.a("w")?.removeSuffix("%")?.toFloatOrNull()?.let { if (tw.a("w")!!.endsWith("%")) it else it / 50f })?.let { "width:${n(it.coerceIn(5f, 100f))}%;" }
            tw?.a("type") == "dxa" -> twip(tw.a("w"))?.takeIf { it > 0 }?.let { "width:${n(it)}pt;" }
            totalW != null -> "width:${n(totalW)}pt;"
            else -> "width:100%;"
        } ?: "width:100%;"
        val align = when (tblPr?.kid("jc")?.a("val")) { "center" -> "margin-left:auto;margin-right:auto;"; "right", "end" -> "margin-left:auto;"; else -> "" }
        out.append("<table style=\"").append(widthCss).append(align).append(if (outer) "border:0.5pt solid #000000;" else "").append("margin-bottom:6pt\">")
        if (grid.isNotEmpty() && totalW != null) {
            out.append("<colgroup>")
            for (g in grid) out.append("<col style=\"width:").append(n(g / totalW * 100f)).append("%\"/>")
            out.append("</colgroup>")
        }
        for ((ri, cells) in laid.withIndex()) {
            out.append("<tr>")
            for (c in cells) {
                if (c.merge == "continue") continue
                var rowspan = 1
                if (c.merge == "restart") {
                    var k = ri + 1
                    while (k < laid.size && laid[k].any { it.col == c.col && it.merge == "continue" }) { rowspan++; k++ }
                }
                val pr = c.e.kid("tcPr")
                val st = StringBuilder()
                val b = pr?.kid("tcBorders")
                fun side(name: String, alt: String, def: Boolean) {
                    val on = sideOn(b, name) ?: sideOn(b, alt) ?: def
                    st.append("border-").append(name).append(':').append(if (on) "0.5pt solid #000000" else "none").append(';')
                }
                side("top", "top", inside || (ri == 0 && outer))
                side("bottom", "bottom", inside || (ri + rowspan >= laid.size && outer))
                side("left", "start", inside || (c.col == 0 && outer))
                side("right", "end", inside || (c.col + c.span >= grid.size && outer))
                pr?.kid("shd")?.a("fill")?.let { css(it) }?.takeIf { it != "#FFFFFF" }?.let { st.append("background-color:").append(it).append(';') }
                out.append("<td")
                if (c.span > 1) out.append(" colspan=\"").append(c.span).append('"')
                if (rowspan > 1) out.append(" rowspan=\"").append(rowspan).append('"')
                out.append(" style=\"").append(st).append("\">")
                val inner = StringBuilder()
                for (e in c.e.kids()) block(e, inner, flow, inCell = true)
                out.append(inner.ifEmpty { "&#160;" })
                out.append("</td>")
            }
            out.append("</tr>")
        }
        out.append("</table>")
    }
}

// ====================================================================== spreadsheets

/**
 * Spreadsheets and CSV files as tables: each sheet in its own pages, its cells shown as Excel shows
 * them (dates as dates, 12.5% as 12.5%, $1,250.00 as written), merged cells merged, column widths
 * kept, and the whole made smaller, then the page wider, until every column fits.
 */
internal object Sheets {
    private const val MAX_ROWS = 20_000
    private const val MAX_COLS = 120
    private const val ROWS_PER_PART = 300

    private class Look(val bold: Boolean, val italic: Boolean, val underline: Boolean, val strike: Boolean, val size: Float?, val color: String?, val fill: String?,
                       val align: String?, val wrap: Boolean, val format: String, val borders: BooleanArray)

    private class CellOut(val text: String, val numeric: Boolean, val look: Look?)

    fun xlsx(zip: ZipFile, name: String): OfficeDoc = with(Office) {
        val wb = zip.xml("xl/workbook.xml") ?: throw IllegalArgumentException("This spreadsheet is damaged: it has no workbook in it.")
        val date1904 = wb.kid("workbookPr")?.a("date1904").let { it == "1" || it == "true" }
        val rels = HashMap<String, String>()
        zip.xml("xl/_rels/workbook.xml.rels")?.kids()?.forEach { r ->
            val id = r.a("Id") ?: return@forEach
            val t = r.a("Target") ?: return@forEach
            rels[id] = if (t.startsWith("/")) t.removePrefix("/") else "xl/" + t.removePrefix("./")
        }
        val strings = zip.xml("xl/sharedStrings.xml")?.kids()?.filter { it.local == "si" }?.map { si ->
            val direct = si.kid("t")
            direct?.text() ?: si.kids().filter { it.local == "r" }.joinToString("") { it.kid("t")?.text().orEmpty() }
        }.orEmpty()
        readTheme(zip)
        val looks = readStyles(zip.xml("xl/styles.xml"))
        val parts = ArrayList<OfficePart>()
        val notes = ArrayList<String>()
        val sheets = wb.kid("sheets")?.kids()?.filter { it.local == "sheet" }.orEmpty()
        for (s in sheets) {
            if (s.a("state") == "hidden" || s.a("state") == "veryHidden") continue
            val path = rels[s.rel() ?: continue] ?: continue
            val root = zip.xml(path) ?: continue
            val sheetName = s.a("name") ?: "Sheet"
            val rows = HashMap<Int, HashMap<Int, CellOut>>()
            val hiddenRows = HashSet<Int>()
            var maxR = -1
            var maxC = -1
            var cut = false
            for (row in root.kid("sheetData")?.kids().orEmpty()) {
                if (row.local != "row") continue
                val rIdx = (row.a("r")?.toIntOrNull() ?: (maxR + 2)) - 1
                if (row.a("hidden") == "1" || row.a("hidden") == "true") { hiddenRows += rIdx; continue }
                if (rIdx >= MAX_ROWS) { cut = true; continue }
                var cIdx = -1
                for (c in row.kids()) {
                    if (c.local != "c") continue
                    cIdx = c.a("r")?.let(::colOf) ?: (cIdx + 1)
                    if (cIdx >= MAX_COLS) { cut = true; continue }
                    val look = c.a("s")?.toIntOrNull()?.let { looks.getOrNull(it) }
                    val v = c.kid("v")?.text()
                    val out: CellOut? = when (c.a("t")) {
                        "s" -> v?.toIntOrNull()?.let { strings.getOrNull(it) }?.let { CellOut(it, false, look) }
                        "inlineStr" -> CellOut(c.kid("is")?.let { i -> i.kid("t")?.text() ?: i.all("t").joinToString("") { it.text() } }.orEmpty(), false, look)
                        "str" -> v?.let { CellOut(it, false, look) }
                        "b" -> v?.let { CellOut(if (it == "1") "TRUE" else "FALSE", false, look) }
                        "e" -> v?.let { CellOut(it, false, look) }
                        "d" -> v?.let { CellOut(it.replace('T', ' ').removeSuffix("Z"), true, look) }
                        else -> v?.toDoubleOrNull()?.let { d -> CellOut(Numbers.show(d, look?.format ?: "General", date1904), true, look) }
                    }
                    if (out == null && (look?.fill == null && look?.borders?.any { it } != true)) continue
                    val cell = out ?: CellOut("", false, look)
                    rows.getOrPut(rIdx) { HashMap() }[cIdx] = cell
                    if (cell.text.isNotEmpty()) { maxR = maxOf(maxR, rIdx); maxC = maxOf(maxC, cIdx) }
                }
            }
            if (cut) notes += "$sheetName: only the first ${MAX_ROWS} rows and $MAX_COLS columns"
            if (maxR < 0) continue
            // Column widths (Excel's in characters), hidden columns left out.
            val widths = FloatArray(maxC + 1) { 8.43f }
            val hiddenCols = HashSet<Int>()
            for (col in root.kid("cols")?.kids().orEmpty()) {
                if (col.local != "col") continue
                val a = (col.a("min")?.toIntOrNull() ?: continue) - 1
                val b = (col.a("max")?.toIntOrNull() ?: continue) - 1
                for (k in a..minOf(b, maxC)) {
                    if (col.a("hidden") == "1" || col.a("hidden") == "true") hiddenCols += k
                    col.a("width")?.toFloatOrNull()?.let { widths[k] = it }
                }
            }
            val merges = root.kid("mergeCells")?.kids()?.filter { it.local == "mergeCell" }?.mapNotNull { m ->
                val ref = m.a("ref") ?: return@mapNotNull null
                val (a, b) = ref.split(':').let { l -> l[0] to l.getOrElse(1) { l[0] } }
                val r0 = rowOf(a); val c0 = colOf(a); val r1 = rowOf(b); val c1 = colOf(b)
                if (r0 == null || c0 == null || r1 == null || c1 == null) null else intArrayOf(r0, c0, r1, c1)
            }.orEmpty()
            val landscapeAsked = root.kid("pageSetup")?.a("orientation") == "landscape"
            // Faint lines between cells, as on screen: a sheet without them is hard to follow on a phone.
            val gridlines = true
            parts += table(sheetName, rows, maxR, maxC, widths, hiddenCols, hiddenRows, merges, landscapeAsked, gridlines, sheets.size > 1)
        }
        if (parts.isEmpty()) parts += OfficePart("<p>This spreadsheet is empty.</p>", 595.3f, 841.9f, floatArrayOf(36f, 36f, 36f, 36f))
        val title = zip.xml("docProps/core.xml")?.kids()?.firstOrNull { it.local == "title" }?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: name.substringAfterLast('/').substringBeforeLast('.')
        OfficeDoc(parts, title, notes.takeIf { it.isNotEmpty() }?.joinToString("; ", postfix = "."))
    }

    fun csv(bytes: ByteArray, name: String, tab: Boolean): OfficeDoc {
        var text = String(bytes, Charsets.UTF_8).removePrefix("﻿")
        if (text.contains('�')) text = String(bytes, Charsets.ISO_8859_1)
        val first = text.lineSequence().firstOrNull().orEmpty()
        val sep = when {
            tab -> '\t'
            first.count { it == ';' } > first.count { it == ',' } -> ';'
            first.count { it == '\t' } > first.count { it == ',' } -> '\t'
            else -> ','
        }
        val rows = HashMap<Int, HashMap<Int, CellOut>>()
        var r = 0
        var c = 0
        var maxC = -1
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        var cut = false
        fun put() {
            val t = cell.toString()
            if (c < MAX_COLS && r < MAX_ROWS) {
                if (t.isNotEmpty()) {
                    val num = t.trim().replace(",", "").let { s -> s.toDoubleOrNull() != null || (s.endsWith("%") && s.dropLast(1).toDoubleOrNull() != null) || (s.startsWith("$") && s.drop(1).toDoubleOrNull() != null) }
                    rows.getOrPut(r) { HashMap() }[c] = CellOut(t, num, null)
                    maxC = maxOf(maxC, c)
                }
            } else cut = true
            cell.setLength(0)
        }
        while (i < text.length) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') { if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false } else cell.append(ch)
            } else when (ch) {
                '"' -> if (cell.isEmpty()) quoted = true else cell.append(ch)
                sep -> { put(); c++ }
                '\r' -> Unit
                '\n' -> { put(); r++; c = 0 }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || c > 0) put()
        val maxR = rows.keys.maxOrNull() ?: -1
        val parts = ArrayList<OfficePart>()
        if (maxR >= 0) {
            // Widths from the longest entry in each column.
            val widths = FloatArray(maxC + 1) { k -> (rows.values.take(500).maxOfOrNull { it[k]?.text?.length ?: 0 } ?: 8).coerceIn(6, 50) * 1.05f + 1f }
            // The first row as headings, in bold.
            val head = Look(true, false, false, false, null, null, "#F2F2F2", null, false, "General", BooleanArray(4))
            rows[0]?.let { row -> for ((k, v) in row) row[k] = CellOut(v.text, false, head) }
            parts += table(name.substringAfterLast('/').substringBeforeLast('.'), rows, maxR, maxC, widths, emptySet(), emptySet(), emptyList(), false, true, false)
        } else parts += OfficePart("<p>This file is empty.</p>", 595.3f, 841.9f, floatArrayOf(36f, 36f, 36f, 36f))
        return OfficeDoc(parts, name.substringAfterLast('/').substringBeforeLast('.'), if (cut) "Only the first $MAX_ROWS rows and $MAX_COLS columns." else null)
    }

    /** "C12" → column 2 (from 0). */
    private fun colOf(ref: String): Int? {
        var n = 0
        var any = false
        for (ch in ref) { if (ch.isLetter()) { n = n * 26 + (ch.uppercaseChar() - 'A' + 1); any = true } else break }
        return if (any) n - 1 else null
    }

    private fun rowOf(ref: String): Int? = ref.dropWhile { it.isLetter() }.toIntOrNull()?.minus(1)

    /** Excel's 64 numbered colours, for files that name colours by number. */
    private val indexed = listOf(
        "000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF", "000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF",
        "800000", "008000", "000080", "808000", "800080", "008080", "C0C0C0", "808080", "9999FF", "993366", "FFFFCC", "CCFFFF", "660066", "FF8080", "0066CC", "CCCCFF",
        "000080", "FF00FF", "FFFF00", "00FFFF", "800080", "800000", "008080", "0000FF", "00CCFF", "CCFFFF", "CCFFCC", "FFFF99", "99CCFF", "FF99CC", "CC99FF", "FFCC99",
        "3366FF", "33CCCC", "99CC00", "FFCC00", "FF9900", "FF6600", "666699", "969696", "003366", "339966", "003300", "333300", "993300", "993366", "333399", "333333",
    )

    /** The workbook's theme colours in Excel's order: light 1, dark 1, light 2, dark 2, six accents, two link colours. */
    private var theme = listOf("FFFFFF", "000000", "E7E6E6", "44546A", "4472C4", "ED7D31", "A5A5A5", "FFC000", "5B9BD5", "70AD47", "0563C1", "954F72")

    private fun readTheme(zip: ZipFile) = with(Office) {
        val scheme = zip.xml("xl/theme/theme1.xml")?.all("clrScheme")?.firstOrNull() ?: return@with
        fun hex(name: String) = scheme.kid(name)?.let { c -> c.kid("srgbClr")?.a("val") ?: c.kid("sysClr")?.a("lastClr") }
        val order = listOf("lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink")
        theme = order.mapIndexed { i, n -> hex(n) ?: theme[i] }
    }

    /** A colour as Excel gives it: written out, a theme colour lightened or darkened by a tint, or a numbered one. */
    private fun color(e: Element?): String? = with(Office) {
        if (e == null || e.a("auto") == "1") return null
        e.a("rgb")?.let { return css(it) }
        val base = e.a("theme")?.toIntOrNull()?.let { theme.getOrNull(it) } ?: e.a("indexed")?.toIntOrNull()?.let { indexed.getOrNull(it) } ?: return null
        val tint = e.a("tint")?.toDoubleOrNull() ?: 0.0
        if (tint == 0.0) return "#$base"
        val r = base.substring(0, 2).toInt(16) / 255.0; val g = base.substring(2, 4).toInt(16) / 255.0; val b = base.substring(4, 6).toInt(16) / 255.0
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
        var l = (mx + mn) / 2
        val d = mx - mn
        val sat = if (d == 0.0) 0.0 else d / (1 - kotlin.math.abs(2 * l - 1))
        val hue = when { d == 0.0 -> 0.0; mx == r -> ((g - b) / d).mod(6.0); mx == g -> (b - r) / d + 2; else -> (r - g) / d + 4 } * 60
        l = if (tint < 0) l * (1 + tint) else l * (1 - tint) + tint
        val c = (1 - kotlin.math.abs(2 * l - 1)) * sat
        val x = c * (1 - kotlin.math.abs((hue / 60).mod(2.0) - 1))
        val m = l - c / 2
        val (r1, g1, b1) = when { hue < 60 -> Triple(c, x, 0.0); hue < 120 -> Triple(x, c, 0.0); hue < 180 -> Triple(0.0, c, x); hue < 240 -> Triple(0.0, x, c); hue < 300 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x) }
        fun hx(v: Double) = ((v + m).coerceIn(0.0, 1.0) * 255).toInt().toString(16).padStart(2, '0').uppercase()
        "#" + hx(r1) + hx(g1) + hx(b1)
    }

    private fun readStyles(root: Element?): List<Look> = with(Office) {
        if (root == null) return emptyList()
        val formats = HashMap<Int, String>()
        root.kid("numFmts")?.kids()?.forEach { f -> f.a("numFmtId")?.toIntOrNull()?.let { id -> formats[id] = f.a("formatCode").orEmpty() } }
        class F(val b: Boolean, val i: Boolean, val u: Boolean, val s: Boolean, val size: Float?, val color: String?)
        val fonts = root.kid("fonts")?.kids()?.filter { it.local == "font" }?.map { f ->
            F(f.kid("b")?.let { (it.a("val") ?: "1") != "0" } == true, f.kid("i")?.let { (it.a("val") ?: "1") != "0" } == true,
                f.kid("u")?.let { (it.a("val") ?: "single") != "none" } == true, f.kid("strike")?.let { (it.a("val") ?: "1") != "0" } == true,
                f.kid("sz")?.a("val")?.toFloatOrNull(), color(f.kid("color")))
        }.orEmpty()
        val fills = root.kid("fills")?.kids()?.filter { it.local == "fill" }?.map { f ->
            val p = f.kid("patternFill")
            if (p == null || (p.a("patternType") ?: "none") == "none") null else color(p.kid("fgColor")) ?: color(p.kid("bgColor"))
        }.orEmpty()
        val borders = root.kid("borders")?.kids()?.filter { it.local == "border" }?.map { b ->
            BooleanArray(4) { k -> b.kid(listOf("top", "right", "bottom", "left")[k])?.a("style")?.let { it != "none" } == true }
        }.orEmpty()
        root.kid("cellXfs")?.kids()?.filter { it.local == "xf" }?.map { x ->
            val f = x.a("fontId")?.toIntOrNull()?.let { fonts.getOrNull(it) }
            val al = x.kid("alignment")
            val id = x.a("numFmtId")?.toIntOrNull() ?: 0
            Look(
                f?.b == true, f?.i == true, f?.u == true, f?.s == true, f?.size, f?.color?.takeIf { it != "#000000" },
                x.a("fillId")?.toIntOrNull()?.let { fills.getOrNull(it) }?.takeIf { it != "#FFFFFF" },
                al?.a("horizontal")?.takeIf { it != "general" }, al?.a("wrapText").let { it == "1" || it == "true" },
                formats[id] ?: Numbers.builtIn(id), x.a("borderId")?.toIntOrNull()?.let { borders.getOrNull(it) } ?: BooleanArray(4),
            )
        }.orEmpty()
    }

    private fun table(
        name: String, rows: Map<Int, Map<Int, CellOut>>, maxR: Int, maxC: Int, widths: FloatArray, hiddenCols: Set<Int>, hiddenRows: Set<Int>,
        merges: List<IntArray>, landscapeAsked: Boolean, gridlines: Boolean, withName: Boolean,
    ): List<OfficePart> {
        val cols = (0..maxC).filter { it !in hiddenCols }
        // Excel's character widths in points at 11 pt type.
        val pts = cols.map { widths[it] * 5.6f + 6f }.toMutableList()
        // The last column has nothing beside it to run on into: it takes its longest text (within reason).
        if (pts.isNotEmpty()) {
            val last = cols.last()
            val longest = rows.values.mapNotNull { it[last] }.filter { !it.numeric && it.look?.wrap != true }.maxOfOrNull { it.text.length } ?: 0
            pts[pts.size - 1] = maxOf(pts.last(), minOf(longest * 5.3f + 6f, 320f))
        }
        val need = pts.sum()
        val margin = 28f
        var w = if (landscapeAsked || need > 595.3f - 2 * margin) 841.9f else 595.3f
        var h = if (w > 600f) 595.3f else 841.9f
        // Smaller type to fit, then a wider page for what still does not.
        val scale = ((w - 2 * margin) / need).coerceIn(0.55f, 1f)
        if (need * scale > w - 2 * margin) { w = need * scale + 2 * margin; h = maxOf(h, w * 0.62f) }
        val base = 10f * scale
        val starts = HashMap<Long, IntArray>()
        val covered = HashSet<Long>()
        for (m in merges) {
            starts[key(m[0], m[1])] = m
            for (r in m[0]..m[2]) for (c in m[1]..m[3]) if (r != m[0] || c != m[1]) covered += key(r, c)
        }
        val visibleRows = (0..maxR).filter { it !in hiddenRows }
        val out = ArrayList<OfficePart>()
        val grid = if (gridlines) "0.5pt solid #D0D0D0" else "none"
        for ((k, chunk) in visibleRows.chunked(ROWS_PER_PART).withIndex()) {
            val sb = StringBuilder()
            if (withName && k == 0) sb.append("<p style=\"font-size:").append(Office.n(base * 1.3f)).append("pt;font-weight:bold;margin-bottom:6pt\">").append(Office.esc(name)).append("</p>")
            sb.append("<table style=\"width:").append(Office.n(need * scale)).append("pt;font-size:").append(Office.n(base)).append("pt\"><colgroup>")
            for (p in pts) sb.append("<col style=\"width:").append(Office.n(p / need * 100f)).append("%\"/>")
            sb.append("</colgroup>")
            for (r in chunk) {
                sb.append("<tr>")
                val spill = HashSet<Int>()
                for ((ci, c) in cols.withIndex()) {
                    val kk = key(r, c)
                    if (kk in covered || c in spill) continue
                    val cell = rows[r]?.get(c)
                    val m = starts[kk]
                    // Text too long for its column runs on over the empty cells beside it, as in Excel.
                    var over = 1
                    if (m == null && cell != null && !cell.numeric && cell.look?.wrap != true && !cell.text.contains('\n')) {
                        val want = cell.text.length * 5.3f * ((cell.look?.size ?: 11f) / 11f) + 6f
                        var have = pts[ci]
                        var k = ci + 1
                        while (have < want && k < cols.size) {
                            val nc = cols[k]
                            val next = rows[r]?.get(nc)
                            if ((next != null && (next.text.isNotEmpty() || next.look?.fill != null)) || key(r, nc) in covered || starts.containsKey(key(r, nc))) break
                            have += pts[k]; spill += nc; over++; k++
                        }
                    }
                    val look = cell?.look
                    val css = StringBuilder("border:").append(grid).append(";padding:1pt 3pt;")
                    look?.borders?.let { b -> listOf("top", "right", "bottom", "left").forEachIndexed { i, s -> if (b[i]) css.append("border-").append(s).append(":0.75pt solid #000000;") } }
                    look?.fill?.let { css.append("background-color:").append(it).append(';') }
                    if (look?.bold == true) css.append("font-weight:bold;")
                    if (look?.italic == true) css.append("font-style:italic;")
                    if (look?.underline == true || look?.strike == true) css.append("text-decoration:").append(listOfNotNull("underline".takeIf { look.underline }, "line-through".takeIf { look.strike }).joinToString(" ")).append(';')
                    look?.color?.let { css.append("color:").append(it).append(';') }
                    look?.size?.takeIf { it != 11f }?.let { css.append("font-size:").append(Office.n(base * it / 11f)).append("pt;") }
                    val align = look?.align ?: if (cell?.numeric == true) "right" else if (cell?.text == "TRUE" || cell?.text == "FALSE") "center" else null
                    when (align) { "right" -> css.append("text-align:right;"); "center", "centerContinuous" -> css.append("text-align:center;"); "justify" -> css.append("text-align:justify;") }
                    if (look?.wrap != true && cell?.numeric == true) css.append("white-space:nowrap;")
                    sb.append("<td")
                    if (over > 1) sb.append(" colspan=\"").append(over).append('"')
                    if (m != null) {
                        val span = cols.count { it in m[1]..m[3] }
                        val rspan = visibleRows.count { it in m[0]..m[2] }.coerceAtMost(chunk.count { it >= r })
                        if (span > 1) sb.append(" colspan=\"").append(span).append('"')
                        if (rspan > 1) sb.append(" rowspan=\"").append(rspan).append('"')
                    }
                    sb.append(" style=\"").append(css).append("\">")
                    val t = cell?.text.orEmpty()
                    sb.append(if (t.isEmpty()) "&#160;" else Office.esc(t).replace("\n", "<br/>"))
                    sb.append("</td>")
                }
                sb.append("</tr>")
            }
            sb.append("</table>")
            out += OfficePart(sb.toString(), w, h, floatArrayOf(margin, margin, margin, margin), startsSection = k == 0, baseSize = base)
        }
        return out
    }

    private fun key(r: Int, c: Int) = r.toLong() * 100_000L + c
}

/** Numbers as Excel shows them, by its format codes. */
internal object Numbers {
    private fun two(v: Int) = if (v in 0..9) "0$v" else v.toString()

    fun builtIn(id: Int): String = when (id) {
        0 -> "General"; 1 -> "0"; 2 -> "0.00"; 3 -> "#,##0"; 4 -> "#,##0.00"
        5 -> "\$#,##0;(\$#,##0)"; 6 -> "\$#,##0;[Red](\$#,##0)"; 7 -> "\$#,##0.00;(\$#,##0.00)"; 8 -> "\$#,##0.00;[Red](\$#,##0.00)"
        9 -> "0%"; 10 -> "0.00%"; 11 -> "0.00E+00"; 12 -> "# ?/?"; 13 -> "# ??/??"
        14 -> "dd/mm/yyyy"; 15 -> "d-mmm-yy"; 16 -> "d-mmm"; 17 -> "mmm-yy"; 18 -> "h:mm AM/PM"; 19 -> "h:mm:ss AM/PM"
        20 -> "h:mm"; 21 -> "h:mm:ss"; 22 -> "dd/mm/yyyy h:mm"
        37 -> "#,##0 ;(#,##0)"; 38 -> "#,##0 ;[Red](#,##0)"; 39 -> "#,##0.00;(#,##0.00)"; 40 -> "#,##0.00;[Red](#,##0.00)"
        44 -> "\$#,##0.00"; 45 -> "mm:ss"; 46 -> "[h]:mm:ss"; 47 -> "mm:ss.0"; 48 -> "##0.0E+0"; 49 -> "@"
        else -> "General"
    }

    fun show(v: Double, format: String, date1904: Boolean = false): String {
        val sections = split(format)
        val sec = when {
            v < 0 && sections.size > 1 -> sections[1]
            v == 0.0 && sections.size > 2 -> sections[2]
            else -> sections[0]
        }
        val f = sec.replace(Regex("\\[(Red|Black|Blue|Green|White|Yellow|Cyan|Magenta|Color ?\\d+)\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[[<>=][^\\]]*\\]"), "")
        val neg = v < 0 && sections.size > 1
        val x = kotlin.math.abs(v)
        if (f.equals("General", true) || f.isBlank()) return general(v)
        if (f == "@") return general(v)
        if (isDate(f)) return date(x, f, date1904)
        return number(x, f, v < 0 && !neg).trim()
    }

    private fun split(f: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        for (c in f) {
            if (c == '"') q = !q
            if (c == ';' && !q) { out += sb.toString(); sb.setLength(0) } else sb.append(c)
        }
        out += sb.toString()
        return out
    }

    private fun general(v: Double): String {
        if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) return v.toLong().toString()
        val s = java.math.BigDecimal(v).round(java.math.MathContext(10)).stripTrailingZeros()
        return if (kotlin.math.abs(v) >= 1e11 || (kotlin.math.abs(v) < 1e-9 && v != 0.0)) String.format(java.util.Locale.US, "%.5E", v) else s.toPlainString()
    }

    private fun stripQuoted(f: String) = f.replace(Regex("\"[^\"]*\""), "").replace(Regex("\\\\."), "")

    fun isDate(f: String): Boolean {
        val s = stripQuoted(f).replace(Regex("\\[\\$[^\\]]*\\]"), "").lowercase()
        return s.any { it == 'y' || it == 'd' || it == 'h' || it == 's' } || (s.contains('m') && !s.contains('0') && !s.contains('#'))
    }

    private fun date(serial: Double, f: String, date1904: Boolean): String {
        val days = Math.floor(serial).toLong()
        var frac = serial - days
        // Excel's 1900 system counts a 29 February 1900 that never was.
        val base = if (date1904) java.time.LocalDate.of(1904, 1, 1) else java.time.LocalDate.of(1899, 12, 30)
        val d = if (!date1904 && days < 61) base.plusDays(days + 1) else base.plusDays(days)
        if (frac < 0) frac = 0.0
        // Seconds rounded to as many decimals as the format shows ("ss.00"), so 27.9999 is 28, not 27.99.
        val z = Regex("[sS]\\.(0+)").find(f)?.groupValues?.get(1)?.length ?: 0
        val unit = Math.pow(10.0, z.toDouble())
        val units = Math.round(frac * 86400 * unit)
        val secs = (units / unit.toLong()).toInt()
        val fracDigits = (units % unit.toLong()).toString().padStart(z, '0')
        val hh = secs / 3600 % 24
        val mm = secs / 60 % 60
        val ss = secs % 60
        val ampm = Regex("AM/PM|A/P", RegexOption.IGNORE_CASE).containsMatchIn(f)
        val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        val wdays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        val sb = StringBuilder()
        var i = 0
        val src = f.replace(Regex("\\[\\$[^\\]]*\\]"), "")
        // Whether an "m" is minutes: after an h or before an s.
        fun minutesAt(pos: Int): Boolean {
            val before = src.substring(0, pos).lowercase().replace(Regex("\"[^\"]*\""), "").trimEnd(':', ' ', '[', ']')
            val after = src.substring(pos).lowercase().dropWhile { it == 'm' }.trimStart(':', ' ', ']')
            return before.endsWith("h") || after.startsWith("s")
        }
        while (i < src.length) {
            val c = src[i]
            val lc = c.lowercaseChar()
            var run = 1
            while (i + run < src.length && src[i + run].lowercaseChar() == lc && lc in "ymdhs") run++
            when {
                c == '"' -> { val end = src.indexOf('"', i + 1).let { if (it < 0) src.length else it }; sb.append(src, i + 1, end); i = end + 1; continue }
                c == '\\' && i + 1 < src.length -> { sb.append(src[i + 1]); i += 2; continue }
                src.regionMatches(i, "AM/PM", 0, 5, true) -> { sb.append(if (hh < 12) "AM" else "PM"); i += 5; continue }
                src.regionMatches(i, "A/P", 0, 3, true) -> { val up = src[i] == 'A'; sb.append(if (hh < 12) (if (up) "A" else "a") else (if (up) "P" else "p")); i += 3; continue }
                lc == 'y' -> sb.append(if (run <= 2) two(d.year % 100) else d.year.toString())
                lc == 'd' -> sb.append(when (run) { 1 -> d.dayOfMonth.toString(); 2 -> two(d.dayOfMonth); 3 -> wdays[d.dayOfWeek.value - 1].take(3); else -> wdays[d.dayOfWeek.value - 1] })
                lc == 'm' && run <= 2 && minutesAt(i) -> sb.append(if (run == 1) mm.toString() else two(mm))
                lc == 'm' -> sb.append(when (run) { 1 -> d.monthValue.toString(); 2 -> two(d.monthValue); 3 -> months[d.monthValue - 1].take(3); 5 -> months[d.monthValue - 1].take(1); else -> months[d.monthValue - 1] })
                lc == 'h' -> { val h = if (ampm) (if (hh % 12 == 0) 12 else hh % 12) else hh; sb.append(if (run == 1) h.toString() else two(h)) }
                lc == 's' -> sb.append(if (run == 1) ss.toString() else two(ss))
                c == '[' -> { val end = src.indexOf(']', i); if (end > i) { val inner = src.substring(i + 1, end).lowercase(); sb.append(when { inner.startsWith("h") -> (Math.floor(serial * 24)).toLong().toString(); inner.startsWith("m") -> (Math.floor(serial * 1440)).toLong().toString(); else -> "" }); i = end + 1; continue } else sb.append(c) }
                c == '.' && i + 1 < src.length && src[i + 1] == '0' -> { sb.append('.'); var k = i + 1; var zz = 0; while (k < src.length && src[k] == '0') { zz++; k++ }; sb.append(fracDigits.padEnd(zz, '0').take(zz)); i = k; continue }
                c == '_' || c == '*' -> { i += 2; continue }
                else -> sb.append(c)
            }
            i += if (lc in "ymdhs") run else 1
        }
        return sb.toString().trim()
    }

    private fun number(x: Double, f: String, minus: Boolean): String {
        val clean = f.replace(Regex("\\[\\$([^\\]-]*)(-[^\\]]*)?\\]")) { it.groupValues[1] }
        // Literal text around the number: "$", "kg", "x".
        val lead = StringBuilder()
        val tail = StringBuilder()
        var core = StringBuilder()
        var seenDigit = false
        var i = 0
        while (i < clean.length) {
            val c = clean[i]
            when {
                c == '"' -> { val end = clean.indexOf('"', i + 1).let { if (it < 0) clean.length else it }; (if (seenDigit) tail else lead).append(clean, i + 1, end); i = end + 1; continue }
                c == '\\' && i + 1 < clean.length -> { (if (seenDigit) tail else lead).append(clean[i + 1]); i += 2; continue }
                c == '_' || c == '*' -> { if (c == '_') (if (seenDigit) tail else lead).append(' '); i += 2; continue }
                c in "0#?.,%Ee+-" && !(c in "+-" && !core.endsWith("E") && !core.endsWith("e")) && !(c in "Ee" && !seenDigit) -> { core.append(c); if (c in "0#?") seenDigit = true }
                c == '(' || c == ')' || c == ' ' || c == '$' || c == '€' || c == '£' || c == '¥' || c.isLetter() || c == '/' || c == ':' -> (if (seenDigit) tail else lead).append(c)
                else -> (if (seenDigit) tail else lead).append(c)
            }
            i++
        }
        var v = x
        val pct = core.count { it == '%' }
        repeat(pct) { v *= 100 }
        var c = core.toString().replace("%", "")
        // Trailing commas scale by thousands.
        while (c.endsWith(",")) { v /= 1000; c = c.dropLast(1) }
        if (c.contains('E', true)) {
            val decs = c.substringAfter('.', "").takeWhile { it == '0' || it == '#' }.length
            val s = String.format(java.util.Locale.US, "%.${decs}E", v).replace("E+0", "E+").replace("E-0", "E-")
            return (if (minus) "-" else "") + lead + s.replace(Regex("E([+-])(\\d)$"), "E$10$2") + tail + "%".repeat(pct)
        }
        val intPart = c.substringBefore('.')
        val decPart = c.substringAfter('.', "")
        val fixed = decPart.count { it == '0' }
        val optional = decPart.count { it == '#' || it == '?' }
        val grouping = intPart.contains(',')
        val minInt = intPart.count { it == '0' }
        var s = java.math.BigDecimal(v).setScale(fixed + optional, java.math.RoundingMode.HALF_UP).toPlainString()
        if (optional > 0 && s.contains('.')) {
            var cut = s.length
            var dropped = 0
            while (dropped < optional && s[cut - 1] == '0') { cut--; dropped++ }
            s = s.substring(0, cut).trimEnd('.')
        }
        var ip = s.substringBefore('.')
        val dp = s.substringAfter('.', "")
        if (ip == "0" && minInt == 0) ip = ""
        ip = ip.padStart(minInt, '0')
        if (grouping) ip = ip.reversed().chunked(3).joinToString(",").reversed()
        val num = ip + if (dp.isNotEmpty()) ".$dp" else if (fixed > 0) "." else ""
        // "#" and "?" show nothing for a nought ("-"?? in accounting formats); "0" shows it.
        return (if (minus) "-" else "") + lead.toString() + num.ifEmpty { if (minInt == 0 && fixed == 0) "" else "0" } + tail.toString() + "%".repeat(pct)
    }
}
