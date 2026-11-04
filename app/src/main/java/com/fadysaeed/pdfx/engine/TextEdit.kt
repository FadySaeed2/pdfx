package com.fadysaeed.pdfx.engine

import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.StructuredTextWalker
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * A run of words on a page that look alike (one font, size and colour, with no wide gap in it):
 * what a tap on a line picks to edit. Places are on the page as shown (origin top left).
 */
data class TextRun(
    val page: Int,
    val text: String,
    val box: Box,
    /** Where its baseline starts, and the way the line runs (1, 0 for left to right). */
    val x: Float, val y: Float, val dirX: Float, val dirY: Float,
    val size: Float,
    val color: Int,
    val font: String,
    val bold: Boolean, val italic: Boolean, val serif: Boolean, val mono: Boolean,
    /** How long it runs along its line. */
    val length: Float,
    /** It lies over a picture (a scanned page): what is drawn there must be covered too. */
    val onPicture: Boolean,
) {
    /** "Times, 11 pt, bold" — how the new words will look. */
    val look: String get() = listOfNotNull(
        font.substringAfter('+').substringBefore('-').substringBefore(',').ifBlank { if (serif) "Serif" else "Sans" },
        "${Math.round(size * 10) / 10f} pt".replace(".0 pt", " pt"),
        "bold".takeIf { bold }, "italic".takeIf { italic },
    ).joinToString(", ")
}

/**
 * Changes the words on a page in simple cases (a line, a name, a number, a date): the old words
 * are taken out of the page itself, not covered, and the new ones written where they stood, in the
 * same font when it has every letter needed (so a changed date looks as it did), else in the
 * nearest standard one of the same kind, size and colour. On a scanned page the picture of the old
 * words is painted over in the paper's own colour first. Text that flows into the next line does
 * not reflow: this is for short changes, as a pen and correction fluid would make.
 */
object TextEdit {
    /** A space's width in ems, for fonts without one. */
    private const val SPACE = 0.25f

    private class Ch(val c: Int, val origin: Point, val quad: Quad, val font: Font, val size: Float, val argb: Int)

    /** The run of words at ([x], [y]) on [page], or the nearest within a few points; null when there are none. */
    fun runAt(page: PDFPage, index: Int, x: Float, y: Float): TextRun? = runsOf(page, index).let { runs ->
        runs.firstOrNull { it.first.box.contains(x, y) }?.first
            ?: runs.map { it.first }.minByOrNull { distance(it.box, x, y) }?.takeIf { distance(it.box, x, y) < 8f }
    }

    private fun distance(b: Box, x: Float, y: Float): Float {
        val dx = maxOf(b.x0 - x, 0f, x - b.x1)
        val dy = maxOf(b.y0 - y, 0f, y - b.y1)
        return kotlin.math.hypot(dx, dy)
    }

