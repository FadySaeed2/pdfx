package com.fadysaeed.pdfx.data

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.fadysaeed.pdfx.PdfxApp
import com.fadysaeed.pdfx.engine.PdfSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reads the words on every page of every PDF into the search index, one file at a time, most
 * recently opened first. A file is read again only when it changes on disk.
 */
class TextIndexer(private val context: Context, private val library: Library) {

    /** Indexes until nothing is left. Returns how many files were read. */
    suspend fun runAll(stopIf: () -> Boolean = { false }): Int {
        var n = 0
        while (!stopIf()) {
            val d = library.dao.nextToIndex()
            if (d == null) {
                // Once all are read: "titles" several files share are banners, not titles; read those again.
                if (library.fixSharedTitles() == 0) break else continue
            }
            index(d)
            n++
            if (n % 50 == 0) library.fixSharedTitles()
        }
        // Highlights and notes of files read before PDFx kept them, and of any that changed since.
        if (!stopIf()) readMarks(stopIf)
        return n
    }

    /** Reads the marks of every file whose marks are not read for its present version. */
    suspend fun readMarks(stopIf: () -> Boolean = { false }) {
        val read = withContext(Dispatchers.IO) { library.marks.readAt() }
        for (d in library.dao.all()) {
            if (stopIf()) return
            if (d.inTrash || !d.isFile || d.locked || read[d.id] == d.modified) continue
            val s = runCatching { PdfSession.open(context, Uri.parse(d.uri)) }.getOrNull() ?: continue
            try {
                if (!s.needsPassword) keepMarks(d, s)
            } finally {
                s.close()
            }
        }
    }

    private suspend fun keepMarks(d: Doc, s: PdfSession) {
        val marks = runCatching { s.marksToKeep() }.getOrDefault(emptyList())
        withContext(Dispatchers.IO) {
            library.marks.replace(d.id, d.modified, marks.map { m -> MarkRow(d.id, m.page, m.kind.name, m.color, m.quoted.take(2000), m.note.take(4000), m.author.take(200)) })
        }
    }

    suspend fun index(d: Doc) {
        val session = runCatching { PdfSession.open(context, Uri.parse(d.uri)) }.getOrNull()
        if (session == null) {
            markDone(d, pages = d.pages, locked = d.locked)
            return
        }
        try {
            if (session.needsPassword) {
                withContext(Dispatchers.IO) { library.text.replace(d.id, emptyList()) }
                markDone(d, pages = 0, locked = true)
                return
            }
            // The words of every page, unless they were read since the file last changed.
            if (d.indexedAt != d.modified) {
                val rows = ArrayList<PageText>()
                for (p in 0 until session.pageCount) {
                    val text = runCatching { session.text(p) }.getOrDefault("")
                        .replace(Regex("\\s+"), " ").trim()
                    if (text.isNotEmpty()) rows += PageText(page = p, body = text.take(MAX_CHARS_PER_PAGE))
                }
                withContext(Dispatchers.IO) {
                    library.text.replace(d.id, rows)
                    // Words read off its scanned pages went with the rest: they are read again.
                    library.scanWords.forget(d.id)
                }
            }
            keepMarks(d, session)
            // Its real title, author and year, and its kind.
            val meta = runCatching { session.metadata() }.getOrDefault(emptyMap())
            val first = runCatching { session.infoPages(3) }.getOrDefault(emptyList())
            val facts = runCatching { com.fadysaeed.pdfx.engine.DocInfo.find(meta + ("File" to d.name), first.take(2), library.sharedTitles()) }.getOrNull()
            val kind = runCatching {
                val size = session.pageSize(0)
                com.fadysaeed.pdfx.engine.Kinds.of(com.fadysaeed.pdfx.engine.KindClues(
                    name = d.name, folder = d.folder, pages = session.pageCount, text = first.joinToString("\n") { it.text },
                    title = facts?.title, doi = facts?.doi, formFields = session.formFieldCount(),
                    landscape = size.width > size.height * 1.15f, creator = (meta["Creator"].orEmpty() + " " + meta["Producer"].orEmpty()),
                ))
            }.getOrDefault(com.fadysaeed.pdfx.engine.DocKind.OTHER)
            markDone(d, pages = session.pageCount, locked = false, facts = facts, kind = kind.name)
        } finally {
            session.close()
        }
    }

    private suspend fun markDone(d: Doc, pages: Int, locked: Boolean, facts: com.fadysaeed.pdfx.engine.DocFacts? = null, kind: String = com.fadysaeed.pdfx.engine.DocKind.OTHER.name) {
        val now = library.dao.byId(d.id) ?: return
        var next = now.copy(indexedAt = d.modified, infoAt = d.modified, pages = if (pages > 0) pages else now.pages, locked = locked, kind = kind)
        if (facts != null) next = next.copy(title = facts.title, author = facts.author, year = facts.year, doi = facts.doi)
        library.dao.update(next)
    }

    companion object {
        private const val MAX_CHARS_PER_PAGE = 40_000

        /** Asks WorkManager to read any unread files in the background, once the battery is not low. */
        fun schedule(context: Context) {
            val req = OneTimeWorkRequestBuilder<IndexWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("text-index", ExistingWorkPolicy.KEEP, req)
        }
    }
}

class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PdfxApp
        // An index from an earlier PDFx is moved to the faster one first, once.
        runCatching { app.library.text.upgrade() }
        app.work.withLock { app.indexer.runAll(stopIf = { isStopped }) }
        return Result.success()
    }
}
