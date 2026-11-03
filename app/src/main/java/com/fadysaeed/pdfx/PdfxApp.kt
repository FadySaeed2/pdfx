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
    val signatures: com.fadysaeed.pdfx.data.Signatures by lazy { com.fadysaeed.pdfx.data.Signatures(filesDir) }
    val certificates: com.fadysaeed.pdfx.data.Certificates by lazy { com.fadysaeed.pdfx.data.Certificates(filesDir) }
    private val scanLock = Mutex()

    override fun onCreate() {
        super.onCreate()
        com.fadysaeed.pdfx.data.CrashReports.install(this)
    }
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
                // Files known only through another app become real files while that app still allows it.
                runCatching { library.adoptShared() }
                TextIndexer.schedule(this@PdfxApp)
            }
        }
    }
}
