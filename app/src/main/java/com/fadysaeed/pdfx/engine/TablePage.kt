package com.fadysaeed.pdfx.engine

import com.artifex.mupdf.fitz.ColorSpace
import com.artifex.mupdf.fitz.DefaultColorSpaces
import com.artifex.mupdf.fitz.Device
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.Path
import com.artifex.mupdf.fitz.PathWalker
import com.artifex.mupdf.fitz.Point
import com.artifex.mupdf.fitz.Quad
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.Shade
import com.artifex.mupdf.fitz.StrokeState
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.StructuredTextWalker
import com.artifex.mupdf.fitz.Text
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Options for reading a page's characters for tables: the same ones the golden files were made with. */
const val TABLE_TEXT_OPTIONS = "preserve-whitespace,clip,use-cid-for-unknown-unicode"

/** Every character on a page with what the table finder needs: box, origin, code, size, weight, font, line, direction. */
internal class CharWalker : StructuredTextWalker {
    val chars = ArrayList<TChar>()
    private var line = -1
    private var dx = 1f
    private var dy = 0f
    private var lastFont: Font? = null
    private var lastName = ""
    private var lastBold = false

    override fun onImageBlock(bbox: Rect?, transform: Matrix?, image: Image?) {}
    override fun beginTextBlock(bbox: Rect?, flags: Int) {}
    override fun endTextBlock() {}
    override fun beginLine(bbox: Rect?, wmode: Int, dir: Point?) {
        line++
        dx = dir?.x ?: 1f
        dy = dir?.y ?: 0f
    }
    override fun endLine() {}
    override fun onChar(c: Int, origin: Point?, font: Font?, size: Float, q: Quad?, argb: Int, flags: Int, bidi: Int) {
        if (font !== lastFont) {
            lastFont = font
            lastName = runCatching { font?.name ?: "" }.getOrDefault("")
            lastBold = runCatching { font?.isBold == true }.getOrDefault(false) ||
                listOf("Bold", "Black", "Heavy", "Semibold", ".B").any { lastName.contains(it) }
        }
        val qq = q ?: return
        val x0 = min(min(qq.ul_x, qq.ll_x), min(qq.ur_x, qq.lr_x))
        val x1 = max(max(qq.ul_x, qq.ll_x), max(qq.ur_x, qq.lr_x))
        val y0 = min(min(qq.ul_y, qq.ll_y), min(qq.ur_y, qq.lr_y))
        val y1 = max(max(qq.ul_y, qq.ll_y), max(qq.ur_y, qq.lr_y))
        val bold = lastBold || (flags and StructuredText.CHAR_FLAGS_BOLD) != 0
        chars += TChar(x0, y0, x1, y1, origin?.x ?: x0, origin?.y ?: y1, c, size, bold, lastName, line, dx, dy)
    }
    override fun beginStruct(standard: String?, raw: String?, index: Int) {}
    override fun endStruct() {}
    override fun onVector(bbox: Rect?, info: StructuredTextWalker.VectorInfo?, argb: Int) {}
}

/**
 * Collects a page's drawn shapes the way the table finder needs them: each filled subpath's box
 * with its colour, and each straight stroked segment. Text, images and clipping are passed over.
 */
internal class ShapeDevice : Device() {
    val shapes = ArrayList<TShape>()

    private fun pt(m: Matrix, x: Float, y: Float): FloatArray = floatArrayOf(x * m.a + y * m.c + m.e, x * m.b + y * m.d + m.f)

    private fun rgb(cs: ColorSpace?, color: FloatArray?): FloatArray {
        val c = color ?: return floatArrayOf(0f, 0f, 0f)
        val n = runCatching { cs?.numberOfComponents ?: c.size }.getOrDefault(c.size)
        return when {
            n == 1 && c.isNotEmpty() -> floatArrayOf(c[0], c[0], c[0])
            n == 3 && c.size >= 3 -> floatArrayOf(c[0], c[1], c[2])
            n == 4 && c.size >= 4 -> floatArrayOf((1 - c[0]) * (1 - c[3]), (1 - c[1]) * (1 - c[3]), (1 - c[2]) * (1 - c[3]))
            c.isNotEmpty() -> (1 - c[0]).let { floatArrayOf(it, it, it) } // a spot colour: its tint
            else -> floatArrayOf(0f, 0f, 0f)
        }
    }

