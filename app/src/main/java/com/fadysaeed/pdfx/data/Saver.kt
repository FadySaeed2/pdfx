package com.fadysaeed.pdfx.data

import android.media.MediaScannerConnection
import android.net.Uri
import com.artifex.mupdf.fitz.Document
import com.fadysaeed.pdfx.engine.PdfSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The last few versions of each file PDFx has changed, kept in the app's private storage so a
 * save can always be taken back. Keyed by the document's id, so renames and moves don't matter.
 */
class Versions(private val root: File) {
    data class Version(val file: File, val savedAt: Long, val size: Long)

    fun keep(docId: Long, current: File, max: Int = 3) {
        val dir = File(root, docId.toString()).apply { mkdirs() }
        val copy = File(dir, "${System.currentTimeMillis()}.pdf")
        current.copyTo(copy, overwrite = true)
        list(docId).drop(max).forEach { it.file.delete() }
    }

    fun list(docId: Long): List<Version> =
        File(root, docId.toString()).listFiles { f -> f.name.endsWith(".pdf") }
            ?.mapNotNull { f -> f.nameWithoutExtension.toLongOrNull()?.let { Version(f, it, f.length()) } }
            ?.sortedByDescending { it.savedAt }
            ?: emptyList()

    fun clear(docId: Long) { File(root, docId.toString()).deleteRecursively() }
}

/**
 * Saves a changed document without ever putting the original at risk: the new file is written
 * beside it under a temporary name, opened again to check it is whole, the original is kept as a
 * version, and only then does the new file take its name. Files that live inside another app
 * (a WhatsApp share) are saved as a copy in Documents/PDFx instead.
 */
class Saver(private val library: Library) {

    sealed interface Saved {
        val doc: Doc
        data class InPlace(override val doc: Doc) : Saved
        data class AsCopy(override val doc: Doc) : Saved
    }

    suspend fun save(session: PdfSession, doc: Doc?, uri: Uri): Result<Saved> = withContext(Dispatchers.IO) {
        runCatching {
            val f = doc?.path?.let(::File)
            if (doc != null && f != null && f.isFile && f.canWrite() && !doc.inTrash) {
                val tmp = File(f.parentFile, ".${f.name}.pdfx-saving")
                tmp.delete()
                try {
                    val incremental = session.canSaveIncrementally()
                    if (incremental) f.copyTo(tmp, overwrite = true)
                    session.writeTo(tmp.absolutePath, incremental)
                    check(tmp, session.pageCount)
                    library.versions.keep(doc.id, f)
                    if (!tmp.renameTo(f)) {
                        tmp.copyTo(f, overwrite = true)
                        tmp.delete()
                    }
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
                MediaScannerConnection.scanFile(library.context, arrayOf(f.absolutePath), null, null)
                Saved.InPlace(library.afterSave(doc, f))
            } else {
                library.homeDir.mkdirs()
                val base = (doc?.name ?: library.describe(uri).first).let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
                val out = Library.uniqueTarget(library.homeDir, base)
                val tmp = File(library.homeDir, ".${out.name}.pdfx-saving")
                try {
                    // Start from the original's own bytes and append the changes when possible, so a
                    // signed file keeps its signatures valid; otherwise write the whole file afresh.
                    val appended = session.canSaveIncrementally() && runCatching {
                        library.context.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } != null
                    }.getOrDefault(false)
                    session.writeTo(tmp.absolutePath, incremental = appended)
                    check(tmp, session.pageCount)
                    if (!tmp.renameTo(out)) throw IOException("The copy could not be finished.")
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
                MediaScannerConnection.scanFile(library.context, arrayOf(out.absolutePath), null, null)
                Saved.AsCopy(library.adoptCopy(out, doc))
            }
        }
    }

    /** Puts an earlier version back. The file as it is now becomes a version itself, so this can be undone too. */
    suspend fun restore(doc: Doc, version: Versions.Version): Result<Doc> = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(doc.path ?: throw IOException("This file has no place on the phone."))
            check(version.file, -1)
            val tmp = File(f.parentFile, ".${f.name}.pdfx-restoring")
            try {
                version.file.copyTo(tmp, overwrite = true)
                library.versions.keep(doc.id, f, max = 4)
                version.file.delete()
                if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true) }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            library.afterSave(doc, f)
        }
    }

    /** Opens the written file again: it must open, and have every page. */
    private fun check(file: File, pages: Int) {
        val d = Document.openDocument(file.absolutePath)
        try {
            if (!d.needsPassword()) {
                val n = d.countPages()
                if (n <= 0 || (pages > 0 && n != pages)) throw IOException("The saved file came out incomplete, so the original was kept.")
            }
        } finally {
            d.destroy()
        }
    }
}
