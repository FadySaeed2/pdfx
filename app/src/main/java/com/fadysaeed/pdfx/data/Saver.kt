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

    /** Drops the kept versions of files no longer in the library. Returns how many folders went. */
    fun dropOrphans(ids: Set<Long>): Int =
        root.listFiles()?.count { f -> f.isDirectory && f.name.toLongOrNull()?.let { it !in ids } == true && f.deleteRecursively() } ?: 0
}

/**
 * Saves a changed document without ever putting the original at risk: the new file is written
 * beside it under a temporary name, opened again to check it is whole, the original is kept as a
 * version, and only then does the new file take its name. Files that live inside another app
 * (a WhatsApp share) are saved as a copy in Documents/PDFx instead.
 */
class Saver(private val library: Library) {
    companion object {
        /** Saving found the file written by something else since it opened; %s is the new file's name. */
        const val CHANGED_ON_SAVE = "This file was changed by something else while it was open (its scanned pages being read, or another app). So neither is lost, your changes are in a new file beside it, %s; the file itself stays as that change left it."

        const val CHANGED = "This file was changed by something else while this was being done (its scanned pages being read, say), so nothing was replaced. Open it again and try once more."

        /** The size and last change of a file on disk: two writes apart show different stamps. */
        fun stampOf(f: File): Pair<Long, Long> = f.length() to f.lastModified()
    }

    sealed interface Saved {
        val doc: Doc
        data class InPlace(override val doc: Doc) : Saved
        /** Saved as a new file; [why] says why, when it is not the usual reason (a file inside another app). */
        data class AsCopy(override val doc: Doc, val why: String? = null) : Saved
    }

