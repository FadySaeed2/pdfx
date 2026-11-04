package com.fadysaeed.pdfx.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fadysaeed.pdfx.PdfxApp
import com.fadysaeed.pdfx.ui.library.LibraryModel
import com.fadysaeed.pdfx.ui.library.LibraryScreen
import com.fadysaeed.pdfx.ui.library.Notice
import com.fadysaeed.pdfx.ui.viewer.OpenRequest
import com.fadysaeed.pdfx.ui.viewer.ViewerScreen
import android.content.Intent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer

/** The library and an open document. A file handed over by another app opens straight away. */
@Composable
fun PdfxRoot(incoming: List<Uri>?, onIncomingHandled: () -> Unit, shortcut: String? = null, onShortcutHandled: () -> Unit = {}) {
    val context = LocalContext.current
    val vm: LibraryModel = viewModel()
    // Kept across the app being recreated, so an open file stays open.
    var open by rememberSaveable(stateSaver = OpenRequest.Saver) { mutableStateOf<OpenRequest?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    // If PDFx closed unexpectedly last time, offer the report so it can be passed on.
    var crash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        crash = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.fadysaeed.pdfx.data.CrashReports.pending(context) }.getOrNull() }
    }
    crash?.let { report ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { crash = null },
            containerColor = com.fadysaeed.pdfx.ui.theme.Ink.Field,
            shape = androidx.compose.ui.graphics.RectangleShape,
            title = { androidx.compose.material3.Text("PDFx closed unexpectedly", style = com.fadysaeed.pdfx.ui.theme.Type.title) },
            text = { androidx.compose.material3.Text("A report of what went wrong is kept on this phone. Share it to get the problem fixed; nothing is sent unless you send it.", style = com.fadysaeed.pdfx.ui.theme.Type.note) },
            confirmButton = {
                com.fadysaeed.pdfx.ui.components.AmberButton("Share report", {
                    crash = null
                    runCatching {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "PDFx crash report").putExtra(Intent.EXTRA_TEXT, report), null))
                    }
                })
            },
            dismissButton = { com.fadysaeed.pdfx.ui.components.GhostButton("Not now", { crash = null }, minHeight = 52.dp) },
        )
    }

    fun keep(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    val app = context.applicationContext as PdfxApp
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var receiving by remember { mutableStateOf(false) }
    /**
     * A file from another app (Gmail, WhatsApp, Drive) becomes a real file on the phone before it
     * opens, so it can still be read, merged, split and saved in place after that app stops
     * letting PDFx read it. When that cannot be done, it opens straight from the other app as before.
     */
    suspend fun openFromApp(uri: Uri) {
        keep(uri)
        receiving = true
        val d = runCatching { app.library.receive(uri) }.getOrNull()
        receiving = false
        open = OpenRequest(d?.path?.let { Uri.fromFile(java.io.File(it)) } ?: uri)
    }

    /** What is being made (a book or pictures into a PDF), shown over everything. */
    var making by remember { mutableStateOf<String?>(null) }
    /** A book waiting for its page size and type size. */
    var book by remember { mutableStateOf<Uri?>(null) }

    suspend fun picturesToPdf(uris: List<Uri>) {
        making = "Getting the pictures…"
        val dir = java.io.File(context.cacheDir, "import-${System.nanoTime()}").apply { mkdirs() }
        val files = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            uris.mapIndexedNotNull { i, u -> com.fadysaeed.pdfx.data.Pictures.prepare(context, u, java.io.File(dir, "p%03d.jpg".format(i))) }
        }
        if (files.isEmpty()) { making = null; dir.deleteRecursively(); vm.notice.value = Notice("Those pictures could not be read."); return }
        val r = com.fadysaeed.pdfx.ui.tools.Making.picturesToPdf(app, files, "Pictures") { making = it }
        dir.deleteRecursively()
        making = null
        r.fold(
            { (d, read) ->
                vm.notice.value = Notice("${if (files.size == 1) "The picture is" else "${files.size} pictures are"} now ${LibraryModel.display(d)}, in Documents/PDFx/Scans." + if (read > 0) " Its words can be searched and copied." else "")
                open = OpenRequest(Uri.parse(d.uri))
            },
            { vm.notice.value = Notice(it.message ?: "The pictures could not be made into a PDF.") },
        )
    }

    fun bookToPdf(uri: Uri, size: com.fadysaeed.pdfx.engine.Books.Size, fontSize: Float) {
        scope.launch {
            making = "Opening the book…"
            val name = app.library.describe(uri).first
            val ext = name.substringAfterLast('.', "epub").lowercase()
            val copy = java.io.File(context.cacheDir, "book-${System.nanoTime()}.$ext")
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { i -> copy.outputStream().use { i.copyTo(it) } } != null }.getOrDefault(false)
            }
            if (!ok) { making = null; vm.notice.value = Notice("The book could not be read."); return@launch }
            val r = com.fadysaeed.pdfx.ui.tools.Making.bookToPdf(app, copy, name, size, fontSize) { making = it }
            copy.delete()
            making = null
            r.fold(
                { d -> vm.notice.value = Notice("${LibraryModel.display(d)} is now a PDF, in Documents/PDFx/Books."); open = OpenRequest(Uri.parse(d.uri)) },
                { vm.notice.value = Notice(it.message ?: "The book could not be made into a PDF.") },
            )
        }
    }

    fun officeToPdf(uri: Uri) {
        scope.launch {
            making = "Opening the file…"
            val name = runCatching { app.library.describe(uri).first }.getOrDefault("Document.docx")
            val ext = name.substringAfterLast('.', "docx").lowercase()
            val copy = java.io.File(context.cacheDir, "office-${System.nanoTime()}.$ext")
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { i -> copy.outputStream().use { i.copyTo(it) } } != null }.getOrDefault(false)
            }
            if (!ok) { making = null; vm.notice.value = Notice("The file could not be read."); return@launch }
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                com.fadysaeed.pdfx.ui.tools.Making.officeToPdf(app, copy, name) { making = it }
            }
            copy.delete()
            making = null
            r.fold(
                { (d, note) ->
                    vm.notice.value = Notice("${LibraryModel.display(d)} is now a PDF, in Documents/PDFx/Converted." + (note?.let { " $it" } ?: ""))
                    open = OpenRequest(Uri.parse(d.uri))
                },
                { vm.notice.value = Notice(it.message ?: "The file could not be made into a PDF.") },
            )
        }
    }

    /** Files picked or handed over: a PDF opens; a book asks how to lay it out; pictures, Word and Excel files become PDFs. */
    suspend fun handle(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val kinds = uris.map { u ->
            val mime = runCatching { context.contentResolver.getType(u) }.getOrNull().orEmpty()
            val name = runCatching { app.library.describe(u).first }.getOrDefault("")
            when {
                mime.startsWith("image/") -> "picture"
                com.fadysaeed.pdfx.engine.Office.isOffice(name) || (mime.contains("officedocument") && !mime.contains("presentation")) || mime.contains("macroEnabled") || mime == "text/csv" || mime == "text/comma-separated-values" || mime == "text/tab-separated-values" -> "office"
                name.substringAfterLast('.', "").lowercase() in com.fadysaeed.pdfx.engine.Office.oldExtensions || mime == "application/msword" || mime == "application/vnd.ms-excel" -> "old"
                com.fadysaeed.pdfx.engine.Books.isBook(name) || mime == "application/epub+zip" || mime.contains("mobipocket") || mime.contains("fictionbook") || mime.contains("comicbook") || mime.contains("xps") -> "book"
                else -> "pdf"
            }
        }
        when {
            kinds.all { it == "picture" } -> picturesToPdf(uris)
            kinds.first() == "book" -> { uris.forEach(::keep); book = uris.first() }
            kinds.first() == "office" -> officeToPdf(uris.first())
            kinds.first() == "old" -> vm.notice.value = Notice("That is an older Word or Excel file (.doc, .xls). Open it in Word, Excel or Google Docs, save it as .docx or .xlsx, and PDFx can make it a PDF.")
            else -> openFromApp(uris[kinds.indexOf("pdf").coerceAtLeast(0)])
        }
    }

    // From the icon's shortcuts: the last file opens here; scanning and search are the library's.
    var libraryAsk by remember { mutableStateOf<String?>(null) }
    // Each request is taken once and then carried out in the screen's own scope: marking it taken
    // changes the key below, which ends this effect's own work at its first wait, half done.
    LaunchedEffect(shortcut) {
        val s = shortcut ?: return@LaunchedEffect
        onShortcutHandled()
        scope.launch { when (s) {
            "last" -> {
                val last = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.library.dao.all().filter { !it.inTrash && it.lastOpened > 0 }.maxByOrNull { it.lastOpened } }
                if (last != null) open = OpenRequest(Uri.parse(last.uri), last.lastPage)
                else vm.notice.value = Notice("No file has been opened yet.")
            }
            else -> if (s.startsWith("open:")) {
                val id = s.removePrefix("open:").toLongOrNull()
                val d = id?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.library.dao.byId(it) } }
                if (d != null && !d.inTrash) open = OpenRequest(Uri.parse(d.uri), d.lastPage)
                else vm.notice.value = Notice("That file is no longer in the library.")
            } else { open = null; libraryAsk = s }
        } }
    }

    LaunchedEffect(incoming) {
        val uris = incoming ?: return@LaunchedEffect
        onIncomingHandled()
        scope.launch { handle(uris) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { handle(uris) }
    }

    val current = open
    // A last stop for the back gesture inside a file: the viewer's own handling comes first; this
    // only makes sure a file never closes the whole app.
    androidx.activity.compose.BackHandler(enabled = current != null) { open = null }
    // A short fade as a file opens or closes, so the change of screen is seen rather than jumped.
    val fade = remember(current == null, current?.uri) { androidx.compose.animation.core.Animatable(0.35f) }
    LaunchedEffect(fade) { fade.animateTo(1f, androidx.compose.animation.core.tween(160)) }
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }) {
    if (current == null) {
        LibraryScreen(
            vm = vm,
            shortcut = libraryAsk,
            onShortcut = { libraryAsk = null },
            // Opened from a search hit: carry the words in, so they are found and lit up inside.
            onOpen = { d, page ->
                val q = vm.ui.value.query.trim().takeIf { it.isNotEmpty() && page != null }
                open = OpenRequest(Uri.parse(d.uri), page, q)
            },
            onPick = {
                picker.launch(arrayOf(
                    "application/pdf", "application/epub+zip", "application/x-mobipocket-ebook", "application/x-fictionbook+xml",
                    "application/vnd.comicbook+zip", "application/oxps", "application/vnd.ms-xpsdocument", "image/*",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.ms-word.document.macroEnabled.12",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel.sheet.macroEnabled.12",
                    "text/csv", "text/comma-separated-values", "text/tab-separated-values",
                    // Books often come without a proper type.
                    "application/octet-stream",
                ))
            },
        )
    } else {
        ViewerScreen(current, onOpenOther = { open = OpenRequest(it) }) { message ->
            if (message != null) vm.notice.value = Notice(message)
            open = null
        }
    }
    }
    book?.let { u ->
        com.fadysaeed.pdfx.ui.tools.BookDialog(
            name = remember(u) { runCatching { app.library.describe(u).first }.getOrDefault("Book") },
            onMake = { size, font -> book = null; bookToPdf(u, size, font) },
            onDismiss = { book = null },
        )
    }
    making?.let { note ->
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(com.fadysaeed.pdfx.ui.theme.Ink.Black.copy(alpha = 0.8f)),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            androidx.compose.material3.Text(note, style = com.fadysaeed.pdfx.ui.theme.Type.title)
        }
    }
    if (receiving) {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(com.fadysaeed.pdfx.ui.theme.Ink.Black.copy(alpha = 0.7f)),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            androidx.compose.material3.Text("Opening…", style = com.fadysaeed.pdfx.ui.theme.Type.title)
        }
    }
}
