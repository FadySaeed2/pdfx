package com.fadysaeed.pdfx

import android.app.Application
import com.fadysaeed.pdfx.data.Library
import com.fadysaeed.pdfx.data.LibraryDb
import com.fadysaeed.pdfx.data.Saver
import com.fadysaeed.pdfx.data.TextIndexer
import com.fadysaeed.pdfx.data.Thumbs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PdfxApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: LibraryDb by lazy { LibraryDb.open(this) }
    val library: Library by lazy { Library(this, db) }
    val thumbs: Thumbs by lazy { Thumbs(this, library) }
    val indexer: TextIndexer by lazy { TextIndexer(this, library) }
    val saver: Saver by lazy { Saver(library) }
    private val scanLock = Mutex()
    private var prepared = false
    @Volatile private var lastScan = 0L

    /**
     * Looks for new, changed and missing files, then reads any new text in the background.
     * Coming back to the app rescans at most every 20 seconds unless [force] is set.
     */
    fun refresh(force: Boolean = false) {
        scope.launch {
            if (scanLock.isLocked) return@launch
            if (!force && System.currentTimeMillis() - lastScan < 20_000) return@launch
            scanLock.withLock {
                lastScan = System.currentTimeMillis()
                if (!prepared) {
                    runCatching { library.migrateRecents() }
                    runCatching { library.purgeTrash() }
                    prepared = true
                }
                runCatching { library.scan() }
                TextIndexer.schedule(this@PdfxApp)
            }
        }
    }
}
