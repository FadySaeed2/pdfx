package com.fadysaeed.pdfx.engine

import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import java.text.Normalizer
import java.util.Locale

/** A word read off a picture of a page, with its box in page points as the page is shown (origin top left). */
data class ReadWord(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Lays read words over a page as invisible text, each stretched over the box where it sits in the
 * picture, so the page can be searched, copied from and highlighted while it looks exactly as before.
 * The text is drawn in render mode 3 (neither filled nor stroked) in Helvetica, which every reader
 * has, so nothing is embedded. The page's own drawing is wrapped in q … Q so whatever state it
 * leaves cannot move the words.
 */
object TextLayer {
    private const val FONT_KEY = "PDFxRead"
    // Helvetica's ascent and descent in ems as MuPDF measures them (its font's own, not the
    // printed 0.718/0.207): selecting or highlighting a word then covers exactly its box.
    private const val ASCENT = 1.075f
    private const val DESCENT = 0.299f

    fun add(doc: PDFDocument, page: PDFPage, words: List<ReadWord>): Int {
        val usable = words.filter { it.text.isNotBlank() && it.right > it.left && it.bottom > it.top }
        if (usable.isEmpty()) return 0
        val font = Font("Helvetica")
        try {
            val inv = Matrix.Inverted(page.transform)
            val sb = StringBuilder("BT 3 Tr\n")
            for (w in usable) {
                val bytes = winAnsi(w.text)
                if (bytes.isEmpty()) continue
                var em = 0f
                for (b in bytes) em += font.advanceGlyph(font.encodeCharacter(unicodeOf(b)))
                if (em <= 0f) continue
                val s = (w.bottom - w.top) / (ASCENT + DESCENT)
                val sx = (w.right - w.left) / em
                val base = w.bottom - DESCENT * s
                // Text space → page as shown (x along the word, y up from the baseline), then → PDF space.
                val a = sx * inv.a
                val b = sx * inv.b
                val c = -s * inv.c
                val d = -s * inv.d
                val e = w.left * inv.a + base * inv.c + inv.e
                val f = w.left * inv.b + base * inv.d + inv.f
                sb.append("/").append(FONT_KEY).append(" 1 Tf ")
                    .append(n(a)).append(' ').append(n(b)).append(' ').append(n(c)).append(' ')
                    .append(n(d)).append(' ').append(n(e)).append(' ').append(n(f)).append(" Tm <")
                for (x in bytes) sb.append(String.format(Locale.US, "%02X", x.toInt() and 0xFF))
                sb.append("> Tj\n")
            }
            sb.append("ET\n")

            val pageObj = page.getObject()
            val res = ownResources(doc, pageObj)
            var fonts = res.get("Font")
            if (fonts.isNull) { fonts = doc.newDictionary(); res.put("Font", fonts) }
            fonts.put(FONT_KEY, doc.addSimpleFont(font, 0 /* Latin: WinAnsiEncoding */))

            val old = pageObj.get("Contents")
            val contents = doc.newArray()
            contents.append(doc.addStream("q\n"))
            if (old.isArray) for (i in 0 until old.size()) contents.append(old.get(i))
            else if (!old.isNull) contents.append(old)
            contents.append(doc.addStream("\nQ\n"))
            contents.append(doc.addStream(sb.toString()))
            pageObj.put("Contents", contents)
            return usable.size
        } finally {
            font.destroy()
        }
    }

    /**
     * Whether a page's words should be read off its picture: it has no words MuPDF can find, or only
     * a few (a scanner app's stamp, a page number) over a picture that covers most of it. Never a
     * page PDFx has read before, so reading a file twice adds nothing twice.
     */
    fun needsReading(page: PDFPage): Boolean {
        val fonts = page.getObject().getInheritable("Resources").get("Font")
        if (!fonts.isNull && !fonts.get(FONT_KEY).isNull) return false
        val b = page.bounds
        val pageArea = ((b.x1 - b.x0) * (b.y1 - b.y0)).coerceAtLeast(1f)
        var letters = 0
        var picture = 0f
        val st = page.toStructuredText("preserve-images")
        try {
            st.walk(object : com.artifex.mupdf.fitz.StructuredTextWalker {
                override fun onImageBlock(bbox: com.artifex.mupdf.fitz.Rect, transform: Matrix, image: com.artifex.mupdf.fitz.Image) {
                    picture = maxOf(picture, (bbox.x1 - bbox.x0) * (bbox.y1 - bbox.y0))
                }
                override fun beginTextBlock(bbox: com.artifex.mupdf.fitz.Rect, bidi: Int) {}
                override fun endTextBlock() {}
                override fun beginLine(bbox: com.artifex.mupdf.fitz.Rect, wmode: Int, dir: com.artifex.mupdf.fitz.Point) {}
                override fun endLine() {}
                override fun onChar(c: Int, origin: com.artifex.mupdf.fitz.Point, font: Font, size: Float, quad: com.artifex.mupdf.fitz.Quad, argb: Int, flags: Int, bidi: Int) {
                    if (Character.isLetterOrDigit(c)) letters++
                }
                override fun beginStruct(standard: String?, raw: String?, index: Int) {}
                override fun endStruct() {}
                override fun onVector(bbox: com.artifex.mupdf.fitz.Rect, info: com.artifex.mupdf.fitz.StructuredTextWalker.VectorInfo, argb: Int) {}
            })
        } finally {
            st.destroy()
        }
        if (letters == 0) return true
        return letters < SCANNER_STAMP && picture >= pageArea * 0.5f
    }

    /** Fewer letters than this over a full-page picture is a stamp or a page number, not the page's words. */
    private const val SCANNER_STAMP = 150

    /** True when MuPDF already finds words on the page: no need to read it again. */
    fun hasText(page: com.artifex.mupdf.fitz.Page): Boolean {
        val st = page.toStructuredText()
        try {
            for (b in st.blocks ?: return false) for (l in b.lines ?: continue) for (c in l.chars ?: continue)
                if (Character.isLetterOrDigit(c.c)) return true
            return false
        } finally {
            st.destroy()
        }
    }

    /** The page's own resources, made its own if it only inherited them, so adding a font touches no other page. */
    internal fun ownResources(doc: PDFDocument, pageObj: PDFObject): PDFObject {
        val own = pageObj.get("Resources")
        if (!own.isNull) return own
        val inherited = pageObj.getInheritable("Resources")
        val res = doc.newDictionary()
        if (!inherited.isNull) {
            val keys = listOf("Font", "XObject", "ExtGState", "ColorSpace", "Pattern", "Shading", "ProcSet", "Properties")
            for (k in keys) {
                val v = inherited.get(k)
                if (v.isNull) continue
                // Copy a dictionary one level so the new font lands only here.
                if (v.isDictionary && k == "Font") {
                    val copy = doc.newDictionary()
                    val r = v.resolve()
                    for (key in r) copy.put(key, r.get(key))
                    res.put(k, copy)
                } else {
                    res.put(k, v)
                }
            }
        }
        pageObj.put("Resources", res)
        return res
    }

    private fun n(v: Float) = String.format(Locale.US, "%.4f", v)

    /** The word in Windows-1252, the encoding the font is given; letters outside it are spelled plainly (ﬁ → fi, é stays é). */
    internal fun winAnsi(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (ch in text) {
            val code = toCp1252(ch)
            if (code != null) { out.write(code); continue }
            var wrote = false
            for (p in Normalizer.normalize(ch.toString(), Normalizer.Form.NFKD)) {
                if (p.code in 0x0300..0x036F) continue // accents left over from taking a letter apart
                val c2 = toCp1252(p) ?: continue
                out.write(c2); wrote = true
            }
            if (!wrote) out.write('?'.code)
        }
        return out.toByteArray()
    }

    private val cp1252High = mapOf(
        '€' to 0x80, '‚' to 0x82, 'ƒ' to 0x83, '„' to 0x84, '…' to 0x85, '†' to 0x86, '‡' to 0x87, 'ˆ' to 0x88,
        '‰' to 0x89, 'Š' to 0x8A, '‹' to 0x8B, 'Œ' to 0x8C, 'Ž' to 0x8E, '‘' to 0x91, '’' to 0x92, '“' to 0x93,
        '”' to 0x94, '•' to 0x95, '–' to 0x96, '—' to 0x97, '˜' to 0x98, '™' to 0x99, 'š' to 0x9A, '›' to 0x9B,
        'œ' to 0x9C, 'ž' to 0x9E, 'Ÿ' to 0x9F,
    )
    private val cp1252Back = cp1252High.entries.associate { (k, v) -> v to k }

    private fun toCp1252(ch: Char): Int? = when {
        ch.code in 0x20..0x7E -> ch.code
        ch.code in 0xA0..0xFF -> ch.code
        else -> cp1252High[ch]
    }

    private fun unicodeOf(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return cp1252Back[v]?.code ?: v
    }
}