    suspend fun save(session: PdfSession, doc: Doc?, uri: Uri): Result<Saved> = withContext(Dispatchers.IO) {
        runCatching {
            val f = doc?.path?.let(::File)
            if (doc != null && f != null && f.isFile && f.canWrite() && !doc.inTrash) {
                val beside: File? = library.holding(f) {
                    // Written by something else since it opened (its scanned pages read in the
                    // background, another app's save): neither is lost. The file stays as that left
                    // it, and what is open, changes and all, becomes a new file beside it.
                    val opened = session.diskStamp
                    if (opened != null && opened != stampOf(f)) return@holding null
                    val tmp = tempFor(f)
                    try {
                        // Changes are added onto the file it read; with nothing to tell (opened through
                        // another app's link), the whole file is written afresh.
                        val incremental = opened != null && session.canSaveIncrementally()
                        if (incremental) f.copyTo(tmp, overwrite = true)
                        session.writeTo(tmp.absolutePath, incremental)
                        check(tmp, session.pageCount)
                        library.versions.keep(doc.id, f)
                        putInPlace(tmp, f)
                    } finally {
                        if (tmp.exists()) tmp.delete()
                    }
                    f
                }
                if (beside == null) {
                    val dir = f.parentFile?.takeIf { it.canWrite() } ?: library.homeDir.also { it.mkdirs() }
                    val out = newFile(dir, "${f.name.removeSuffix(".pdf").removeSuffix(".PDF")} (my changes).pdf") { tmp ->
                        session.writeTo(tmp.absolutePath, incremental = false)
                        check(tmp, session.pageCount)
                    }
                    MediaScannerConnection.scanFile(library.context, arrayOf(out.absolutePath), null, null)
                    val made = library.adoptCopy(out, doc)
                    return@runCatching Saved.AsCopy(made, CHANGED_ON_SAVE.format(made.name.removeSuffix(".pdf")))
                }
                MediaScannerConnection.scanFile(library.context, arrayOf(f.absolutePath), null, null)
                Saved.InPlace(library.afterSave(doc, f))
            } else {
                library.homeDir.mkdirs()
                val base = (doc?.name ?: library.describe(uri).first).let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
                val out = newFile(library.homeDir, base) { tmp ->
                    // Start from the original's own bytes and append the changes when possible, so a
                    // signed file keeps its signatures valid; otherwise write the whole file afresh.
                    val appended = session.canSaveIncrementally() && runCatching {
                        library.context.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } != null
                    }.getOrDefault(false)
                    session.writeTo(tmp.absolutePath, incremental = appended)
                    check(tmp, session.pageCount)
                }
                MediaScannerConnection.scanFile(library.context, arrayOf(out.absolutePath), null, null)
                Saved.AsCopy(library.adoptCopy(out, doc))
            }
        }
    }

    /**
     * Puts a new file in place of the document, the same safe way as [save]: [make] writes the new
     * file to the path it is given and returns how many pages it has. Used when the pages
     * themselves change (order, turns, pages added or taken out), which rewrites the whole file.
     */
    suspend fun replace(doc: Doc?, uri: Uri, base: Pair<Long, Long>? = null, make: (File) -> Int): Result<Saved> = withContext(Dispatchers.IO) {
        runCatching {
            val f = doc?.path?.let(::File)
            if (doc != null && f != null && f.isFile && f.canWrite() && !doc.inTrash) {
                // The file the new one is made from, as it was: [base] when what is open was read
                // earlier, else as it is now. Made without holding the file (reading a scan's words
                // takes minutes); it takes the file's place only if nothing wrote it meanwhile.
                val from = base ?: stampOf(f)
                val tmp = tempFor(f)
                try {
                    check(tmp, make(tmp))
                    library.holding(f) {
                        if (stampOf(f) != from) throw IOException(CHANGED)
                        library.versions.keep(doc.id, f)
                        putInPlace(tmp, f)
                    }
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
                MediaScannerConnection.scanFile(library.context, arrayOf(f.absolutePath), null, null)
                Saved.InPlace(library.afterSave(doc, f))
            } else {
                library.homeDir.mkdirs()
                val base = (doc?.name ?: library.describe(uri).first).let { if (it.endsWith(".pdf", true)) it else "$it.pdf" }
                val out = newFile(library.homeDir, base) { tmp -> check(tmp, make(tmp)) }
                MediaScannerConnection.scanFile(library.context, arrayOf(out.absolutePath), null, null)
                Saved.AsCopy(library.adoptCopy(out, doc))
            }
        }
    }

    /**
     * Writes a new file named [name] beside the document (or in Documents/PDFx when its folder
     * cannot take it) and adds it to the library. [make] writes it and returns its page count.
     */
    suspend fun create(doc: Doc?, name: String, into: File? = null, make: (File) -> Int): Result<Doc> = withContext(Dispatchers.IO) {
        runCatching {
            val here = into?.also { it.mkdirs() }?.takeIf { it.isDirectory && it.canWrite() }
                ?: doc?.path?.let(::File)?.parentFile?.takeIf { it.isDirectory && it.canWrite() && doc.inTrash.not() }
            val dir = here ?: library.homeDir.also { it.mkdirs() }
            val out = newFile(dir, if (name.endsWith(".pdf", true)) name else "$name.pdf") { tmp -> check(tmp, make(tmp)) }
            MediaScannerConnection.scanFile(library.context, arrayOf(out.absolutePath), null, null)
            library.adoptCopy(out, null)
        }
    }

    /** Puts an earlier version back. The file as it is now becomes a version itself, so this can be undone too. */
    suspend fun restore(doc: Doc, version: Versions.Version): Result<Doc> = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(doc.path ?: throw IOException("This file has no place on the phone."))
            check(version.file, -1)
            library.holding(f) {
                val tmp = tempFor(f)
                try {
                    version.file.copyTo(tmp, overwrite = true)
                    library.versions.keep(doc.id, f, max = 4)
                    putInPlace(tmp, f)
                    version.file.delete()
                } finally {
                    if (tmp.exists()) tmp.delete()
                }
            }
            library.afterSave(doc, f)
        }
    }

    /**
     * A name of its own beside [f] for the file being written, so two writes never share one; and
     * any left by a write that was cut off (the phone turned off half way) cleared away.
     */
    private fun tempFor(f: File): File {
        val dir = f.parentFile ?: throw IOException("The file has no folder.")
        val stale = System.currentTimeMillis() - 10 * 60_000L
        dir.listFiles { x -> x.name.startsWith(".${f.name}.") && x.name.endsWith(".pdfx-saving") && x.lastModified() < stale }?.forEach { it.delete() }
        return File(dir, ".${f.name}.${System.nanoTime()}.pdfx-saving")
    }

    /**
     * A new file in [dir] called [name] (or "name (2)" and on): [write] fills it under a temporary
     * name of its own, and the free name is picked and taken in one step, so two new files made at
     * once never end up with one name, one over the other.
     */
    private suspend fun newFile(dir: File, name: String, write: suspend (File) -> Unit): File {
        val tmp = File(dir, ".$name.${System.nanoTime()}.pdfx-saving")
        try {
            write(tmp)
            synchronized(naming) {
                val out = Library.uniqueTarget(dir, name)
                if (!tmp.renameTo(out)) throw IOException("The new file could not be finished.")
                return out
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private val naming = Any()

    /**
     * Gives the finished [tmp] the name of [f] in one step. Should the phone refuse, the original is
     * left exactly as it was and the save says so; it is never written over in place, where a cut
     * half way would leave it broken.
     */
    private fun putInPlace(tmp: File, f: File) {
        if (!tmp.renameTo(f)) throw IOException("The saved file could not take the original's place. The original is untouched; try saving again.")
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
