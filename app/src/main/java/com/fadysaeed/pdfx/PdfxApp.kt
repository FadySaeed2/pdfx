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
    val scanReading: com.fadysaeed.pdfx.data.ScanReading by lazy { com.fadysaeed.pdfx.data.ScanReading(scope, library, saver) { TextIndexer.schedule(this) } }
    val signatures: com.fadysaeed.pdfx.data.Signatures by lazy { com.fadysaeed.pdfx.data.Signatures(filesDir) }
    val certificates: com.fadysaeed.pdfx.data.Certificates by lazy { com.fadysaeed.pdfx.data.Certificates(filesDir) }
    private val scanLock = Mutex()

    override fun onCreate() {
        super.onCreate()
        com.fadysaeed.pdfx.data.CrashReports.install(this)
        // Arabic and other scripts in books and in PDFs that carry no font for them.
        runCatching { com.fadysaeed.pdfx.engine.Books.installSystemFonts() }
        watchStorage()
    }

    private var settle: kotlinx.coroutines.Job? = null

    /**
     * Android tells PDFx when files appear, change or go anywhere on the phone (a WhatsApp
     * download, a file moved in Files). A few seconds after things settle, the library looks again,
     * so new files show up and moved ones keep their tags without reopening the app.
     */
    private fun watchStorage() {
        val watcher = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                settle?.cancel()
                settle = scope.launch {
                    kotlinx.coroutines.delay(4000)
                    if (library.hasAllFilesAccess()) refresh(force = true)
                }
            }
        }
        runCatching { contentResolver.registerContentObserver(android.provider.MediaStore.Files.getContentUri("external"), true, watcher) }
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
