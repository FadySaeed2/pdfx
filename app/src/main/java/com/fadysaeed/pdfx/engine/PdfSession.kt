package com.fadysaeed.pdfx.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Page
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.Executors

/** Width and height of a page in PDF points (1/72 inch). */
data class PageSize(val width: Float, val height: Float) {
    val aspect: Float get() = if (height > 0f) width / height else 0.707f
}

/**
 * One open document. MuPDF is not thread-safe for a single document, so every call into it runs
 * on this session's own thread, one at a time. Callers just suspend; nothing blocks the UI.
 */
class PdfSession private constructor(
    private val doc: Document,
    private val source: Closeable?,
    private val dispatcher: ExecutorCoroutineDispatcher,
) {
    @Volatile var pageCount: Int = 0
        private set
    @Volatile var closed = false
        private set

    val needsPassword: Boolean get() = locked

    private var locked = false
    private val sizes = HashMap<Int, PageSize>()
    private val pageCache = object : LinkedHashMap<Int, Page>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Page>): Boolean {
            if (size > 6) { runCatching { eldest.value.destroy() }; return true }
            return false
        }
    }

    private fun prepare() {
        locked = doc.needsPassword()
        if (!locked) pageCount = doc.countPages()
    }

    /** Tries a password. True when the document is now readable. */
    suspend fun unlock(password: String): Boolean = onDoc {
        val ok = doc.authenticatePassword(password)
        if (ok) { locked = false; pageCount = doc.countPages() }
        ok
    }

    private fun page(index: Int): Page = pageCache.getOrPut(index) { doc.loadPage(index) }

    suspend fun pageSize(index: Int): PageSize = onDoc { sizeNow(index) }

    /** Sizes for a run of pages in one trip to the document thread. */
    suspend fun pageSizes(range: IntRange): List<PageSize> = onDoc { range.map { sizeNow(it) } }

    private fun sizeNow(index: Int): PageSize = sizes.getOrPut(index) {
        val b = page(index).bounds
        PageSize(b.x1 - b.x0, b.y1 - b.y0)
    }

    /** Draws one page so that it is [widthPx] pixels wide. */
    suspend fun render(index: Int, widthPx: Int): Bitmap = onDoc {
        val p = page(index)
        val s = sizeNow(index)
        val scale = widthPx / s.width
        AndroidDrawDevice.drawPage(p, Matrix(scale))
    }

    /** The plain text of a page, in reading order. */
    suspend fun text(index: Int): String = onDoc {
        val st = page(index).toStructuredText()
        try { st.asText() } finally { st.destroy() }
    }

    /** How many times [needle] appears on a page. */
    suspend fun countHits(index: Int, needle: String): Int = onDoc {
        page(index).search(needle)?.size ?: 0
    }

    suspend fun title(): String? = onDoc {
        doc.getMetaData(Document.META_INFO_TITLE)?.takeIf { it.isNotBlank() }
    }

    fun close() {
        if (closed) return
        closed = true
        dispatcher.executor.execute {
            pageCache.values.forEach { runCatching { it.destroy() } }
            pageCache.clear()
            runCatching { doc.destroy() }
            runCatching { source?.close() }
        }
        dispatcher.close()
    }

    private suspend fun <T> onDoc(block: () -> T): T {
        check(!closed) { "Document is closed" }
        return withContext(dispatcher) { block() }
    }

    companion object {
        private fun newThread(): ExecutorCoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "pdfx-doc").apply { isDaemon = true } }
                .asCoroutineDispatcher()

        /** Opens a document from any content or file Uri, reading it in place. */
        suspend fun open(context: Context, uri: Uri): PdfSession {
            val d = newThread()
            return try {
                withContext(d) {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                        ?: error("The file could not be opened.")
                    val stream = FdStream(pfd)
                    try {
                        val doc = Document.openDocument(stream, "application/pdf")
                        PdfSession(doc, stream, d).also { it.prepare() }
                    } catch (t: Throwable) {
                        stream.close(); throw t
                    }
                }
            } catch (t: Throwable) {
                d.close(); throw t
            }
        }

        /** Opens a document from a path on disk. */
        suspend fun openFile(path: String): PdfSession {
            val d = newThread()
            return try {
                withContext(d) { PdfSession(Document.openDocument(path), null, d).also { it.prepare() } }
            } catch (t: Throwable) {
                d.close(); throw t
            }
        }
    }
}
