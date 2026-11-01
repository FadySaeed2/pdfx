package com.fadysaeed.pdfx.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * A saved signature or set of initials. Drawn ones keep their strokes, scaled so the width is 1
 * (heights are a fraction of that); photographed ones keep a cleaned-up picture with a clear
 * background. [aspect] is width over height.
 */
data class SavedSign(
    val id: String,
    val initials: Boolean,
    val strokes: List<List<Pair<Float, Float>>>,
    val aspect: Float,
    val color: Int,
    val image: String? = null,
)

/** Signatures and initials, kept on the phone only, in the app's own files. */
class Signatures(private val dir: File) {
    private val file = File(dir, "signatures.json")
    private val pictures = File(dir, "signatures").apply { mkdirs() }

    @Synchronized
    fun list(): List<SavedSign> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val strokes = o.optJSONArray("strokes")?.let { ss ->
                (0 until ss.length()).map { k ->
                    val pts = ss.getJSONArray(k)
                    (0 until pts.length() / 2).map { j -> pts.getDouble(j * 2).toFloat() to pts.getDouble(j * 2 + 1).toFloat() }
                }
            } ?: emptyList()
            SavedSign(o.getString("id"), o.optBoolean("initials"), strokes, o.optDouble("aspect", 3.0).toFloat(), o.optInt("color", Color.BLACK), o.optString("image").ifEmpty { null })
        }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun write(all: List<SavedSign>) {
        val arr = JSONArray()
        all.forEach { s ->
            val o = JSONObject().put("id", s.id).put("initials", s.initials).put("aspect", s.aspect.toDouble()).put("color", s.color)
            if (s.image != null) o.put("image", s.image)
            o.put("strokes", JSONArray().apply { s.strokes.forEach { st -> put(JSONArray().apply { st.forEach { put(it.first.toDouble()); put(it.second.toDouble()) } }) } })
            arr.put(o)
        }
        val tmp = File(dir, "signatures.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    /** Saves drawn strokes (in any units); they are scaled to a width of 1. */
    fun addDrawn(strokes: List<List<Pair<Float, Float>>>, initials: Boolean, color: Int): SavedSign? {
        val pts = strokes.flatten()
        if (pts.size < 2) return null
        val x0 = pts.minOf { it.first }; val x1 = pts.maxOf { it.first }
        val y0 = pts.minOf { it.second }; val y1 = pts.maxOf { it.second }
        val w = (x1 - x0).coerceAtLeast(1f)
        val h = (y1 - y0).coerceAtLeast(1f)
        val scaled = strokes.filter { it.isNotEmpty() }.map { st -> st.map { (it.first - x0) / w to (it.second - y0) / w } }
        val s = SavedSign(UUID.randomUUID().toString(), initials, scaled, w / h, color)
        write(list() + s)
        return s
    }

    /** Saves a photographed signature: the ink kept, the paper made clear, trimmed to the ink. */
    fun addPhoto(context: Context, uri: Uri, initials: Boolean): SavedSign? {
        val bmp = decode(context, uri) ?: return null
        val clean = clearBackground(bmp) ?: return null
        val id = UUID.randomUUID().toString()
        val out = File(pictures, "$id.png")
        FileOutputStream(out).use { clean.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val s = SavedSign(id, initials, emptyList(), clean.width.toFloat() / clean.height.coerceAtLeast(1), Color.BLACK, out.absolutePath)
        write(list() + s)
        return s
    }

    fun delete(id: String) {
        val all = list()
        all.firstOrNull { it.id == id }?.image?.let { File(it).delete() }
        write(all.filter { it.id != id })
    }

    private fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2000) sample *= 2
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }.getOrNull()

    companion object {
        /**
         * Keeps the ink and drops the paper: a threshold between the two (Otsu's method on
         * brightness), ink turned dark with its softness kept at the edges, then a tight crop.
         */
        fun clearBackground(src: Bitmap): Bitmap? {
            val w = src.width
            val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            val lum = IntArray(px.size) { i -> val c = px[i]; (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 }
            val hist = IntArray(256)
            lum.forEach { hist[it]++ }
            val total = lum.size.toDouble()
            var sum = 0.0
            for (t in 0..255) sum += t * hist[t].toDouble()
            var sumB = 0.0; var wB = 0.0; var best = 0.0; var thr = 128
            for (t in 0..255) {
                wB += hist[t]; if (wB == 0.0) continue
                val wF = total - wB; if (wF == 0.0) break
                sumB += t * hist[t].toDouble()
                val mB = sumB / wB; val mF = (sum - sumB) / wF
                val between = wB * wF * (mB - mF) * (mB - mF)
                // Cut half way between the ink's mean and the paper's.
                if (between > best) { best = between; thr = ((mB + mF) / 2).toInt() }
            }
            var minX = w; var minY = h; var maxX = -1; var maxY = -1
            val out = IntArray(px.size)
            val soft = 24
            for (i in px.indices) {
                val l = lum[i]
                if (l < thr + soft) {
                    val a = (((thr + soft - l) * 255) / (soft * 2)).coerceIn(0, 255)
                    if (a > 40) {
                        out[i] = Color.argb(a, 20, 20, 24)
                        val x = i % w; val y = i / w
                        if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                    }
                }
            }
            if (maxX < 0) return null
            val pad = 6
            val cx0 = (minX - pad).coerceAtLeast(0); val cy0 = (minY - pad).coerceAtLeast(0)
            val cx1 = (maxX + pad).coerceAtMost(w - 1); val cy1 = (maxY + pad).coerceAtMost(h - 1)
            val full = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
            return Bitmap.createBitmap(full, cx0, cy0, cx1 - cx0 + 1, cy1 - cy0 + 1)
        }
    }
}
