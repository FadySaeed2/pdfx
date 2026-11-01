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
            val d = library.dao.nextToIndex() ?: break
            index(d)
            n++
        }
        return n
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
            val rows = ArrayList<PageText>()
            for (p in 0 until session.pageCount) {
                val text = runCatching { session.text(p) }.getOrDefault("")
                    .replace(Regex("\\s+"), " ").trim()
                if (text.isNotEmpty()) rows += PageText(page = p, body = text.take(MAX_CHARS_PER_PAGE))
            }
            withContext(Dispatchers.IO) { library.text.replace(d.id, rows) }
            markDone(d, pages = session.pageCount, locked = false)
        } finally {
            session.close()
        }
    }

    private suspend fun markDone(d: Doc, pages: Int, locked: Boolean) {
        val now = library.dao.byId(d.id) ?: return
        library.dao.update(now.copy(indexedAt = d.modified, pages = if (pages > 0) pages else now.pages, locked = locked))
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
        app.indexer.runAll(stopIf = { isStopped })
        return Result.success()
    }
}