    override fun fillPath(path: Path?, evenOdd: Boolean, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) {
        val p = path ?: return
        val m = ctm ?: Matrix()
        val col = rgb(cs, color)
        var box: FloatArray? = null
        fun flush() { box?.let { shapes += TShape("fill", it[0], it[1], it[2], it[3], col[0], col[1], col[2]) }; box = null }
        fun add(x: Float, y: Float) {
            val q = pt(m, x, y)
            val b = box
            if (b == null) box = floatArrayOf(q[0], q[1], q[0], q[1])
            else { b[0] = min(b[0], q[0]); b[1] = min(b[1], q[1]); b[2] = max(b[2], q[0]); b[3] = max(b[3], q[1]) }
        }
        runCatching {
            p.walk(object : PathWalker {
                override fun moveTo(x: Float, y: Float) { flush(); add(x, y) }
                override fun lineTo(x: Float, y: Float) { add(x, y) }
                override fun curveTo(cx1: Float, cy1: Float, cx2: Float, cy2: Float, ex: Float, ey: Float) { add(cx1, cy1); add(cx2, cy2); add(ex, ey) }
                override fun closePath() {}
            })
        }
        flush()
        // Done with it now, rather than whenever the collector gets to it.
        runCatching { p.destroy() }
    }

    override fun strokePath(path: Path?, stroke: StrokeState?, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) {
        val p = path ?: return
        val m = ctm ?: Matrix()
        var start: FloatArray? = null
        var last: FloatArray? = null
        fun seg(a: FloatArray, b: FloatArray) {
            // Straight horizontal or vertical segments only: rules and frame sides.
            if (abs(a[1] - b[1]) < 1f || abs(a[0] - b[0]) < 1f) shapes += TShape("line", min(a[0], b[0]), min(a[1], b[1]), max(a[0], b[0]), max(a[1], b[1]))
        }
        runCatching {
            p.walk(object : PathWalker {
                override fun moveTo(x: Float, y: Float) { val q = pt(m, x, y); start = q; last = q }
                override fun lineTo(x: Float, y: Float) { val q = pt(m, x, y); last?.let { seg(it, q) }; last = q }
                override fun curveTo(cx1: Float, cy1: Float, cx2: Float, cy2: Float, ex: Float, ey: Float) { last = pt(m, ex, ey) }
                override fun closePath() { val s = start; val l = last; if (s != null && l != null) seg(l, s); last = s }
            })
        }
        runCatching { p.destroy() }
    }

    override fun close() {}
    override fun clipPath(path: Path?, evenOdd: Boolean, ctm: Matrix?) {}
    override fun clipStrokePath(path: Path?, stroke: StrokeState?, ctm: Matrix?) {}
    override fun fillText(text: Text?, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) {}
    override fun strokeText(text: Text?, stroke: StrokeState?, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) {}
    override fun clipText(text: Text?, ctm: Matrix?) {}
    override fun clipStrokeText(text: Text?, stroke: StrokeState?, ctm: Matrix?) {}
    override fun ignoreText(text: Text?, ctm: Matrix?) {}
    override fun fillShade(shd: Shade?, ctm: Matrix?, alpha: Float, cp: Int) {}
    override fun fillImage(img: Image?, ctm: Matrix?, alpha: Float, cp: Int) {}
    override fun fillImageMask(img: Image?, ctm: Matrix?, cs: ColorSpace?, color: FloatArray?, alpha: Float, cp: Int) {}
    override fun clipImageMask(img: Image?, ctm: Matrix?) {}
    override fun popClip() {}
    override fun beginMask(area: Rect?, luminosity: Boolean, cs: ColorSpace?, bc: FloatArray?, cp: Int) {}
    override fun endMask() {}
    override fun beginGroup(area: Rect?, cs: ColorSpace?, isolated: Boolean, knockout: Boolean, blendmode: Int, alpha: Float) {}
    override fun endGroup() {}
    override fun beginTile(area: Rect?, view: Rect?, xstep: Float, ystep: Float, ctm: Matrix?, id: Int, doc_id: Int): Int = 0
    override fun endTile() {}
    override fun renderFlags(set: Int, clear: Int) {}
    override fun setDefaultColorSpaces(dcs: DefaultColorSpaces?) {}
    override fun beginLayer(name: String?) {}
    override fun endLayer() {}
    override fun beginStructure(standard: Int, raw: String?, idx: Int) {}
    override fun endStructure() {}
    override fun beginMetatext(meta: Int, text: String?) {}
    override fun endMetatext() {}
}

/** What the table finder reads from a page: its characters and its drawn shapes. */
internal fun tableInput(page: Page): PageInput {
    val st = page.toStructuredText(TABLE_TEXT_OPTIONS)
    val walker = CharWalker()
    try { st.walk(walker) } finally { runCatching { st.destroy() } }
    // The page's own drawing only, run straight from the page: a highlight the reader added is not
    // part of a table. (Not through a display list: its paths are packed and freed with the list,
    // and the Java wrappers MuPDF hands the device would later free them again.)
    val dev = ShapeDevice()
    try {
        runCatching { page.runPageContents(dev, Matrix(), null) }
        runCatching { dev.close() }
    } finally {
        runCatching { dev.destroy() }
    }
    val b = page.bounds
    return PageInput(b.x1 - b.x0, b.y1 - b.y0, walker.chars, dev.shapes)
}
