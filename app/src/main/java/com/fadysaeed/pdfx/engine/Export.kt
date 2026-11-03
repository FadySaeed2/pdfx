package com.fadysaeed.pdfx.engine

import java.io.File
import java.io.OutputStream
import java.io.Writer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What a file holds, in reading order, ready to be written as Word, text or Excel: paragraphs as the
 * reflowed view reads them, tables as the table engine reads them, and pictures cut from the pages.
 */
sealed interface Block {
    class Text(val text: String, val level: Int = 0, val spans: List<Span> = emptyList(), val marker: String = "") : Block
    class Table(val table: TableModel) : Block
    /** A PNG of part of a page, [widthPx] × [heightPx] at [dpi]; [caption] and [notes] for a figure. */
    class Picture(val png: File, val widthPx: Int, val heightPx: Int, val dpi: Int, val caption: String = "", val notes: List<String> = emptyList()) : Block
}

/** A table cell as plain words: superscript marks and the marks for glyphs drawn as pictures taken off. */
fun plainCell(s: String): String = s.replace("^", "").replace(Regex("⟦([^|⟧]*)(\\|\\d+)?⟧"), "$1").trim()

private fun isArabicText(text: String): Boolean {
    for (ch in text) {
        if (!ch.isLetter()) continue
        val b = Character.UnicodeBlock.of(ch)
        return b == Character.UnicodeBlock.ARABIC || b == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A ||
            b == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B || b == Character.UnicodeBlock.ARABIC_SUPPLEMENT
    }
    return false
}

/** Text safe inside XML: the five special marks escaped, characters XML cannot hold left out. */
internal fun xml(s: String): String {
    val sb = StringBuilder(s.length + 16)
    for (c in s) when {
        c == '&' -> sb.append("&amp;")
        c == '<' -> sb.append("&lt;")
        c == '>' -> sb.append("&gt;")
        c == '"' -> sb.append("&quot;")
        c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
        c.code < 0x20 || c == '￾' || c == '￿' -> Unit
        else -> sb.append(c)
    }
    return sb.toString()
}

// ---------------------------------------------------------------------------------- plain text

/**
 * Plain text in reading order: a blank line between paragraphs, headings as they are, list items
 * with their marks, table cells split by tabs (so a table pasted into a sheet falls into its cells),
 * and a figure as its caption.
 */
object TextExport {
    fun write(blocks: List<Block>, out: Writer) {
        var first = true
        fun para(s: String) {
            if (s.isBlank()) return
            if (!first) out.write("\n")
            out.write(s.trimEnd()); out.write("\n")
            first = false
        }
        for (b in blocks) when (b) {
            is Block.Text -> para(if (b.marker.isNotEmpty()) "${b.marker} ${b.text}" else b.text)
            is Block.Table -> para(tableText(b.table))
            is Block.Picture -> para(listOf(b.caption.ifBlank { "[Picture]" }).plus(b.notes).joinToString("\n"))
        }
    }

    private fun tableText(t: TableModel): String = buildString {
        if (t.caption.isNotBlank()) append(t.caption.trim()).append('\n')
        if (t.header.any { it.isNotBlank() }) append(t.header.joinToString("\t") { plainCell(it) }).append('\n')
        for (r in t.rows) append(r.cells.joinToString("\t") { plainCell(it) }).append('\n')
        for (n in t.notes) append(n.trim()).append('\n')
    }
}

// ---------------------------------------------------------------------------------- Word

/**
 * A Word file (.docx) to edit, written by hand as the XML Word reads: paragraphs that flow,
 * headings as Word's own headings (so they show in its navigation pane), bold and italic, list
 * items, tables as real Word tables (header row repeated on each page, group rows across the
 * table), pictures in their places with captions, and Arabic paragraphs right to left.
 */
object DocxExport {
    private const val EMU_PER_INCH = 914400L
    /** Text width on A4 with 2.5 cm margins, in inches. */
    private const val TEXT_WIDTH_IN = 6.27

