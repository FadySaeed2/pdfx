package com.fadysaeed.pdfx.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fadysaeed.pdfx.ui.library.LibraryModel
import com.fadysaeed.pdfx.ui.library.LibraryScreen
import com.fadysaeed.pdfx.ui.library.Notice
import com.fadysaeed.pdfx.ui.viewer.OpenRequest
import com.fadysaeed.pdfx.ui.viewer.ViewerScreen
import android.content.Intent

/** The library and an open document. A file handed over by another app opens straight away. */
@Composable
fun PdfxRoot(incoming: Uri?, onIncomingHandled: () -> Unit) {
    val context = LocalContext.current
    val vm: LibraryModel = viewModel()
    var open by remember { mutableStateOf<OpenRequest?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    fun keep(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    LaunchedEffect(incoming) {
        if (incoming != null) {
            keep(incoming)
            open = OpenRequest(incoming)
            onIncomingHandled()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { keep(uri); open = OpenRequest(uri) }
    }

    val current = open
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
        ViewerScreen(current) { message ->
            if (message != null) vm.notice.value = Notice(message)
            open = null
        }
    }
}