    /** Every run on the page, with the font object of its first letter (good only while the page is). */
    private fun runsOf(page: PDFPage, index: Int): List<Pair<TextRun, Font>> {
        val pictures = ArrayList<Rect>()
        val lines = ArrayList<Pair<Point, List<Ch>>>()
        var cur = ArrayList<Ch>()
        var dir = Point(1f, 0f)
        val st = page.toStructuredText("preserve-images")
        try {
            st.walk(object : StructuredTextWalker {
                override fun onImageBlock(bbox: Rect, transform: Matrix, image: com.artifex.mupdf.fitz.Image) { pictures += bbox }
                override fun beginTextBlock(bbox: Rect, bidi: Int) {}
                override fun endTextBlock() {}
                override fun beginLine(bbox: Rect, wmode: Int, d: Point) { cur = ArrayList(); dir = d }
                override fun endLine() { if (cur.isNotEmpty()) lines += dir to cur }
                override fun onChar(c: Int, origin: Point, font: Font, size: Float, quad: Quad, argb: Int, flags: Int, bidi: Int) {
                    cur += Ch(c, origin, quad, font, size, argb)
                }
                override fun beginStruct(standard: String?, raw: String?, index: Int) {}
                override fun endStruct() {}
                override fun onVector(bbox: Rect, info: StructuredTextWalker.VectorInfo, argb: Int) {}
            })
        } finally {
            st.destroy()
        }
        val out = ArrayList<Pair<TextRun, Font>>()
        for ((d, chars) in lines) {
            // Split where the look changes or a wide gap opens (table cells on one line).
            var start = 0
            for (i in 1..chars.size) {
                val cut = i == chars.size || run {
                    val a = chars[i - 1]
                    val b = chars[i]
                    val gap = (b.quad.ul_x - a.quad.ur_x) * d.x + (b.quad.ul_y - a.quad.ur_y) * d.y
                    a.font.name != b.font.name || kotlin.math.abs(a.size - b.size) > 0.2f || a.argb != b.argb || gap > a.size * 1.6f
                }
                if (!cut) continue
                val part = chars.subList(start, i)
                start = i
                val text = String(part.flatMap { Character.toChars(it.c).toList() }.toCharArray()).trim()
                if (text.isEmpty()) continue
                val xs = part.flatMap { listOf(it.quad.ul_x, it.quad.ur_x, it.quad.ll_x, it.quad.lr_x) }
                val ys = part.flatMap { listOf(it.quad.ul_y, it.quad.ur_y, it.quad.ll_y, it.quad.lr_y) }
                val box = Box(xs.min(), ys.min(), xs.max(), ys.max())
                val first = part.first { !Character.isWhitespace(it.c) }
                val last = part.last { !Character.isWhitespace(it.c) }
                val length = (last.quad.ur_x - first.quad.ul_x) * d.x + (last.quad.ur_y - first.quad.ul_y) * d.y
                val f = first.font
                val onPicture = pictures.any { r -> r.x0 < box.x1 && box.x0 < r.x1 && r.y0 < box.y1 && box.y0 < r.y1 }
                out += TextRun(
                    index, text, box, first.origin.x, first.origin.y, d.x, d.y, first.size, first.argb and 0xFFFFFF, f.name ?: "",
                    runCatching { f.isBold }.getOrDefault(false) || f.name.orEmpty().contains("Bold", true),
                    runCatching { f.isItalic }.getOrDefault(false) || f.name.orEmpty().contains("Italic", true) || f.name.orEmpty().contains("Oblique", true),
                    runCatching { f.isSerif }.getOrDefault(false), runCatching { f.isMono }.getOrDefault(false),
                    length.coerceAtLeast(1f), onPicture,
                ) to f
            }
        }
        return out
    }

