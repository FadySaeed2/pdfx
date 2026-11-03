package com.fadysaeed.pdfx.ui.tools

import com.fadysaeed.pdfx.PdfxApp
import com.fadysaeed.pdfx.data.Doc
import com.fadysaeed.pdfx.engine.Arranger
import com.fadysaeed.pdfx.engine.Books
import com.fadysaeed.pdfx.engine.Reading
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** New PDFs made from other things: pictures (scans, photos) and books. */
object Making {
    fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date())

    /**
     * Pictures as pages of a new file in Documents/PDFx/Scans named "[prefix] date time", with their
     * words read so it can be searched and copied from. [say] hears what is happening. Returns the
     * file and how many of its pages got words.
     */
    suspend fun picturesToPdf(app: PdfxApp, pictures: List<File>, prefix: String, say: (String) -> Unit): Result<Pair<Doc, Int>> {
        say("Saving…")
        var read = 0
        val r = app.saver.create(null, "$prefix ${stamp()}", into = File(app.library.homeDir, "Scans")) { out ->
            val bare = File(app.cacheDir, "pictures-${System.nanoTime()}.pdf")
            try {
                val n = Arranger.fromPictures(pictures.map { it.absolutePath }, bare.absolutePath)
                // Reading the words is a bonus: if it fails, the file is kept as pictures.
                runCatching {
                    read = Reading.makeSearchable(bare.absolutePath, out.absolutePath) { k, all ->
                        say(if (all == 1) "Reading the words…" else "Reading the words on page $k of $all…")
                    }
                }.onFailure { bare.copyTo(out, overwrite = true) }
                n
            } finally {
                bare.delete()
            }
        }
        return r.map { it to read }
    }

    /** A book as a PDF in Documents/PDFx/Books, named by its title. */
    suspend fun bookToPdf(app: PdfxApp, book: File, name: String, size: Books.Size, fontSize: Float, say: (String) -> Unit): Result<Doc> {
        say("Laying out the book…")
        var title = ""
        val probe = runCatching {
            val d = com.artifex.mupdf.fitz.Document.openDocument(book.absolutePath)
            try { d.getMetaData("info:Title")?.trim().orEmpty() } finally { d.destroy() }
        }.getOrDefault("")
        title = probe.ifBlank { name.substringBeforeLast('.') }.replace(Regex("""[\\/:*?"<>|]"""), " ").trim().take(80).ifBlank { "Book" }
        return app.saver.create(null, title, into = File(app.library.homeDir, "Books")) { out ->
            Books.toPdf(book.absolutePath, out.absolutePath, size, fontSize) { k, n -> say("Writing page $k of $n…") }.pages
        }
    }
}
