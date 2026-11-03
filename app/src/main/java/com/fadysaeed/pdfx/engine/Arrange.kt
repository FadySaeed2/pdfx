package com.fadysaeed.pdfx.engine

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFGraftMap
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.Rect
import java.util.concurrent.atomic.AtomicLong

/** Where a page in a new arrangement comes from. */
enum class PageSource { ORIGINAL, OTHER, BLANK, PICTURE }

private val ids = AtomicLong(1)

/**
 * One page of a file being put in order: a page of the file itself ([page]), of another PDF
 * ([path], [page]), a blank page ([width] by [height] points) or a picture ([path], a JPEG or PNG
 * already turned upright). [turn] is how far to turn it, clockwise, in degrees. [id] tells pages
 * apart when the same one appears twice.
 */
data class PageItem(
    val source: PageSource,
    val page: Int = 0,
    val path: String? = null,
    val turn: Int = 0,
    val width: Float = 595f,
    val height: Float = 842f,
    val id: Long = ids.getAndIncrement(),
) {
    fun turned(by: Int) = copy(turn = ((turn + by) % 360 + 360) % 360)
    fun twin() = copy(id = ids.getAndIncrement())
}

/** The pages of [count] in their own order: where an arrangement starts. */
fun originalPages(count: Int): List<PageItem> = List(count) { PageItem(PageSource.ORIGINAL, it) }

/**
 * Writes a new PDF from a file and an arrangement of pages. The file's own pages keep everything
 * they carry (marks, links, form fields with their values, the contents list where its pages are
 * still there); a page used twice, and pages from other files, are copied in whole. The original
 * file is only read.
 */
object Arranger {

    /** What the root of a file holds besides its pages, kept across the rearranging. */
    private val ROOT_KEEP = listOf(
        "AcroForm", "Metadata", "Lang", "MarkInfo", "ViewerPreferences", "PageMode", "PageLayout",
        "Names", "OutputIntents", "Extensions",
    )

    /**
     * Arranges the file at [base] into [out]. [password] opens a locked file (it stays locked
     * the same way). Pages of other files that are locked cannot be used; those are left out and
     * their files named in the result.
     */
    fun write(
        base: String, items: List<PageItem>, out: String, password: String? = null, otherPasswords: Map<String, String> = emptyMap(),
        /** Contents entries to add at the end of the file's own list: a title and the item it opens at. */
        contents: List<Pair<String, Int>> = emptyList(),
    ): Result {
        require(items.isNotEmpty()) { "A file needs at least one page." }
        val d = open(base, password)
        val opened = ArrayList<Document>()
        val maps = ArrayList<PDFGraftMap>()
        val skipped = LinkedHashSet<String>()
        try {
            val n = d.countPages()
            val root = d.trailer.get("Root")
            val kept = ROOT_KEEP.mapNotNull { k -> root.get(k).takeIf { !it.isNull }?.let { k to it } }
            // A second copy of the file itself, for pages used more than once: each use is a page of its own.
            var twin: Pair<PDFDocument, PDFGraftMap>? = null
            val others = HashMap<String, Pair<PDFDocument, PDFGraftMap>?>()
            val used = BooleanArray(n)
            val order = ArrayList<Int>()
            val turns = ArrayList<Int>()
            // Where each item ends up; items that could not be used are left out.
            val where = IntArray(items.size) { -1 }
            var next = n
            var copied = false
            for ((idx, it) in items.withIndex()) {
                val at: Int = when (it.source) {
                    PageSource.ORIGINAL -> {
                        require(it.page in 0 until n) { "Page ${it.page + 1} is not in the file." }
                        if (!used[it.page]) { used[it.page] = true; it.page } else {
                            val t = twin ?: open(base, password).let { s -> opened += s; val m = d.newPDFGraftMap(); maps += m; (s to m).also { p -> twin = p } }
                            t.second.graftPage(-1, t.first, it.page)
                            copyAnnots(d, t.second, t.first.findPage(it.page), d.findPage(d.countPages() - 1))
                            copied = true
                            next++
                        }
                    }
                    PageSource.OTHER -> {
                        val path = it.path ?: continue
                        val o = others.getOrPut(path) {
                            runCatching { open(path, otherPasswords[path]) }.getOrNull()?.let { s -> opened += s; val m = d.newPDFGraftMap(); maps += m; s to m }
                        }
                        if (o == null || it.page !in 0 until o.first.countPages()) { skipped += path; continue }
                        o.second.graftPage(-1, o.first, it.page)
                        copyAnnots(d, o.second, o.first.findPage(it.page), d.findPage(d.countPages() - 1))
                        copied = true
                        next++
                    }
                    PageSource.BLANK -> {
                        val p = d.addPage(Rect(0f, 0f, it.width, it.height), 0, d.newDictionary(), "")
                        d.insertPage(-1, p)
                        next++
                    }
                    PageSource.PICTURE -> {
                        val path = it.path ?: continue
                        addPicture(d, path)
                        next++
                    }
                }
                order += at
                turns += it.turn
                where[idx] = order.size - 1
            }
            if (order.isEmpty()) throw IllegalArgumentException("None of the pages could be used.")
            d.rearrangePages(order.toIntArray())
            for ((i, t) in turns.withIndex()) {
                if (t % 360 == 0) continue
                val p = d.findPage(i)
                val r = p.getInheritable("Rotate").let { if (it.isNull) 0 else it.asInteger() }
                p.put("Rotate", ((r + t) % 360 + 360) % 360)
            }
            restoreRoot(d, kept)
            if (contents.isNotEmpty()) addContents(d, contents.mapNotNull { (t, i) -> where.getOrNull(i)?.takeIf { it >= 0 }?.let { t to it } })
            d.save(out, if (copied) "garbage=deduplicate" else "garbage=compact")
            return Result(order.size, skipped.toList())
        } finally {
            maps.forEach { runCatching { it.destroy() } }
            opened.forEach { runCatching { it.destroy() } }
            runCatching { d.destroy() }
        }
    }

