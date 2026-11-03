package com.fadysaeed.pdfx.engine

import android.graphics.Bitmap
import java.io.File

/** Pages saved as pictures, one file a page. */
object PagePictures {
    enum class Format(val ext: String, val label: String) { PNG("png", "PNG"), JPEG("jpg", "JPEG") }
    enum class Sharpness(val label: String, val dpi: Int) { SCREEN("Screen", 110), SHARP("Sharp", 200), PRINT("Print", 300) }

    /** The longest side a picture may have, so a poster-sized page cannot run the phone out of memory. */
    private const val MAX_SIDE = 7000

    /** "Name p003.png" in [dir] for each page; [progress] hears (picture being made, pictures in all). */
    suspend fun write(s: PdfSession, pages: List<Int>, dir: File, base: String, format: Format, dpi: Int, progress: (Int, Int) -> Unit): List<File> {
        dir.mkdirs()
        val digits = maxOf(2, s.pageCount.toString().length)
        val out = ArrayList<File>()
        for ((k, i) in pages.withIndex()) {
            progress(k + 1, pages.size)
            val size = s.pageSize(i)
            val scale = minOf(dpi / 72f, MAX_SIDE / maxOf(size.width, size.height))
            val bmp = s.render(i, (size.width * scale).toInt().coerceAtLeast(1))
            val f = File(dir, "$base p${(i + 1).toString().padStart(digits, '0')}.${format.ext}")
            try {
                f.outputStream().use {
                    if (format == Format.PNG) bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                    else bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
                }
            } finally {
                bmp.recycle()
            }
            out += f
        }
        return out
    }
}