    /**
     * Writes [text] in place of [run] on [page] (one of [doc]'s pages). Fails, changing nothing,
     * when the page has places marked to be blacked out (they would go too) or when the new words
     * need letters no font at hand has.
     */
    fun replace(doc: PDFDocument, page: PDFPage, run: TextRun, text: String) {
        if (page.annotations?.any { it.type == PDFAnnotation.TYPE_REDACT } == true) {
            throw IllegalStateException("This page has places marked to be blacked out. Black them out or remove them first.")
        }
        val fresh = runsOf(page, run.page)
        val (now, origFont) = fresh.firstOrNull { (r, _) -> kotlin.math.abs(r.x - run.x) < 0.5f && kotlin.math.abs(r.y - run.y) < 0.5f && r.text == run.text }
            ?: throw IllegalStateException("Those words have changed since they were picked. Tap them again.")
        val words = text.replace('\n', ' ').trim()
        // The font: the file's own when it has every letter, else the standard one nearest it.
        val own = words.isEmpty() || words.all { ch -> ch == ' ' || runCatching { origFont.encodeCharacter(ch.code) > 0 }.getOrDefault(false) }
        val ownFont = if (own && words.isNotEmpty()) runCatching { doc.addFont(origFont) }.getOrNull() else null
        val stdName = standard(now)
        val std = if (ownFont == null) Font(stdName) else null
        val bytes: ByteArray
        var advance = 0f
        // Fonts cut down to the letters a file uses often have no space: spaces are then gaps.
        var spaces: List<Int> = emptyList()
        if (ownFont != null) {
            val spaceGid = runCatching { origFont.encodeCharacter(' '.code) }.getOrDefault(0)
            val kept = if (spaceGid > 0) words else words.filter { it != ' ' }
            if (spaceGid <= 0) spaces = words.withIndex().filter { it.value == ' ' }.map { (i, _) -> i - words.substring(0, i).count { it == ' ' } }
            val gids = kept.map { origFont.encodeCharacter(it.code) }
            bytes = ByteArray(gids.size * 2).also { b -> gids.forEachIndexed { i, g -> b[2 * i] = (g shr 8).toByte(); b[2 * i + 1] = g.toByte() } }
            for (g in gids) advance += origFont.advanceGlyph(g)
            advance += spaces.size * SPACE
        } else {
            bytes = encode(words) ?: throw IllegalArgumentException("Those letters cannot be written here yet: Latin letters, numbers and the usual marks can.")
            for (b in bytes) advance += std!!.advanceGlyph(std.encodeCharacter(String(byteArrayOf(b), charset("windows-1252"))[0].code))
        }
        val natural = advance * now.size
        // A little longer than the old words: drawn a little narrower so it fits where they were.
        val squeeze = if (natural > now.length && natural <= now.length * 1.25f) now.length / natural else 1f

        // The paper's colour under the words, for a scanned page.
        val paper = if (now.onPicture) paperColour(page, now.box) else null

        // Take the old words out of the page (not the pictures or lines around them).
        val h = now.box.y1 - now.box.y0
        val w = now.box.x1 - now.box.x0
        val area = if (kotlin.math.abs(now.dirY) < 0.01f) Rect(now.box.x0, now.box.y0 + h * 0.2f, now.box.x1, now.box.y1 - h * 0.2f)
            else Rect(now.box.x0 + w * 0.2f, now.box.y0, now.box.x1 - w * 0.2f, now.box.y1)
        val a = page.createAnnotation(PDFAnnotation.TYPE_REDACT)
        a.rect = area
        a.update()
        page.applyRedactions(false, PDFPage.REDACT_IMAGE_NONE, PDFPage.REDACT_LINE_ART_NONE, PDFPage.REDACT_TEXT_REMOVE)

        val inv = Matrix.Inverted(page.transform)
        val sb = StringBuilder()
        fun pt(x: Float, y: Float) = (x * inv.a + y * inv.c + inv.e) to (x * inv.b + y * inv.d + inv.f)
        if (paper != null) {
            // Paint over the picture of the old words, a hair beyond them.
            val m = now.size * 0.12f
            val corners = listOf(pt(now.box.x0 - m, now.box.y0 - m), pt(now.box.x1 + m, now.box.y0 - m), pt(now.box.x1 + m, now.box.y1 + m), pt(now.box.x0 - m, now.box.y1 + m))
            sb.append("q ").append(rgb(paper)).append(" rg ")
            corners.forEachIndexed { i, (px, py) -> sb.append(n(px)).append(' ').append(n(py)).append(if (i == 0) " m " else " l ") }
            sb.append("h f Q\n")
        }
        val pageObj = page.getObject()
        val res = TextLayer.ownResources(doc, pageObj)
        if (words.isNotEmpty()) {
            val key = "PDFxEdit" + Integer.toHexString((if (ownFont != null) now.font else stdName).hashCode())
            var fonts = res.get("Font")
            if (fonts.isNull) { fonts = doc.newDictionary(); res.put("Font", fonts) }
            fonts.put(key, ownFont ?: doc.addSimpleFont(std!!, 0))
            // Text space → the page as shown → PDF space, along the line's own direction.
            val s = now.size
            val va = s * now.dirX * squeeze; val vb = s * now.dirY * squeeze
            val vc = s * now.dirY; val vd = -s * now.dirX
            val ta = va * inv.a + vb * inv.c; val tb = va * inv.b + vb * inv.d
            val tc = vc * inv.a + vd * inv.c; val td = vc * inv.b + vd * inv.d
            val (te, tf) = pt(now.x, now.y)
            sb.append("q ").append(rgb(now.color)).append(" rg BT /").append(key).append(" 1 Tf ")
                .append(n(ta)).append(' ').append(n(tb)).append(' ').append(n(tc)).append(' ').append(n(td)).append(' ').append(n(te)).append(' ').append(n(tf)).append(" Tm [<")
            val unit = if (ownFont != null) 2 else 1
            for ((k, b) in bytes.withIndex()) {
                // A gap the width of a space where the font has none.
                if (k % unit == 0 && (k / unit) in spaces) sb.append("> ").append(-(SPACE * 1000).toInt()).append(" <")
                sb.append(String.format(Locale.US, "%02X", b.toInt() and 0xFF))
            }
            sb.append(">] TJ ET Q\n")
        }
        std?.destroy()
        if (sb.isEmpty()) return
        val old = pageObj.get("Contents")
        val contents = doc.newArray()
        contents.append(doc.addStream("q\n"))
        if (old.isArray) for (i in 0 until old.size()) contents.append(old.get(i)) else if (!old.isNull) contents.append(old)
        contents.append(doc.addStream("\nQ\n"))
        contents.append(doc.addStream(sb.toString()))
        pageObj.put("Contents", contents)
    }