    /** A new file of [pictures] (JPEG or PNG, already upright), one to a page, fitted to A4. Returns the page count. */
    fun fromPictures(pictures: List<String>, out: String): Int {
        require(pictures.isNotEmpty()) { "No pictures to make a file from." }
        val d = PDFDocument()
        try {
            for (p in pictures) addPicture(d, p)
            d.save(out, "garbage=compact")
            return d.countPages()
        } finally {
            runCatching { d.destroy() }
        }
    }

    class Result(val pages: Int, val skippedFiles: List<String>)

    internal fun open(path: String, password: String?): PDFDocument {
        val doc = Document.openDocument(path)
        val pdf = doc.asPDF() ?: run { doc.destroy(); throw IllegalArgumentException("Not a PDF file.") }
        if (pdf.needsPassword() && (password == null || !pdf.authenticatePassword(password))) {
            pdf.destroy()
            throw IllegalArgumentException("The file is locked with a password.")
        }
        return pdf
    }

    /** A page the size of the picture (fitted to A4, turned to suit it) holding just the picture. */
    private fun addPicture(d: PDFDocument, path: String) {
        val img = Image(path)
        try {
            val w = img.width.coerceAtLeast(1).toFloat()
            val h = img.height.coerceAtLeast(1).toFloat()
            val (bw, bh) = if (w > h) 842f to 595f else 595f to 842f
            val k = minOf(bw / w, bh / h)
            val pw = w * k
            val ph = h * k
            val ref = d.addImage(img)
            val res = d.newDictionary()
            val xo = d.newDictionary()
            xo.put("Im0", ref)
            res.put("XObject", xo)
            val content = "q ${fmt(pw)} 0 0 ${fmt(ph)} 0 0 cm /Im0 Do Q"
            val page = d.addPage(Rect(0f, 0f, pw, ph), 0, res, content)
            d.insertPage(-1, page)
        } finally {
            runCatching { img.destroy() }
        }
    }

    /** Adds entries to the end of the file's contents list, each opening at the top of its page. */
    private fun addContents(d: PDFDocument, entries: List<Pair<String, Int>>) {
        if (entries.isEmpty()) return
        val root = d.trailer.get("Root")
        var outlines = root.get("Outlines")
        if (!outlines.isDictionary) {
            val ref = d.createObject()
            ref.writeObject(d.newDictionary().apply { put("Type", d.newName("Outlines")) })
            root.put("Outlines", ref)
            outlines = root.get("Outlines")
        }
        var prev: PDFObject? = outlines.get("Last").takeIf { it.isDictionary }
        var added = 0
        for ((title, page) in entries) {
            if (page !in 0 until d.countPages()) continue
            val item = d.createObject()
            val dest = d.newArray().apply {
                push(d.findPage(page)); push(d.newName("XYZ")); push(d.newNull()); push(d.newNull()); push(d.newNull())
            }
            item.writeObject(d.newDictionary().apply {
                put("Title", title)
                put("Parent", outlines)
                put("Dest", dest)
            })
            val p = prev
            if (p == null) outlines.put("First", item) else { p.put("Next", item); item.put("Prev", p) }
            outlines.put("Last", item)
            prev = item
            added++
        }
        val count = outlines.get("Count").let { if (it.isNull) 0 else it.asInteger() }
        outlines.put("Count", if (count < 0) count - added else count + added)
    }

