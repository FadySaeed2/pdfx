package com.fadysaeed.pdfx.data

import android.net.Uri
import com.artifex.mupdf.fitz.Document
import com.fadysaeed.pdfx.engine.Reading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Reads the words on many scans in the background, a file at a time and a page at a time, while
 * the rest of the app stays free: the library shows how far it is and can stop it. Each file is
 * written afresh beside itself and only then put in its place (the version before stays in
 * Earlier versions), so stopping half way leaves every file whole.
 */
class ScanReading(private val scope: CoroutineScope, private val library: Library, private val saver: Saver, private val reindex: () -> Unit) {
    data class Progress(val file: Int, val files: Int, val page: Int, val pages: Int, val name: String, val done: Int)

    private val _state = MutableStateFlow<Progress?>(null)
    /** Where it is, or null when nothing is being read. */
    val state: StateFlow<Progress?> = _state

    /**
     * How the last run ended, for a one-line notice: files whose words were read, files where no
     * words were found (photos, drawings), files that could not be read and why, and whether it was stopped.
     */
    data class Outcome(val read: Int, val blank: Int, val failed: Int, val why: String?, val all: Int, val stopped: Boolean)
    val finished = MutableStateFlow<Outcome?>(null)

    @Volatile private var stop = false
    private var job: Job? = null

    private class Stopped : RuntimeException("Stopped")

    fun start(ids: List<Long>) {
        if (job?.isActive == true || ids.isEmpty()) return
        stop = false
        job = scope.launch(Dispatchers.Default) {
            var done = 0
            var blank = 0
            var failed = 0
            var why: String? = null
            for ((k, id) in ids.withIndex()) {
                if (stop) break
                val d = library.dao.byId(id) ?: continue
                val path = d.path ?: continue
                val name = Names.shown(d)
                _state.value = Progress(k + 1, ids.size, 0, d.pages, name, done)
                var found = 0
                val r = saver.replace(d, Uri.parse(d.uri)) { out ->
                    found = Reading.makeSearchable(path, out.absolutePath) { page, pages ->
                        if (stop) throw Stopped()
                        _state.value = Progress(k + 1, ids.size, page, pages, name, done)
                    }
                    Document.openDocument(out.absolutePath).let { x -> x.countPages().also { x.destroy() } }
                }
                when {
                    r.isSuccess -> {
                        if (found > 0) done++ else blank++
                        // Read once is enough, words or not: it leaves "Scans with no words".
                        library.markScanRead(id)
                        // Its words go into search, and its kind is told again, now that it has words.
                        library.dao.byId(id)?.let { library.dao.update(it.copy(kind = null, indexedAt = 0)) }
                    }
                    r.exceptionOrNull() is Stopped -> Unit
                    else -> { failed++; if (why == null) why = r.exceptionOrNull()?.message }
                }
            }
            _state.value = null
            finished.value = Outcome(done, blank, failed, why, ids.size, stop)
            reindex()
        }
    }

    fun stop() { stop = true }
}