    /** The standard font of the same kind: Times for serif, Courier for fixed width, else Helvetica; bold and italic kept. */
    internal fun standard(r: TextRun): String {
        val base = when { r.mono -> "Courier"; r.serif -> "Times"; else -> "Helvetica" }
        val style = when {
            r.bold && r.italic -> if (base == "Times") "BoldItalic" else "BoldOblique"
            r.bold -> "Bold"
            r.italic -> if (base == "Times") "Italic" else "Oblique"
            else -> null
        }
        return when {
            style == null && base == "Times" -> "Times-Roman"
            style == null -> base
            else -> "$base-$style"
        }
    }

    /** The commonest light colour in [box] on the page as drawn: the paper the words sit on. */
    private fun paperColour(page: PDFPage, box: Box): Int {
        val pix = page.toPixmap(Matrix(1f), ColorSpace.DeviceRGB, false)
        try {
            val sx = pix.x
            val sy = pix.y
            val n = 3
            val samples = pix.samples
            val stride = pix.stride
            val counts = HashMap<Int, Int>()
            val x0 = (box.x0 - sx).toInt().coerceIn(0, pix.width - 1)
            val x1 = (box.x1 - sx).toInt().coerceIn(0, pix.width - 1)
            val y0 = (box.y0 - sy).toInt().coerceIn(0, pix.height - 1)
            val y1 = (box.y1 - sy).toInt().coerceIn(0, pix.height - 1)
            for (yy in y0..y1) for (xx in x0..x1) {
                val o = yy * stride + xx * n
                val r = samples[o].toInt() and 0xFF
                val g = samples[o + 1].toInt() and 0xFF
                val b = samples[o + 2].toInt() and 0xFF
                // Grouped to steps of 8, so paper's grain counts as one colour.
                val k = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
                counts.merge(k, 1, Int::plus)
            }
            // The commonest among the lighter half: paper, not ink.
            val light = counts.entries.filter { e -> val k = e.key; ((k shr 10) + ((k shr 5) and 31) + (k and 31)) * 8 / 3 > 110 }
            val best = (light.ifEmpty { counts.entries.toList() }).maxByOrNull { it.value }?.key ?: return 0xFFFFFF
            return (((best shr 10) and 31) * 8 + 4 shl 16) or (((best shr 5) and 31) * 8 + 4 shl 8) or ((best and 31) * 8 + 4)
        } finally {
            pix.destroy()
        }
    }

    private fun rgb(c: Int) = "${n(((c shr 16) and 0xFF) / 255f)} ${n(((c shr 8) and 0xFF) / 255f)} ${n((c and 0xFF) / 255f)}"

    private fun encode(text: String): ByteArray? = runCatching {
        val enc = charset("windows-1252").newEncoder().onUnmappableCharacter(CodingErrorAction.REPORT).onMalformedInput(CodingErrorAction.REPORT)
        val buf: ByteBuffer = enc.encode(CharBuffer.wrap(text))
        ByteArray(buf.remaining()).also { buf.get(it) }
    }.getOrNull()

    private fun n(v: Float) = String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.').ifEmpty { "0" }
}
