package com.fadysaeed.pdfx.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File

/**
 * Photos made ready to become pages: turned upright the way the camera meant (the turn a phone
 * keeps beside the picture, which a PDF does not read), no bigger than they need to be for a page,
 * and saved as JPEG in the app's own space.
 */
object Pictures {
    /** Long side, in pixels: sharp on a printed A4 page at about 290 dots per inch. */
    private const val MAX_SIDE = 3400

    fun prepare(context: Context, uri: Uri, out: File): File? = runCatching {
        val r = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val raw = r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
        val turn = runCatching {
            r.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (turn) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val long = maxOf(raw.width, raw.height)
        if (long > MAX_SIDE) m.postScale(MAX_SIDE.toFloat() / long, MAX_SIDE.toFloat() / long)
        val upright = if (m.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        // A photo with see-through parts (a PNG) goes on white, as it would print.
        val flat = if (upright.hasAlpha()) {
            Bitmap.createBitmap(upright.width, upright.height, Bitmap.Config.ARGB_8888).also { b ->
                val c = android.graphics.Canvas(b)
                c.drawColor(android.graphics.Color.WHITE)
                c.drawBitmap(upright, 0f, 0f, null)
            }
        } else upright
        out.parentFile?.mkdirs()
        out.outputStream().use { flat.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        out
    }.getOrNull()
}