    fun write(blocks: List<Block>, out: OutputStream, title: String = "") {
        val pictures = blocks.filterIsInstance<Block.Picture>()
        ZipOutputStream(out).use { z ->
            fun entry(name: String, body: String) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>""")
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>""")
            entry("docProps/core.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${xml(title)}</dc:title></cp:coreProperties>""")
            entry("word/_rels/document.xml.rels", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""")
                pictures.forEachIndexed { i, _ -> append("""<Relationship Id="rImg$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image$i.png"/>""") }
                append("</Relationships>")
            })
            entry("word/styles.xml", STYLES)
            z.putNextEntry(ZipEntry("word/document.xml"))
            val w = z.bufferedWriter(Charsets.UTF_8)
            w.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>""")
            var img = 0
            for (b in blocks) when (b) {
                is Block.Text -> w.write(paragraph(b))
                is Block.Table -> w.write(table(b.table))
                is Block.Picture -> { w.write(picture(b, img)); img++ }
            }
            w.write("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1418" w:right="1418" w:bottom="1418" w:left="1418" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>""")
            w.flush()
            z.closeEntry()
            pictures.forEachIndexed { i, p ->
                z.putNextEntry(ZipEntry("word/media/image$i.png"))
                p.png.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    private fun pPr(style: String?, rtl: Boolean, extra: String = ""): String {
        if (style == null && !rtl && extra.isEmpty()) return ""
        return "<w:pPr>" + (style?.let { "<w:pStyle w:val=\"$it\"/>" } ?: "") + (if (rtl) "<w:bidi/>" else "") + extra + "</w:pPr>"
    }

    private fun run(text: String, bold: Boolean = false, italic: Boolean = false, rtl: Boolean = false, sup: Boolean = false): String {
        if (text.isEmpty()) return ""
        val props = (if (bold) "<w:b/><w:bCs/>" else "") + (if (italic) "<w:i/><w:iCs/>" else "") +
            (if (sup) "<w:vertAlign w:val=\"superscript\"/>" else "") + (if (rtl) "<w:rtl/>" else "")
        return "<w:r>" + (if (props.isNotEmpty()) "<w:rPr>$props</w:rPr>" else "") + "<w:t xml:space=\"preserve\">${xml(text)}</w:t></w:r>"
    }

    private fun paragraph(b: Block.Text): String {
        val rtl = isArabicText(b.text)
        val style = when {
            b.level == 1 -> "Heading1"
            b.level == 2 -> "Heading2"
            b.level >= 3 -> "Heading3"
            b.marker.isNotEmpty() -> "ListParagraph"
            else -> null
        }
        val sb = StringBuilder("<w:p>")
        sb.append(pPr(style, rtl))
        if (b.marker.isNotEmpty()) sb.append(run(b.marker, rtl = rtl)).append("<w:r><w:tab/></w:r>")
        // Cut the text where its bold or italic changes.
        val cuts = sortedSetOf(0, b.text.length)
        for (s in b.spans) { cuts += s.start.coerceIn(0, b.text.length); cuts += s.end.coerceIn(0, b.text.length) }
        val points = cuts.toList()
        for (k in 0 until points.size - 1) {
            val a = points[k]; val e = points[k + 1]
            if (e <= a) continue
            val on = b.spans.filter { it.start <= a && it.end >= e }
            sb.append(run(b.text.substring(a, e), bold = on.any { it.bold }, italic = on.any { it.italic }, rtl = rtl))
        }
        sb.append("</w:p>")
        return sb.toString()
    }

    /** A cell's words with ^superscripts^ raised. */
    private fun cellRuns(s: String, bold: Boolean, rtl: Boolean): String {
        val flat = s.replace(Regex("⟦([^|⟧]*)(\\|\\d+)?⟧"), "$1")
        val sb = StringBuilder()
        var i = 0
        while (i < flat.length) {
            val open = flat.indexOf('^', i)
            if (open < 0) { sb.append(run(flat.substring(i), bold, rtl = rtl)); break }
            val close = flat.indexOf('^', open + 1)
            if (close < 0) { sb.append(run(flat.substring(i).replace("^", ""), bold, rtl = rtl)); break }
            sb.append(run(flat.substring(i, open), bold, rtl = rtl))
            sb.append(run(flat.substring(open + 1, close), bold, rtl = rtl, sup = true))
            i = close + 1
        }
        return sb.toString()
    }

    private fun cell(s: String, bold: Boolean, span: Int = 1, shade: Boolean = false): String {
        val rtl = isArabicText(s)
        val tcPr = "<w:tcPr>" + (if (span > 1) "<w:gridSpan w:val=\"$span\"/>" else "") +
            (if (shade) "<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"EDEDED\"/>" else "") + "</w:tcPr>"
        // Lines inside a cell stay lines.
        val paras = s.split('\n').joinToString("") { line -> "<w:p>${pPr("TableText", rtl)}${cellRuns(line, bold, rtl)}</w:p>" }
        return "<w:tc>$tcPr$paras</w:tc>"
    }

    private fun table(t: TableModel): String {
        val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.cells.size } ?: 0, 1)
        val sb = StringBuilder()
        if (t.caption.isNotBlank()) sb.append("<w:p>${pPr("Caption", isArabicText(t.caption), "<w:keepNext/>")}${run(t.caption.trim(), bold = true)}</w:p>")
        sb.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"5000\" w:type=\"pct\"/><w:tblLook w:val=\"04A0\" w:firstRow=\"1\" w:lastRow=\"0\" w:firstColumn=\"1\" w:lastColumn=\"0\" w:noHBand=\"0\" w:noVBand=\"1\"/></w:tblPr><w:tblGrid>")
        val colW = (9070 / cols).coerceAtLeast(400)
        repeat(cols) { sb.append("<w:gridCol w:w=\"$colW\"/>") }
        sb.append("</w:tblGrid>")
        if (t.header.any { it.isNotBlank() }) {
            sb.append("<w:tr><w:trPr><w:tblHeader/></w:trPr>")
            for (c in 0 until cols) sb.append(cell(t.header.getOrElse(c) { "" }, bold = true, shade = true))
            sb.append("</w:tr>")
        }
        for (r in t.rows) {
            sb.append("<w:tr><w:trPr><w:cantSplit/></w:trPr>")
            if (r.group) sb.append(cell(r.cells.filter { it.isNotBlank() }.joinToString(" "), bold = true, span = cols))
            else for (c in 0 until cols) sb.append(cell(r.cells.getOrElse(c) { "" }, bold = false))
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        for (n in t.notes) if (n.isNotBlank()) sb.append("<w:p>${pPr("Note", isArabicText(n))}${run(n.trim())}</w:p>")
        if (t.notes.isEmpty()) sb.append("<w:p/>")
        return sb.toString()
    }

    private fun picture(p: Block.Picture, i: Int): String {
        var wIn = p.widthPx.toDouble() / p.dpi
        var hIn = p.heightPx.toDouble() / p.dpi
        if (wIn > TEXT_WIDTH_IN) { hIn *= TEXT_WIDTH_IN / wIn; wIn = TEXT_WIDTH_IN }
        if (hIn > 8.5) { wIn *= 8.5 / hIn; hIn = 8.5 }
        val cx = (wIn * EMU_PER_INCH).toLong()
        val cy = (hIn * EMU_PER_INCH).toLong()
        val sb = StringBuilder()
        sb.append("<w:p>${pPr("Figure", false, "<w:keepNext/>")}<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"${i + 1}\" name=\"Picture ${i + 1}\"/><wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"${i + 1}\" name=\"image$i.png\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"rImg$i\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
        if (p.caption.isNotBlank()) sb.append("<w:p>${pPr("Caption", isArabicText(p.caption))}${run(p.caption.trim(), bold = true)}</w:p>")
        for (n in p.notes) if (n.isNotBlank()) sb.append("<w:p>${pPr("Note", isArabicText(n))}${run(n.trim())}</w:p>")
        return sb.toString()
    }

    private val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:eastAsia="Calibri" w:cs="Arial"/><w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="en-AU" w:bidi="ar-EG"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="276" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style><w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="360" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr><w:rPr><w:b/><w:bCs/><w:sz w:val="32"/><w:szCs w:val="32"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="240" w:after="80"/><w:outlineLvl w:val="1"/></w:pPr><w:rPr><w:b/><w:bCs/><w:sz w:val="26"/><w:szCs w:val="26"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before="200" w:after="60"/><w:outlineLvl w:val="2"/></w:pPr><w:rPr><w:b/><w:bCs/><w:sz w:val="23"/><w:szCs w:val="23"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="ListParagraph"><w:name w:val="List Paragraph"/><w:basedOn w:val="Normal"/><w:qFormat/><w:pPr><w:tabs><w:tab w:val="left" w:pos="720"/></w:tabs><w:spacing w:after="80"/><w:ind w:left="720" w:hanging="360"/></w:pPr></w:style><w:style w:type="paragraph" w:styleId="Caption"><w:name w:val="caption"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:pPr><w:spacing w:before="200" w:after="80"/></w:pPr><w:rPr><w:sz w:val="20"/><w:szCs w:val="20"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Note"><w:name w:val="Table Note"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="60"/></w:pPr><w:rPr><w:sz w:val="18"/><w:szCs w:val="18"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="TableText"><w:name w:val="Table Text"/><w:basedOn w:val="Normal"/><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr><w:rPr><w:sz w:val="19"/><w:szCs w:val="19"/></w:rPr></w:style><w:style w:type="paragraph" w:styleId="Figure"><w:name w:val="Figure"/><w:basedOn w:val="Normal"/><w:pPr><w:jc w:val="center"/><w:spacing w:before="200" w:after="60"/></w:pPr></w:style><w:style w:type="table" w:default="1" w:styleId="TableNormal"><w:name w:val="Normal Table"/><w:tblPr><w:tblInd w:w="0" w:type="dxa"/><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style><w:style w:type="table" w:styleId="TableGrid"><w:name w:val="Table Grid"/><w:basedOn w:val="TableNormal"/><w:tblPr><w:tblBorders><w:top w:val="single" w:sz="4" w:space="0" w:color="A6A6A6"/><w:left w:val="single" w:sz="4" w:space="0" w:color="A6A6A6"/><w:bottom w:val="single" w:sz="4" w:space="0" w:color="A6A6A6"/><w:right w:val="single" w:sz="4" w:space="0" w:color="A6A6A6"/><w:insideH w:val="single" w:sz="4" w:space="0" w:color="A6A6A6"/><w:insideV w:val="single" w:sz="4" w:space="0" w:color="A6A6A6"/></w:tblBorders><w:tblCellMar><w:top w:w="40" w:type="dxa"/><w:bottom w:w="40" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style></w:styles>"""
}

// ---------------------------------------------------------------------------------- Excel

/**
 * An Excel workbook (.xlsx) of a file's tables, one sheet each, named by page and table number.
 * Numbers go in as numbers (1,234.5 → 1234.5; 45% → 0.45 shown as a percentage), so they can be
 * added up and sorted; everything else as text. The header row is bold and stays in view while
 * scrolling; a group row runs across the table; the caption sits above and the notes below.
 */
object XlsxExport {
    private val number = Regex("""^[-−–]?(\d{1,3}(,\d{3})+|\d+)(\.\d+)?$""")
    private val percent = Regex("""^[-−–]?\d+(\.\d+)?\s?%$""")

    /** The value a cell holds as a number, and whether it is a percentage; null when it is words. */
    fun numberOf(s: String): Pair<Double, Boolean>? {
        val t = s.trim()
        if (t.isEmpty() || t.length > 24) return null
        val neg = t.startsWith("-") || t.startsWith("−") || t.startsWith("–")
        if (percent.matches(t)) {
            val v = t.trimStart('-', '−', '–').removeSuffix("%").trim().toDoubleOrNull() ?: return null
            return (if (neg) -v else v) / 100.0 to true
        }
        if (!number.matches(t)) return null
        // A number with a leading zero ("007", an ID or a code) stays words.
        val digits = t.trimStart('-', '−', '–')
        if (digits.length > 1 && digits[0] == '0' && digits[1] != '.') return null
        val v = digits.replace(",", "").toDoubleOrNull() ?: return null
        return (if (neg) -v else v) to false
    }

    /** "p47 Table 5-1": a sheet name Excel takes (31 marks at most, none of []:*?/\, not used twice). */
    fun sheetName(t: TableModel, k: Int, used: MutableSet<String>): String {
        val base = ("p${t.page + 1} " + (if (t.number.isNotBlank()) "Table ${t.number}" else "table ${k + 1}"))
            .replace(Regex("""[\[\]:*?/\\]"""), "-").take(31)
        var name = base
        var n = 2
        while (name.lowercase() in used) { val tail = " ($n)"; name = base.take(31 - tail.length) + tail; n++ }
        used += name.lowercase()
        return name
    }

    private fun col(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.append('A' + r); n = (n - 1) / 26 }
        return sb.reverse().toString()
    }

    fun write(tables: List<TableModel>, out: OutputStream) {
        require(tables.isNotEmpty()) { "No tables to write." }
        val used = HashSet<String>()
        val names = tables.mapIndexed { k, t -> sheetName(t, k, used) }
        ZipOutputStream(out).use { z ->
            fun entry(name: String, body: String) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
            entry("[Content_Types].xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
                names.indices.forEach { append("""<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""") }
                append("</Types>")
            })
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            entry("xl/workbook.xml", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
                names.forEachIndexed { i, n -> append("""<sheet name="${xml(n)}" sheetId="${i + 1}" r:id="rS${i + 1}"/>""") }
                append("</sheets></workbook>")
            })
            entry("xl/_rels/workbook.xml.rels", buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                names.indices.forEach { append("""<Relationship Id="rS${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""") }
                append("""<Relationship Id="rStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            })
            // Styles: 0 plain, 1 bold header (shaded, wrapped), 2 percentage, 3 wrapped text, 4 bold caption, 5 small note.
            entry("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="3"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font><font><i/><sz val="9"/><name val="Calibri"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFEDEDED"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="6"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1" applyAlignment="1"><alignment wrapText="1" vertical="top"/></xf><xf numFmtId="10" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment wrapText="1" vertical="top"/></xf><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/><xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
            tables.forEachIndexed { i, t -> entry("xl/worksheets/sheet${i + 1}.xml", sheet(t)) }
        }
    }

    private fun sheet(t: TableModel): String {
        val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.cells.size } ?: 0, 1)
        val rows = ArrayList<String>()
        val merges = ArrayList<String>()
        val widths = IntArray(cols) { 8 }
        var r = 0
        fun text(c: Int, s: String, style: Int) = """<c r="${col(c)}${r + 1}" t="inlineStr" s="$style"><is><t xml:space="preserve">${xml(s)}</t></is></c>"""
        fun row(cells: String) { rows += """<row r="${r + 1}">$cells</row>"""; r++ }
        if (t.caption.isNotBlank()) { row(text(0, t.caption.trim(), 4)); row("") }
        var headerRow = -1
        if (t.header.any { it.isNotBlank() }) {
            headerRow = r
            row((0 until cols).joinToString("") { c -> val s = plainCell(t.header.getOrElse(c) { "" }); widths[c] = maxOf(widths[c], s.length.coerceAtMost(40)); text(c, s, 1) })
        }
        for (tr in t.rows) {
            if (tr.group) {
                val s = tr.cells.map(::plainCell).filter { it.isNotBlank() }.joinToString(" ")
                if (cols > 1) merges += "A${r + 1}:${col(cols - 1)}${r + 1}"
                row(text(0, s, 1))
                continue
            }
            val cells = StringBuilder()
            for (c in 0 until cols) {
                val s = plainCell(tr.cells.getOrElse(c) { "" })
                if (s.isEmpty()) continue
                widths[c] = maxOf(widths[c], s.length.coerceAtMost(60))
                val n = numberOf(s)
                cells.append(
                    if (n == null) text(c, s, if (s.length > 40) 3 else 0)
                    else """<c r="${col(c)}${r + 1}"${if (n.second) " s=\"2\"" else ""}><v>${n.first}</v></c>""",
                )
            }
            row(cells.toString())
        }
        val notes = t.notes.filter { it.isNotBlank() }
        if (notes.isNotEmpty()) { row(""); for (n in notes) row(text(0, n.trim(), 5)) }
        return buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
            if (headerRow >= 0) append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="${headerRow + 1}" topLeftCell="A${headerRow + 2}" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
            append("<cols>")
            for (c in 0 until cols) append("""<col min="${c + 1}" max="${c + 1}" width="${(widths[c] * 1.1 + 2).coerceAtMost(60.0)}" customWidth="1"/>""")
            append("</cols><sheetData>")
            rows.forEach { append(it) }
            append("</sheetData>")
            if (merges.isNotEmpty()) { append("""<mergeCells count="${merges.size}">"""); merges.forEach { append("""<mergeCell ref="$it"/>""") }; append("</mergeCells>") }
            append("</worksheet>")
        }
    }
}