    private val SKIP_KEYS = setOf("P", "Parent", "Popup", "IRT", "Dest", "A", "Kids")
    private val FIELD_KEYS = listOf("FT", "T", "TU", "TM", "Ff", "V", "DV", "DA", "Q", "Opt", "MaxLen", "DS", "RV")

    /**
     * A copied page takes its marks with it (the engine's copy leaves them behind): highlights,
     * notes, ink, stamps, and form fields as they look, each field carrying its own name and value.
     * Links are left out, since they point into the other file, and so is a signature's proof,
     * which belongs to the file it was made in (its look stays).
     */
    private fun copyAnnots(d: PDFDocument, map: PDFGraftMap, src: PDFObject, dst: PDFObject) {
        val annots = src.get("Annots")
        if (!annots.isArray) return
        val out = d.newArray()
        for (a0 in annots) {
            val a = a0.resolve()
            if (!a.isDictionary) continue
            val sub = a.get("Subtype").let { if (it.isName) it.asName() else "" }
            if (sub == "Link" || sub == "Popup") continue
            val c = d.newDictionary()
            for (k in a) {
                val name = k.asName()
                if (name in SKIP_KEYS) continue
                c.put(name, map.graftObject(a.get(name)))
            }
            if (sub == "Widget") {
                // A field's name and value can sit on its parent; the copy carries them itself.
                var parent = a.get("Parent")
                var depth = 0
                while (parent.isDictionary && depth < 16) {
                    for (key in FIELD_KEYS) if (c.get(key).isNull && !parent.get(key).isNull) c.put(key, map.graftObject(parent.get(key)))
                    parent = parent.get("Parent")
                    depth++
                }
                if (c.get("FT").let { it.isName && it.asName() == "Sig" }) c.delete("V")
            }
            c.put("P", dst)
            // An object of its own, as marks must be. (addObject in these bindings hands back the
            // dictionary itself, not its reference, and lets go of it: so not that.)
            val ref = d.createObject()
            ref.writeObject(c)
            out.append(ref)
        }
        if (out.size() > 0) dst.put("Annots", out)
    }

    private fun fmt(v: Float) = String.format(java.util.Locale.US, "%.2f", v)

    /**
     * Putting the root back the way it was: the rearranging keeps only the pages, the contents
     * list and named places. Form fields are kept where their page still is.
     */
    private fun restoreRoot(d: PDFDocument, kept: List<Pair<String, PDFObject>>) {
        val root = d.trailer.get("Root")
        for ((k, v) in kept) {
            when (k) {
                "Names" -> {
                    // The rearranging rebuilt the named places (Dests); the rest (attachments, scripts) come back.
                    val now = root.get("Names")
                    if (now.isNull) { root.put("Names", v); continue }
                    for (key in v) {
                        val name = key.asName()
                        if (name != "Dests" && now.get(name).isNull) now.put(name, v.get(name))
                    }
                }
                "AcroForm" -> keepForm(d, v)?.let { root.put("AcroForm", it) }
                else -> root.put(k, v)
            }
        }
    }

    /** The form, with only the fields that still have a box on one of the pages. */
    private fun keepForm(d: PDFDocument, form: PDFObject): PDFObject? {
        val fields = form.get("Fields")
        if (!fields.isArray) return form
        val boxes = HashSet<Int>()
        for (i in 0 until d.countPages()) {
            val annots = d.findPage(i).get("Annots")
            if (annots.isArray) for (a in annots) if (a.isIndirect) boxes += a.asIndirect()
        }
        fun alive(f: PDFObject, depth: Int): Boolean {
            if (depth > 32) return false
            if (f.isIndirect && f.asIndirect() in boxes) return true
            val kids = f.get("Kids")
            return kids.isArray && kids.any { alive(it, depth + 1) }
        }
        val keep = d.newArray()
        for (f in fields) if (alive(f, 0)) keep.append(f)
        if (keep.size() == 0) return null
        form.put("Fields", keep)
        return form
    }
}
