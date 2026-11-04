package com.fadysaeed.pdfx.engine

import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Words written on every page as part of the page itself: a watermark ("CONFIDENTIAL" across the
 * page, faint, so the text under it still reads) and page numbers (where and how they are asked
 * for). Each page's own drawing is wrapped in q … Q first, so nothing it leaves set can move them,
 * and they sit the right way up on turned and cropped pages.
 */
object Stamp {
    enum class Place(val label: String, val top: Boolean, val align: Int) {
        BOTTOM_CENTER("Bottom, centre", false, 0), BOTTOM_RIGHT("Bottom, right", false, 1), BOTTOM_LEFT("Bottom, left", false, -1),
        TOP_CENTER("Top, centre", true, 0), TOP_RIGHT("Top, right", true, 1), TOP_LEFT("Top, left", true, -1),
    }

    enum class Style(val label: String) {
        PLAIN("3"), PAGE("Page 3"), OF("Page 3 of 12"), SLASH("3 / 12"), DASHES("– 3 –");

        fun text(n: Int, last: Int) = when (this) {
            PLAIN -> "$n"; PAGE -> "Page $n"; OF -> "Page $n of $last"; SLASH -> "$n / $last"; DASHES -> "– $n –"
        }
    }

    /** Page numbers: where, how written, from which page (0 for the first) and starting at which number. */
    data class Numbers(val place: Place = Place.BOTTOM_CENTER, val style: Style = Style.PLAIN, val from: Int = 0, val startAt: Int = 1, val size: Float = 10f, val color: Int = 0x404040)

    /** A watermark: its words, colour, how see-through (0 to 1), across the page corner to corner or straight, and on which pages. */
    data class Mark(val text: String, val color: Int = 0x808080, val opacity: Float = 0.25f, val diagonal: Boolean = true, val firstOnly: Boolean = false)

    private const val FONT = "PDFxStampF"
    private const val GS = "PDFxStampG"

    /** Whether [text] can be written: Latin letters, numbers and the usual marks. */
    fun writable(text: String): Boolean = text.isNotBlank() && encode(text) != null

    fun watermark(path: String, out: String, m: Mark, password: String? = null): Int {
        val d = Arranger.open(path, password)
        try {
            watermark(d, m)
            d.save(out, "garbage=compact")
            return d.countPages()
        } finally {
            d.destroy()
        }
    }

    fun pageNumbers(path: String, out: String, n: Numbers, password: String? = null): Int {
        val d = Arranger.open(path, password)
        try {
            pageNumbers(d, n)
            d.save(out, "garbage=compact")
            return d.countPages()
        } finally {
            d.destroy()
        }
    }

    fun watermark(d: PDFDocument, m: Mark) {
        val bytes = encode(m.text.trim()) ?: throw IllegalArgumentException("A watermark can have Latin letters, numbers and the usual marks.")
        val font = Font("Helvetica-Bold")
        try {
            val em = width(font, bytes)
            val pages = if (m.firstOnly) 1 else d.countPages()
            for (i in 0 until pages) {
                val p = d.loadPage(i) as PDFPage
                try {
                    val b = p.bounds
                    val w = b.x1 - b.x0
                    val h = b.y1 - b.y0
                    // Corner to corner, bottom left to top right; or straight across the middle.
                    val angle = if (m.diagonal) Math.atan2(h.toDouble(), w.toDouble()) else 0.0
                    val room = if (m.diagonal) Math.hypot(w.toDouble(), h.toDouble()).toFloat() * 0.72f else w * 0.82f
                    val size = (room / em).coerceIn(14f, 160f)
                    val cos = Math.cos(angle).toFloat()
                    val sin = Math.sin(angle).toFloat()
                    val half = em * size / 2f
                    val cx = b.x0 + w / 2f
                    val cy = b.y0 + h / 2f
                    // The words' middle on the page's middle (their height about 0.7 of the type size).
                    val bx = cx - half * cos + 0.35f * size * sin
                    val by = cy + half * sin + 0.35f * size * cos
                    write(d, p, bytes, font, size, cos, sin, bx, by, m.color, m.opacity)
                } finally {
                    p.destroy()
                }
            }
        } finally {
            font.destroy()
        }
    }

