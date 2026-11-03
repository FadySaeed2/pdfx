package com.fadysaeed.pdfx.engine

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DocumentWriter
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Rect
import java.io.File

/**
 * Lays out what [Office] made of a Word file or a spreadsheet and writes it as a PDF: each part on
 * its own page size, the header and footer drawn on every page with its number, the links kept
 * and the headings made into the PDF's contents list.
 */
object OfficePdf {
    class Result(val pages: Int, val title: String, val note: String?)

    private fun page(body: String, top: Float, right: Float, bottom: Float, left: Float) =
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><style>@page{margin:${Office.n(top)}pt ${Office.n(right)}pt ${Office.n(bottom)}pt ${Office.n(left)}pt}" +
            Office.BASE_CSS + "</style></head><body>" + body + "</body></html>"

    /** Writes [path] (named [name], which says what kind of file it is) as a PDF to [out]. [progress] hears (page, pages so far). */
    fun toPdf(path: String, name: String, out: String, work: File, progress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val doc = Office.read(path, name)
        work.mkdirs()
        val made = ArrayList<File>()
        fun file(html: String): File = File(work, "part-${made.size}.html").also { it.writeText(html); made += it }
        fun open(part: OfficePart): Document {
            val body = part.body.replace(Office.PAGE, "").replace(Office.PAGES, "")
            val f = file(page(body, part.margin[0], part.margin[1], part.margin[2], part.margin[3]))
            val d = Document.openDocument(f.absolutePath)
            d.layout(part.w, part.h, part.baseSize)
            return d
        }
        try {
            val needTotal = doc.parts.any { p -> listOfNotNull(p.header, p.footer, p.firstHeader, p.firstFooter).any { it.contains(Office.PAGES) } }
            var total = 0
            if (needTotal) for (p in doc.parts) { val d = open(p); total += d.countPages(); d.destroy() }
            // Headers and footers laid out once for each text they have (a page number makes each page its own).
            class Laid(val d: Document, val shift: Float)
            val laid = HashMap<String, Laid>()
            fun band(html: String, part: OfficePart, footer: Boolean): Laid = laid.getOrPut("$footer|${part.w}x${part.h}|$html") {
                val top = if (footer) 0f else part.headerAt
                val f = file(page(html, top, part.margin[1], 0f, part.margin[3]))
                val d = Document.openDocument(f.absolutePath)
                d.layout(part.w, part.h, 11f)
                var shift = 0f
                if (footer) {
                    // The footer's foot sits its distance above the page's bottom edge.
                    val bottom = contentBottom(d)
                    shift = part.h - part.footerAt - bottom
                }
                Laid(d, shift)
            }
            val writer = DocumentWriter(out, "pdf", "compress")
            class Link(val page: Int, val r: Rect, val uri: String)
            val links = ArrayList<Link>()
            val outline = ArrayList<Books.Entry>()
            var n = 0
            try {
                for (part in doc.parts) {
                    val d = open(part)
                    try {
                        val count = d.countPages()
                        val start = n
                        for (k in 0 until count) {
                            progress(n + 1, if (needTotal) total else maxOf(n + 1, count))
                            val p = d.loadPage(k)
                            try {
                                val dev = writer.beginPage(Rect(0f, 0f, part.w, part.h))
                                p.run(dev, Matrix.Identity(), null)
                                runCatching { p.links }.getOrNull()?.forEach { l -> if (l.isExternal && l.uri != null) links += Link(n, l.bounds, l.uri) }
                                val first = part.startsSection && k == 0
                                val head = if (first && part.firstHeader != null) part.firstHeader else part.header
                                val foot = if (first && part.firstFooter != null) part.firstFooter else part.footer
                                for ((html, isFoot) in listOf(head to false, foot to true)) {
                                    if (html.isNullOrBlank()) continue
                                    val text = html.replace(Office.PAGE, (n + 1).toString()).replace(Office.PAGES, (if (needTotal) total else n + 1).toString())
                                    val b = band(text, part, isFoot)
                                    if (b.d.countPages() == 0) continue
                                    val bp = b.d.loadPage(0)
                                    try { bp.run(dev, Matrix(1f, 0f, 0f, 1f, 0f, b.shift), null) } finally { bp.destroy() }
                                }
                                writer.endPage()
                            } finally {
                                p.destroy()
                            }
                            n++
                        }
                        outline += entries(d, runCatching { d.loadOutline() }.getOrNull(), start)
                    } finally {
                        d.destroy()
                    }
                }
            } finally {
                writer.close()
                writer.destroy()
                laid.values.forEach { it.d.destroy() }
            }
            if (n == 0) throw IllegalArgumentException("Nothing in this file could be laid out.")
            // Links and the contents list go in afterwards, as the writer cannot make them.
            val pdf = Document.openDocument(out) as PDFDocument
            try {
                for ((pg, group) in links.groupBy { it.page }) {
                    val p = pdf.loadPage(pg) as PDFPage
                    try { group.forEach { l -> runCatching { p.createLink(l.r, l.uri) } } } finally { p.destroy() }
                }
                Books.writeOutline(pdf, outline)
                runCatching { pdf.setMetaData("info:Title", doc.title) }
                pdf.save(out, "garbage=compact,compress=yes")
            } finally {
                pdf.destroy()
            }
            return Result(n, doc.title, doc.note)
        } finally {
            made.forEach { it.delete() }
        }
    }

    private fun entries(d: Document, items: Array<Outline>?, offset: Int): List<Books.Entry> = items.orEmpty().mapNotNull { o ->
        val page = runCatching { d.pageNumberFromLocation(d.resolveLink(o.uri)) }.getOrDefault(-1)
        val kids = entries(d, o.down, offset)
        if (o.title.isNullOrBlank() || (page < 0 && kids.isEmpty())) null
        else Books.Entry(o.title.trim(), if (page >= 0) page + offset else kids.first().page, kids)
    }

    /** How far down the first page of [d] anything is drawn: words or pictures. */
    private fun contentBottom(d: Document): Float {
        if (d.countPages() == 0) return 0f
        val p = d.loadPage(0)
        var bottom = 0f
        try {
            val st = p.toStructuredText("preserve-images")
            try {
                for (b in st.blocks ?: emptyArray()) {
                    // Text blocks, and picture blocks as MuPDF lists them among the text.
                    bottom = maxOf(bottom, b.bbox.y1)
                }
                st.walk(object : com.artifex.mupdf.fitz.StructuredTextWalker {
                    override fun onImageBlock(bbox: Rect, transform: Matrix, image: com.artifex.mupdf.fitz.Image) { bottom = maxOf(bottom, bbox.y1) }
                    override fun beginTextBlock(bbox: Rect, bidi: Int) { bottom = maxOf(bottom, bbox.y1) }
                    override fun endTextBlock() {}
                    override fun beginLine(bbox: Rect, wmode: Int, dir: com.artifex.mupdf.fitz.Point) {}
                    override fun endLine() {}
                    override fun onChar(c: Int, origin: com.artifex.mupdf.fitz.Point, font: com.artifex.mupdf.fitz.Font, size: Float, quad: com.artifex.mupdf.fitz.Quad, argb: Int, flags: Int, bidi: Int) {}
                    override fun beginStruct(standard: String?, raw: String?, index: Int) {}
                    override fun endStruct() {}
                    override fun onVector(bbox: Rect, info: com.artifex.mupdf.fitz.StructuredTextWalker.VectorInfo, argb: Int) { bottom = maxOf(bottom, bbox.y1) }
                })
            } finally {
                st.destroy()
            }
        } finally {
            p.destroy()
        }
        return bottom
    }
}
