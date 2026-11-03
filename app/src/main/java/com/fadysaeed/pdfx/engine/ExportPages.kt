package com.fadysaeed.pdfx.engine

import android.graphics.Bitmap
import java.io.File

/** Gathers what an open file holds, page by page, for writing it out as Word, Excel or text. */
object ExportPages {
    enum class Kind(val ext: String, val label: String, val mime: String) {
        WORD("docx", "Word", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
        EXCEL("xlsx", "Excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        TEXT("txt", "Text", "text/plain"),
    }

    /** Sharpness of pictures cut from the pages for Word. */
    private const val DPI = 200

    /**
     * The pages as blocks in reading order. Pictures ([withPictures]) are drawn into [tmp] as PNGs.
     * [progress] hears (page being read, pages in all).
     */
    suspend fun blocks(s: PdfSession, pages: List<Int>, tmp: File, withPictures: Boolean, progress: (Int, Int) -> Unit): List<Block> {
        val out = ArrayList<Block>()
        tmp.mkdirs()
        var n = 0
        for ((k, i) in pages.withIndex()) {
            progress(k + 1, pages.size)
            for (p in s.paragraphs(i)) {
                val t = p.table
                val fig = p.figure
                when {
                    t != null -> out += Block.Table(t)
                    fig != null -> {
                        val info = p.figureInfo
                        if (!withPictures) { out += Block.Text(info?.caption?.ifBlank { null } ?: "[Picture]"); continue }
                        val png = picture(s, i, fig, File(tmp, "p${i + 1}-${n++}.png")) ?: continue
                        out += Block.Picture(png.first, png.second, png.third, DPI, info?.caption.orEmpty(), info?.notes.orEmpty())
                    }
                    p.text.isNotBlank() -> out += Block.Text(p.text, p.level, p.spans, if (p.list) p.marker else "")
                }
            }
        }
        return out
    }

    /** Every table on the pages, for Excel. */
    suspend fun tables(s: PdfSession, pages: List<Int>, progress: (Int, Int) -> Unit): List<TableModel> {
        val out = ArrayList<TableModel>()
        for ((k, i) in pages.withIndex()) {
            progress(k + 1, pages.size)
            out += s.tables(i).filter { it.kind != TableKind.IMAGE && (it.rows.isNotEmpty() || it.header.isNotEmpty()) }
        }
        return out
    }

    private suspend fun picture(s: PdfSession, page: Int, box: Box, file: File): Triple<File, Int, Int>? {
        val size = s.pageSize(page)
        val scale = DPI / 72f
        val x = (box.x0 * scale).toInt().coerceAtLeast(0)
        val y = (box.y0 * scale).toInt().coerceAtLeast(0)
        val w = ((box.x1 - box.x0) * scale).toInt().coerceAtMost((size.width * scale).toInt() - x)
        val h = ((box.y1 - box.y0) * scale).toInt().coerceAtMost((size.height * scale).toInt() - y)
        if (w < 8 || h < 8) return null
        val bmp = runCatching { s.renderRegion(page, (size.width * scale).toInt(), x, y, w, h) }.getOrNull() ?: return null
        try {
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bmp.recycle()
        }
        return Triple(file, w, h)
    }

    /** Pages as people write them ("all", "5", "3-10, 12"), 0-based; null with the reason when they cannot be read. */
    fun pagesFrom(text: String, count: Int): Pair<List<Int>?, String?> {
        if (text.isBlank()) return (0 until count).toList() to null
        val (parts, why) = Splits.ranges(text, count)
        return (parts?.flatten()?.distinct()?.sorted()) to why
    }
}