    fun pageNumbers(d: PDFDocument, n: Numbers) {
        val font = Font("Helvetica")
        try {
            val count = d.countPages()
            val last = n.startAt + (count - 1 - n.from)
            for (i in n.from.coerceAtLeast(0) until count) {
                val text = n.style.text(n.startAt + (i - n.from), last)
                val bytes = encode(text) ?: continue
                val p = d.loadPage(i) as PDFPage
                try {
                    val b = p.bounds
                    val w = width(font, bytes) * n.size
                    val edge = 28f
                    val x = when (n.place.align) { 0 -> b.x0 + (b.x1 - b.x0 - w) / 2f; 1 -> b.x1 - edge - w; else -> b.x0 + edge }
                    val y = if (n.place.top) b.y0 + edge + n.size * 0.7f else b.y1 - edge
                    write(d, p, bytes, font, n.size, 1f, 0f, x, y, n.color, 1f)
                } finally {
                    p.destroy()
                }
            }
        } finally {
            font.destroy()
        }
    }

    /** Words on page [p], starting at ([x], [y]) on the page as shown, going along ([cos], [sin]) turned up from the right. */
    private fun write(d: PDFDocument, p: PDFPage, bytes: ByteArray, font: Font, size: Float, cos: Float, sin: Float, x: Float, y: Float, color: Int, opacity: Float) {
        val inv = Matrix.Inverted(p.transform)
        // Text space → the page as shown (y down) → PDF space.
        val va = size * cos; val vb = -size * sin; val vc = -size * sin; val vd = -size * cos
        val a = va * inv.a + vb * inv.c
        val bb = va * inv.b + vb * inv.d
        val c = vc * inv.a + vd * inv.c
        val dd = vc * inv.b + vd * inv.d
        val e = x * inv.a + y * inv.c + inv.e
        val f = x * inv.b + y * inv.d + inv.f
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val bl = (color and 0xFF) / 255f
        val sb = StringBuilder("q /").append(GS).append(" gs ").append(n(r)).append(' ').append(n(g)).append(' ').append(n(bl)).append(" rg BT /").append(FONT)
            .append(" 1 Tf ").append(n(a)).append(' ').append(n(bb)).append(' ').append(n(c)).append(' ').append(n(dd)).append(' ').append(n(e)).append(' ').append(n(f)).append(" Tm <")
        for (x0 in bytes) sb.append(String.format(Locale.US, "%02X", x0.toInt() and 0xFF))
        sb.append("> Tj ET Q\n")

        val pageObj = p.getObject()
        val res = TextLayer.ownResources(d, pageObj)
        var fonts = res.get("Font")
        if (fonts.isNull) { fonts = d.newDictionary(); res.put("Font", fonts) }
        fonts.put(FONT, d.addSimpleFont(font, 0))
        var states = res.get("ExtGState")
        if (states.isNull) { states = d.newDictionary(); res.put("ExtGState", states) }
        val gs = d.newDictionary()
        gs.put("ca", opacity.coerceIn(0.05f, 1f))
        gs.put("CA", opacity.coerceIn(0.05f, 1f))
        states.put(GS, d.addObject(gs))

        val old = pageObj.get("Contents")
        val contents = d.newArray()
        contents.append(d.addStream("q\n"))
        if (old.isArray) for (i in 0 until old.size()) contents.append(old.get(i)) else if (!old.isNull) contents.append(old)
        contents.append(d.addStream("\nQ\n"))
        contents.append(d.addStream(sb.toString()))
        pageObj.put("Contents", contents)
    }

    private fun width(font: Font, bytes: ByteArray): Float {
        var em = 0f
        for (b in bytes) em += font.advanceGlyph(font.encodeCharacter(String(byteArrayOf(b), charset("windows-1252"))[0].code))
        return em
    }

    private fun encode(text: String): ByteArray? = runCatching {
        val enc = charset("windows-1252").newEncoder().onUnmappableCharacter(CodingErrorAction.REPORT).onMalformedInput(CodingErrorAction.REPORT)
        val buf: ByteBuffer = enc.encode(CharBuffer.wrap(text.replace('‑', '-')))
        ByteArray(buf.remaining()).also { buf.get(it) }
    }.getOrNull()

    private fun n(v: Float) = String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')
}
