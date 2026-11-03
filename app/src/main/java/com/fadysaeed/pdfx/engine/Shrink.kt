package com.fadysaeed.pdfx.engine

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import java.io.ByteArrayOutputStream

/**
 * Makes a file smaller. Every level writes the file again tidily: objects nobody uses dropped,
 * repeats kept once, streams packed, small objects packed together, fonts cut to the letters used
 * (unless the file has a form, whose fields may need other letters later). The two stronger levels
 * also make big pictures smaller and store them as JPEG. A picture is only replaced when the new one
 * is smaller, and pictures that are masks or need exact colours (1-bit, palettes, ink colours,
 * CMYK) are left alone.
 */
object Shrink {
    enum class Level(val label: String, val maxSide: Int, val quality: Int) {
        TIDY("Tidy only", 0, 0),
        BALANCED("Balanced", 2200, 75),
        SMALLEST("Smallest", 1500, 60),
    }

    class Result(val pages: Int, val pictures: Int)

    fun write(path: String, out: String, level: Level, password: String? = null): Result {
        val d = Arranger.open(path, password)
        try {
            var pictures = 0
            if (level.maxSide > 0) pictures = shrinkPictures(d, level)
            val hasForm = runCatching { !d.trailer.get("Root").get("AcroForm").get("Fields").isNull }.getOrDefault(false)
            if (!hasForm) runCatching { d.subsetFonts() }
            // Encryption is kept as it was (the default), so a protected file stays protected.
            d.save(out, "garbage=deduplicate,compress=yes,compress-fonts=yes,compress-images=yes,objstms=yes")
            return Result(d.countPages(), pictures)
        } finally {
            d.destroy()
        }
    }

    private fun shrinkPictures(d: PDFDocument, level: Level): Int {
        // Pictures other pictures use as see-through masks stay exact.
        val masks = HashSet<Int>()
        val n = d.countObjects()
        for (i in 1 until n) {
            val o = runCatching { d.newIndirect(i, 0) }.getOrNull() ?: continue
            if (!isImage(o)) continue
            val m = o.get("SMask")
            if (m.isIndirect) masks += m.asIndirect()
        }
        var done = 0
        for (i in 1 until n) {
            if (i in masks) continue
            val o = runCatching { d.newIndirect(i, 0) }.getOrNull() ?: continue
            if (!isImage(o)) continue
            if (runCatching { shrinkOne(d, o, level) }.getOrDefault(false)) done++
        }
        return done
    }

    private fun isImage(o: PDFObject): Boolean =
        runCatching { o.isStream && o.get("Subtype").asName() == "Image" }.getOrDefault(false)

    private fun shrinkOne(d: PDFDocument, o: PDFObject, level: Level): Boolean {
        if (o.get("ImageMask").asBoolean()) return false
        if (o.get("BitsPerComponent").asInteger() != 8) return false
        if (!o.get("Decode").isNull || !o.get("Mask").isNull) return false
        val cs = o.get("ColorSpace").resolve()
        when {
            cs.isName && cs.asName() == "DeviceRGB" -> {}
            cs.isName && cs.asName() == "DeviceGray" -> {}
            cs.isArray && cs.get(0).asName() == "ICCBased" -> if (cs.get(1).get("N").asInteger() !in listOf(1, 3)) return false
            else -> return false
        }
        val w = o.get("Width").asInteger()
        val h = o.get("Height").asInteger()
        if (w <= 0 || h <= 0 || w.toLong() * h > 40_000_000L) return false
        val before = o.readRawStream().size
        if (before < 30_000) return false // not worth it

        val img = d.loadImage(o)
        val bmp: Bitmap
        try {
            val pix = img.toPixmap()
            try {
                bmp = toBitmap(pix.samples, pix.width, pix.height, pix.numberOfComponents, pix.stride, pix.alpha)
            } finally {
                pix.destroy()
            }
        } finally {
            img.destroy()
        }
        val k = minOf(1f, level.maxSide.toFloat() / maxOf(w, h))
        val nw = maxOf(1, (w * k).toInt())
        val nh = maxOf(1, (h * k).toInt())
        val small = if (k < 1f) Bitmap.createScaledBitmap(bmp, nw, nh, true).also { if (it !== bmp) bmp.recycle() } else bmp
        val jpeg = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, level.quality, it) }.toByteArray()
        small.recycle()
        if (jpeg.size >= before * 0.9) return false

        val buf = Buffer()
        buf.writeBytes(jpeg)
        o.writeRawStream(buf)
        buf.destroy()
        o.put("Filter", d.newName("DCTDecode"))
        o.delete("DecodeParms")
        o.put("Width", nw)
        o.put("Height", nh)
        o.put("BitsPerComponent", 8)
        // Android writes colour JPEGs; a grey picture comes back as colour that looks the same.
        o.put("ColorSpace", d.newName("DeviceRGB"))
        return true
    }

    /** The picture's pixels as a bitmap, a row at a time so a big scan needs no second full copy in memory. */
    private fun toBitmap(s: ByteArray, w: Int, h: Int, n: Int, stride: Int, alpha: Boolean): Bitmap {
        val colours = if (alpha) n - 1 else n
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            var p = y * stride
            for (x in 0 until w) {
                row[x] = if (colours >= 3) {
                    val r = s[p].toInt() and 0xFF; val g = s[p + 1].toInt() and 0xFF; val b = s[p + 2].toInt() and 0xFF
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                } else {
                    val v = s[p].toInt() and 0xFF
                    (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
                p += n
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        return bmp
    }
}
