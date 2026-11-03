package com.fadysaeed.pdfx.engine

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Reads the words on pictures of pages, on the phone. Google's text reader (ML Kit, its English
 * and Latin model built into the app) was chosen over Tesseract by measuring both on the same
 * scanned and photographed pages (OcrEvalOnDeviceTest): as many words found, fewer wrong ones on
 * photos and tables. Nothing is sent anywhere; the app has no internet permission.
 */
object Reading {
    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** The words on [bmp] in reading order, boxes in its pixels. Blocks the calling thread; never the main one. */
    fun words(bmp: Bitmap): List<ReadWord> {
        val text = Tasks.await(client.process(InputImage.fromBitmap(bmp, 0)))
        val out = ArrayList<ReadWord>()
        for (b in text.textBlocks) for (l in b.lines) for (e in l.elements) {
            val r = e.boundingBox ?: continue
            out += ReadWord(e.text, r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
        }
        return out
    }

    /** Words found at [scale] pixels per point, brought back to page points. */
    fun toPage(words: List<ReadWord>, scale: Float): List<ReadWord> =
        words.map { ReadWord(it.text, it.left / scale, it.top / scale, it.right / scale, it.bottom / scale) }

    /** Pixels per point for reading: about 300 dots per inch, no side over [maxSide] pixels. */
    fun scaleFor(width: Float, height: Float, maxSide: Int = 3300): Float = minOf(300f / 72f, maxSide / maxOf(width, height))

    /**
     * Reads every page of [path] that is only a picture and writes the file again to [out] with the
     * words laid over those pages. [progress] hears (page being read, pages to read). Returns the
     * number of pages that got words.
     */
    fun makeSearchable(path: String, out: String, progress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val doc = Document.openDocument(path) as PDFDocument
        try {
            val todo = (0 until doc.countPages()).filter { i ->
                val p = doc.loadPage(i)
                try { !TextLayer.hasText(p) } finally { p.destroy() }
            }
            var done = 0
            for ((k, i) in todo.withIndex()) {
                progress(k + 1, todo.size)
                val p = doc.loadPage(i) as PDFPage
                try {
                    val b = p.bounds
                    val scale = scaleFor(b.x1 - b.x0, b.y1 - b.y0)
                    val bmp = AndroidDrawDevice.drawPage(p, Matrix(scale))
                    val ws = try { words(bmp) } finally { bmp.recycle() }
                    if (TextLayer.add(doc, p, toPage(ws, scale)) > 0) done++
                } finally {
                    p.destroy()
                }
            }
            doc.save(out, "garbage=compact")
            return done
        } finally {
            doc.destroy()
        }
    }
}
