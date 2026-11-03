package com.fadysaeed.pdfx.engine

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DocumentWriter
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import java.io.File

/**
 * E-books (EPUB, MOBI, FB2) and comic archives (CBZ) made into PDF. MuPDF lays the book out on
 * pages of the chosen size and type size; the words stay real words (search, copy, highlight), the
 * pictures come along, the book's contents become the PDF's contents list, and its links still go
 * where they went. Books locked with DRM cannot be read by anything but their own shop's app.
 */
object Books {
    enum class Size(val label: String, val w: Float, val h: Float) {
        PHONE("Phone", 312f, 600f),
        A5("A5", 420f, 595f),
        A4("A4", 595f, 842f),
    }

    val extensions = setOf("epub", "mobi", "fb2", "cbz", "xps")

    fun isBook(name: String) = name.substringAfterLast('.', "").lowercase() in extensions

    class Result(val pages: Int, val title: String)

    /** Writes [path] as a PDF to [out]; [progress] hears (page written, pages in all). */
    fun toPdf(path: String, out: String, size: Size, fontSize: Float, progress: (Int, Int) -> Unit = { _, _ -> }): Result {
        val doc = Document.openDocument(path)
        try {
            if (doc.needsPassword()) throw IllegalArgumentException("This book is locked (DRM): only the app of the shop it came from can open it.")
            // Margins come from the book's own style; the page is the size asked for.
            if (doc.isReflowable) doc.layout(size.w, size.h, fontSize)
            val n = doc.countPages()
            if (n <= 0) throw IllegalArgumentException("Nothing in this book could be read.")
            val title = runCatching { doc.getMetaData("info:Title") }.getOrNull()?.trim().orEmpty()
            val writer = DocumentWriter(out, "pdf", "compress")
            try {
                for (i in 0 until n) {
                    progress(i + 1, n)
                    val p = doc.loadPage(i)
                    try {
                        val dev = writer.beginPage(p.bounds)
                        p.run(dev, Matrix.Identity(), null)
                        writer.endPage()
                    } finally {
                        p.destroy()
                    }
                }
            } finally {
                writer.close()
                writer.destroy()
            }
            addLinksAndContents(doc, out, title)
            return Result(n, title)
        } finally {
            doc.destroy()
        }
    }

    internal class Entry(val title: String, val page: Int, val kids: List<Entry>)

    /** The book's links and its contents list, carried over to the written PDF. */
    private fun addLinksAndContents(book: Document, out: String, title: String) {
        val pdf = Document.openDocument(out) as PDFDocument
        try {
            val n = minOf(pdf.countPages(), book.countPages())
            for (i in 0 until n) {
                val src = book.loadPage(i)
                val links = runCatching { src.links }.getOrNull()
                src.destroy()
                if (links.isNullOrEmpty()) continue
                val dst = pdf.loadPage(i) as PDFPage
                try {
                    for (l in links) runCatching {
                        val uri = l.uri ?: return@runCatching
                        if (l.isExternal) dst.createLink(l.bounds, uri)
                        else {
                            val dest = book.resolveLinkDestination(uri)
                            val target = book.pageNumberFromLocation(dest)
                            if (target in 0 until pdf.countPages()) dst.createLinkXYZ(l.bounds, target, dest.x, dest.y, 0f)
                        }
                    }
                } finally {
                    dst.destroy()
                }
            }
            val outline = runCatching { book.loadOutline() }.getOrNull()
            fun entries(items: Array<Outline>?): List<Entry> = items.orEmpty().mapNotNull { o ->
                val page = runCatching { book.pageNumberFromLocation(book.resolveLink(o.uri)) }.getOrDefault(-1)
                val kids = entries(o.down)
                if (o.title.isNullOrBlank() || (page < 0 && kids.isEmpty())) null
                else Entry(o.title.trim(), if (page >= 0) page else kids.first().page, kids)
            }
            writeOutline(pdf, entries(outline))
            if (title.isNotEmpty()) runCatching { pdf.setMetaData("info:Title", title) }
            pdf.save(out, "garbage=compact,compress=yes")
        } finally {
            pdf.destroy()
        }
    }

    internal fun writeOutline(d: PDFDocument, entries: List<Entry>) {
        if (entries.isEmpty()) return
        val root = d.trailer.get("Root")
        val top = d.createObject()
        top.writeObject(d.newDictionary().apply { put("Type", d.newName("Outlines")) })
        root.put("Outlines", top)
        fun level(parent: PDFObject, items: List<Entry>): Int {
            var prev: PDFObject? = null
            var count = 0
            for (e in items) {
                if (e.page !in 0 until d.countPages()) continue
                val item = d.createObject()
                val dest = d.newArray().apply {
                    append(d.findPage(e.page)); append(d.newName("XYZ")); append(d.newNull()); append(d.newNull()); append(d.newNull())
                }
                item.writeObject(d.newDictionary().apply { put("Title", e.title); put("Parent", parent); put("Dest", dest) })
                val p = prev
                if (p == null) parent.put("First", item) else { p.put("Next", item); item.put("Prev", p) }
                parent.put("Last", item)
                prev = item
                count++
                // Chapters shown closed, so a long contents list starts short.
                val kids = level(item, e.kids)
                if (kids > 0) item.put("Count", -kids)
            }
            return count
        }
        val n = level(top, entries)
        top.put("Count", n)
    }

    // ------------------------------------------------------------------------------------------ fonts

    /**
     * Lets MuPDF borrow the phone's own fonts for writing it has none for (the Android build carries
     * Latin, Greek and Cyrillic only): Arabic, Hebrew, Indian scripts, Chinese, Japanese, Korean and
     * the rest, from /system/fonts. Used when a book or a PDF names no font of its own for them.
     */
    fun installSystemFonts(dir: File = File("/system/fonts")) {
        val files = dir.listFiles()?.filter { it.name.endsWith(".ttf") || it.name.endsWith(".otf") || it.name.endsWith(".ttc") }.orEmpty()
        if (files.isEmpty()) return
        val cache = HashMap<String, Font?>()
        fun pick(script: String, bold: Boolean): File? {
            val key = script.lowercase().replace(" ", "")
            val cjk = mapOf("sc" to "cjk", "tc" to "cjk", "jp" to "cjk", "kr" to "cjk", "han" to "cjk", "hiragana" to "cjk", "katakana" to "cjk", "hangul" to "cjk")
            val want = cjk[key] ?: key
            val named = files.filter { f -> f.name.lowercase().replace("-", "").let { it.startsWith("noto") && it.contains(want) } }
            if (named.isEmpty()) return null
            fun score(f: File): Int {
                val n = f.name.lowercase()
                var s = 0
                if (bold == n.contains("bold")) s += 4
                if (n.contains("regular") || (bold && n.contains("bold"))) s += 2
                if (n.contains("ui")) s -= 1 // the UI cuts are squeezed for buttons
                if (n.contains("naskh")) s += 1 // Arabic book type over the plain sans
                if (n.contains("serif") && !n.contains("naskh")) s -= 1
                if (n.contains("italic") || n.contains("mono") || n.contains("display")) s -= 3
                return s
            }
            return named.maxByOrNull(::score)
        }
        Font.installFontLoader { _, script, bold, _ ->
            if (script == null || script == "undefined" || script.equals("Latin", true) || script.equals("Common", true)) return@installFontLoader null
            val k = "$script/$bold"
            synchronized(cache) {
                if (!cache.containsKey(k)) cache[k] = pick(script, bold)?.let { f -> runCatching { Font(f.absolutePath) }.getOrNull() }
                cache[k]
            }
        }
    }
}
