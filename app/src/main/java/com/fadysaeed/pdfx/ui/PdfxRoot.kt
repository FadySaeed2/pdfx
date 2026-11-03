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

/** The library and an open document. A file handed over by another app opens straight away. */
@Composable
fun PdfxRoot(incoming: Uri?, onIncomingHandled: () -> Unit) {
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

    LaunchedEffect(incoming) {
        if (incoming != null) {
            onIncomingHandled()
            openFromApp(incoming)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { openFromApp(uri) }
    }

    val current = open
    // A last stop for the back gesture inside a file: the viewer's own handling comes first; this
    // only makes sure a file never closes the whole app.
    androidx.activity.compose.BackHandler(enabled = current != null) { open = null }
    if (current == null) {
        LibraryScreen(
            vm = vm,
            // Opened from a search hit: carry the words in, so they are found and lit up inside.
            onOpen = { d, page ->
                val q = vm.ui.value.query.trim().takeIf { it.isNotEmpty() && page != null }
                open = OpenRequest(Uri.parse(d.uri), page, q)
            },
            onPick = { picker.launch(arrayOf("application/pdf")) },
        )
    } else {
        ViewerScreen(current, onOpenOther = { open = OpenRequest(it) }) { message ->
            if (message != null) vm.notice.value = Notice(message)
            open = null
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
